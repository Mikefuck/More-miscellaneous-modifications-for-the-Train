package com.habitrain.lottery.crate;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.player.HabiSystemItemApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardStoreStatus;
import com.habitrain.lottery.backpack.PlayerCardAdminService;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.SkinTypeKeys;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.warehouse.SystemItemBalances;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Server-authoritative CSGO-style crate/key catalogue, quota and opening transaction. */
public final class CrateService {
    public static final String NAMESPACE = HabiLotteryMod.MOD_ID;
    private static final Gson GSON = new Gson();
    private static final String STATE_FILE = "crates.json";
    private static final int DEFAULT_SKIN_WEIGHT = 100;
    private static final int MAX_WEIGHT = 1_000_000_000;
    private static final long MAX_TOTAL_WEIGHT = 4_000_000_000L;
    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();
    private static final EnumMap<SkinQuality, Integer> DEFAULT_WEEKLY = new EnumMap<>(SkinQuality.class);
    private static final EnumMap<SkinQuality, Integer> DEFAULT_MONTHLY = new EnumMap<>(SkinQuality.class);
    private static State state = new State();
    private static boolean ready;
    private static String configError = "crates.config_failed";

    static {
        DEFAULT_WEEKLY.put(SkinQuality.WHITE, 2000);
        DEFAULT_WEEKLY.put(SkinQuality.BLUE, 1000);
        DEFAULT_WEEKLY.put(SkinQuality.PURPLE, 500);
        DEFAULT_WEEKLY.put(SkinQuality.GOLD, 160);
        DEFAULT_WEEKLY.put(SkinQuality.RED, 40);
        DEFAULT_MONTHLY.put(SkinQuality.WHITE, 8000);
        DEFAULT_MONTHLY.put(SkinQuality.BLUE, 4000);
        DEFAULT_MONTHLY.put(SkinQuality.PURPLE, 2000);
        DEFAULT_MONTHLY.put(SkinQuality.GOLD, 640);
        DEFAULT_MONTHLY.put(SkinQuality.RED, 160);
    }

    private CrateService() {}

    public record Definition(String id, String nameKey, String keyId, String icon,
                             int color, SkinQuality primary, boolean prism) {}

    public record OpenResult(boolean success, String crateId, String keyId, String type,
                             String skin, SkinQuality quality, String message,
                             int weeklyRemaining, int monthlyRemaining, List<Reward> rewards,
                             long inventoryRevision) {}

    public record Reward(String kind, String id, int amount) {}

    /** Server-authoritative settings for one crate's skin pool. */
    public static final class CratePool {
        /** A disabled crate cannot be opened even when the player owns its items. */
        public boolean enabled = true;
        /** When enabled, prefer a skin the player has not unlocked yet. */
        public boolean duplicateProtection;
        /** False keeps the legacy primary-quality/prismatic behaviour. */
        public boolean customPool;
        /** Canonical {@code type/id} skin entries mapped to non-negative integer weights. */
        public Map<String, Integer> skinWeights = new LinkedHashMap<>();
        public boolean archived;
        public String name = "";
        public String description = "";
        public String tier = "white";
        public String icon = "minecraft:chest";
        public String keyName = "";
        public String keyIcon = "minecraft:tripwire_hook";
        public int color = 0xFF9A6B3F;
        public String appearancePreset = "woodland";
        public String badge = "star";
        public int skinDrawCount = 1;
        public int rollCount = 1;
        public int minimumSkinCount;
        public boolean allowSameSkinInOneOpen;
        public String rewardMode = "skin_plus_bonus";
        public List<ExtraReward> extraRewards = new ArrayList<>();
    }

    public static final class ExtraReward {
        public String type = "green_apples";
        public String cardKind = "";
        public int amount = 1;
        public double chance;
        public int weight = 100;
        public int maxPerOpen = 1;
    }

    /** A durable intent, saved before mutating any player or card balance. */
    public static final class PendingOpen {
        public String openId, playerId, crateId, keyId;
        public String weekKey, monthKey, primaryQualityId;
        public List<Reward> rewards = new ArrayList<>();
        public Map<String, Integer> cardBefore = new LinkedHashMap<>();
        public Map<String, Integer> skinDelta = new LinkedHashMap<>();
        public Map<String, Integer> weeklyDelta = new LinkedHashMap<>();
        public Map<String, Integer> monthlyDelta = new LinkedHashMap<>();
        public int apples;
        public long inventoryRevision;
    }

    public static final class State {
        public int schemaVersion = 3;
        public long revision;
        public String weekKey = "";
        public String monthKey = "";
        public Map<String, Integer> weeklyCaps = new LinkedHashMap<>();
        public Map<String, Integer> monthlyCaps = new LinkedHashMap<>();
        public Map<String, Integer> weeklyUsed = new LinkedHashMap<>();
        public Map<String, Integer> monthlyUsed = new LinkedHashMap<>();
        public Map<String, CratePool> pools = new LinkedHashMap<>();
        public Map<String, CratePool> crates = new LinkedHashMap<>();
        public Map<String, Integer> skinLifetimeCaps = new LinkedHashMap<>();
        public Map<String, Integer> skinProduced = new LinkedHashMap<>();
        public Map<String, PendingOpen> pendingOpens = new LinkedHashMap<>();
    }

    public static void register() {
        for (CrateCatalog.Entry entry : CrateCatalog.entries()) {
            add(entry, entry.primary(), entry.prism());
        }
    }

    private static void add(CrateCatalog.Entry entry, SkinQuality primary, boolean prism) {
        if (entry == null) throw new IllegalStateException("Missing crate catalogue entry");
        Definition definition = new Definition(entry.id(), entry.nameKey(), entry.keyId(), entry.icon(),
                entry.color(), primary, prism);
        DEFINITIONS.put(entry.id(), definition);
        HabiSystemItemApi.register(new HabiSystemItemApi.Definition(itemId("crate_" + entry.id()), entry.nameKey(),
                entry.nameKey() + ".hint", ResourceLocation.parse(entry.icon()), entry.color()));
        HabiSystemItemApi.register(new HabiSystemItemApi.Definition(itemId(entry.keyId()),
                "screen.habitrain_lottery.crate.key." + entry.id(),
                entry.nameKey() + ".key_hint", ResourceLocation.parse(iconForKey(entry.id())), entry.color()));
    }

    private static String iconForKey(String id) {
        return switch (id) {
            case "woodland" -> "minecraft:tripwire_hook";
            case "cobalt" -> "minecraft:iron_nugget";
            case "amethyst" -> "minecraft:amethyst_shard";
            case "gilded" -> "minecraft:gold_nugget";
            case "crimson" -> "minecraft:redstone";
            default -> "minecraft:echo_shard";
        };
    }

    private static ResourceLocation itemId(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }

    public static List<Definition> definitions() { return List.copyOf(DEFINITIONS.values()); }
    public static Definition definition(String id) {
        CrateCatalog.Entry entry = CrateCatalog.find(id);
        return entry == null ? null : DEFINITIONS.get(entry.id());
    }
    public static boolean isCrateId(String id) { return id != null && id.startsWith(NAMESPACE + ":crate_"); }
    public static boolean isKeyId(String id) { return id != null && id.startsWith(NAMESPACE + ":key_"); }
    public static String crateItemId(String id) {
        CrateCatalog.Entry entry = CrateCatalog.find(id);
        return entry == null ? NAMESPACE + ":crate_" + id : entry.crateItemId();
    }
    public static String keyItemId(String id) {
        CrateCatalog.Entry entry = CrateCatalog.find(id);
        return entry == null ? NAMESPACE + ":key_" + id : entry.keyItemId();
    }

    public static synchronized void onServerStarted() {
        CrateCatalog.reset();
        state = new State();
        if (WorldLotteryPaths.ready()) {
            var load = AtomicJsonFiles.readJson(WorldLotteryPaths.configFile(STATE_FILE), State.class, GSON);
            if (load.corrupt()) {
                ready = false;
                HabiLotteryMod.LOGGER.error("Crate config is corrupt; opening is disabled until it is recovered");
                return;
            }
            if (load.ok() && load.value() != null) state = load.value();
        }
        try {
            normalizeState();
        } catch (RuntimeException error) {
            ready = false;
            state = new State();
            CrateCatalog.reset();
            refreshDefinitions();
            HabiLotteryMod.LOGGER.error("Invalid crate config; opening is disabled to preserve the saved file", error);
            return;
        }
        if (!saveState()) {
            ready = false;
            HabiLotteryMod.LOGGER.error("Could not persist crate config; opening is disabled");
            return;
        }
        ready = true;
        for (PendingOpen pending : List.copyOf(state.pendingOpens.values())) {
            OpenResult recovered = finishPending(pending, null);
            if (!recovered.success()) HabiLotteryMod.LOGGER.warn("Crate open {} awaits recovery: {}", pending.openId, recovered.message());
        }
    }

    public static synchronized void onServerStopping() {
        if (ready) saveState();
        ready = false;
        state = new State();
        CrateCatalog.reset();
        refreshDefinitions();
    }

    private static void normalizeState() {
        if (state == null) state = new State();
        boolean migrateRewards = state.schemaVersion < 4;
        String week = weekKey(), month = monthKey();
        if (!week.equals(state.weekKey)) {
            state.weekKey = week;
            state.weeklyUsed = new LinkedHashMap<>();
        }
        if (!month.equals(state.monthKey)) {
            state.monthKey = month;
            state.monthlyUsed = new LinkedHashMap<>();
        }
        if (state.weeklyCaps == null) state.weeklyCaps = new LinkedHashMap<>();
        if (state.monthlyCaps == null) state.monthlyCaps = new LinkedHashMap<>();
        if (state.weeklyUsed == null) state.weeklyUsed = new LinkedHashMap<>();
        if (state.monthlyUsed == null) state.monthlyUsed = new LinkedHashMap<>();
        for (SkinQuality q : SkinQuality.values()) {
            state.weeklyCaps.putIfAbsent(q.id(), DEFAULT_WEEKLY.get(q));
            state.monthlyCaps.putIfAbsent(q.id(), DEFAULT_MONTHLY.get(q));
            state.weeklyUsed.putIfAbsent(q.id(), 0);
            state.monthlyUsed.putIfAbsent(q.id(), 0);
        }
        if (state.pools == null) state.pools = new LinkedHashMap<>();
        if (state.crates == null) state.crates = new LinkedHashMap<>();
        if (state.skinLifetimeCaps == null) state.skinLifetimeCaps = new LinkedHashMap<>();
        if (state.skinProduced == null) state.skinProduced = new LinkedHashMap<>();
        if (state.pendingOpens == null) state.pendingOpens = new LinkedHashMap<>();
        if (state.crates.isEmpty()) for (CrateCatalog.Entry entry : CrateCatalog.builtins()) {
            CratePool old = state.pools.get(entry.id());
            CratePool pool = old == null ? defaultPool(entry) : copyPool(old);
            pool.name = entry.nameKey(); pool.description = entry.nameKey() + ".hint";
            pool.tier = entry.primary().id(); pool.icon = entry.icon(); pool.color = entry.color();
            pool.keyName = "screen.habitrain_lottery.crate.key." + entry.id();
            pool.keyIcon = iconForKey(entry.id()); pool.appearancePreset = entry.id();
            state.crates.put(entry.id(), pool);
        }
        for (CrateCatalog.Entry entry : CrateCatalog.builtins()) {
            state.crates.computeIfAbsent(entry.id(), ignored -> defaultPool(entry));
        }
        refreshDefinitions();
        for (Definition definition : DEFINITIONS.values()) {
            CratePool pool = state.crates.get(definition.id());
            if (pool == null) continue;
            if (pool.skinWeights == null) pool.skinWeights = new LinkedHashMap<>();
            if (pool.extraRewards == null) pool.extraRewards = new ArrayList<>();
            normalizePool(definition, pool);
            if (migrateRewards) migrateRewardPool(pool);
            if (pool.customPool) {
                try {
                    validatePool(definition, pool);
                } catch (RuntimeException error) {
                    HabiLotteryMod.LOGGER.warn("Invalid crate pool {}; keeping entries for provider recovery", definition.id(), error);
                    if (!"not enough unique skins".equals(error.getMessage())
                            && !"enabled crate has no weighted skins".equals(error.getMessage())) pool.enabled = false;
                }
            }
        }
        state.pools.clear();
        state.schemaVersion = 4;
        refreshDefinitions();
    }

    /** Freeze legacy automatic skins and fold independent bonus chances into relative weights once. */
    static void migrateRewardPool(CratePool pool) {
        if (!"unified_pool".equals(pool.rewardMode)) {
            if (pool.skinDrawCount == 0) pool.skinWeights.replaceAll((key, weight) -> 0);
            for (ExtraReward reward : pool.extraRewards) {
                reward.weight = reward.chance > 0 ? Math.max(1, (int)Math.round(reward.chance * 100)) : 0;
            }
            pool.rollCount = 1;
        }
        pool.customPool = true;
        pool.rewardMode = "unified_pool";
        pool.minimumSkinCount = 0;
        if (pool.skinWeights.values().stream().noneMatch(v -> v != null && v > 0)
                && pool.extraRewards.stream().noneMatch(r -> r.weight > 0)) pool.enabled = false;
    }

    private static CratePool defaultPool(CrateCatalog.Entry entry) {
        CratePool pool = new CratePool();
        pool.customPool = true;
        pool.enabled = false;
        pool.rewardMode = "unified_pool";
        pool.name = entry.nameKey(); pool.description = entry.nameKey() + ".hint";
        pool.tier = entry.primary().id(); pool.icon = entry.icon(); pool.color = entry.color();
        pool.keyName = "screen.habitrain_lottery.crate.key." + entry.id();
        pool.keyIcon = iconForKey(entry.id()); pool.appearancePreset = entry.id();
        return pool;
    }

    private static void refreshDefinitions() {
        List<CrateCatalog.Entry> entries = new ArrayList<>();
        if (state.crates != null && !state.crates.isEmpty()) state.crates.forEach((id, pool) -> {
            if (pool == null || !id.matches("[a-z0-9_.-]{1,48}")) return;
            CrateCatalog.Entry builtin = CrateCatalog.builtin(id);
            String name = pool.name == null || pool.name.isBlank() ? id : pool.name;
            String description = pool.description == null ? "" : pool.description;
            String icon = pool.icon == null || pool.icon.isBlank() ? "minecraft:chest" : pool.icon;
            String keyName = pool.keyName == null || pool.keyName.isBlank() ? "Key: " + name : pool.keyName;
            String keyIcon = pool.keyIcon == null || pool.keyIcon.isBlank() ? "minecraft:tripwire_hook" : pool.keyIcon;
            SkinQuality quality = SkinQuality.fromId(pool.tier);
            List<String> extraKinds = pool.extraRewards == null ? List.of() : pool.extraRewards.stream()
                    .filter(r -> r != null && ("unified_pool".equals(pool.rewardMode) ? r.weight > 0 : r.chance > 0))
                    .map(r -> "card".equals(r.type) ? r.cardKind : "green_apples").toList();
            entries.add(new CrateCatalog.Entry(id, name, "key_" + id, icon, pool.color,
                    quality, builtin != null && builtin.prism(), description, keyName, keyIcon,
                    pool.tier, pool.enabled, pool.archived, pool.rewardMode, pool.skinDrawCount,
                    pool.rollCount, pool.minimumSkinCount, extraKinds, pool.appearancePreset, pool.badge,
                    publicRewards(new Definition(id, name, "key_" + id, icon, pool.color,
                            quality, builtin != null && builtin.prism()), pool)));
        });
        else entries.addAll(CrateCatalog.builtins());
        CrateCatalog.replaceEntries(entries);
        DEFINITIONS.clear();
        Map<String, HabiSystemItemApi.Definition> items = new LinkedHashMap<>();
        for (CrateCatalog.Entry entry : entries) {
            DEFINITIONS.put(entry.id(), new Definition(entry.id(), entry.nameKey(), entry.keyId(),
                    entry.icon(), entry.color(), entry.primary(), entry.prism()));
            items.put(entry.crateItemId(), new HabiSystemItemApi.Definition(ResourceLocation.parse(entry.crateItemId()),
                    entry.nameKey(), entry.description(), ResourceLocation.parse(entry.icon()), entry.color()));
            items.put(entry.keyItemId(), new HabiSystemItemApi.Definition(ResourceLocation.parse(entry.keyItemId()),
                    entry.keyName(), entry.description(), ResourceLocation.parse(entry.keyIcon()), entry.color()));
        }
        HabiSystemItemApi.replaceWorldDefinitions(items);
    }

    private static void normalizePool(Definition definition, CratePool pool) {
        if (!pool.customPool) {
            pool.skinWeights.clear();
            for (SkinDefinition skin : HabiSkinApi.registrations()) {
                if (definition.prism() || skin.quality() == definition.primary()) {
                    pool.skinWeights.put(skinKey(skin), DEFAULT_SKIN_WEIGHT);
                }
            }
            return;
        }
        // Explicit pools stay exactly as configured. The editor's add catalog supplies
        // available skins without injecting them into every crate's saved reward list.
    }

    private static String weekKey() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        return date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
    }

    private static String monthKey() { return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).toString(); }

    private static boolean saveState() {
        if (!WorldLotteryPaths.ready()) return false;
        return AtomicJsonFiles.writeJson(WorldLotteryPaths.configFile(STATE_FILE), state, GSON, true);
    }

    public static synchronized String configJson() {
        normalizeState();
        return GSON.toJson(state);
    }

    public static synchronized String catalogJson() {
        refreshDefinitions();
        return GSON.toJson(CrateCatalog.entries());
    }

    static List<CrateCatalog.RewardPreview> publicRewards(Definition definition, CratePool pool) {
        List<CrateCatalog.RewardPreview> result = new ArrayList<>();
        boolean unified = "unified_pool".equals(pool.rewardMode);
        if (unified || pool.skinDrawCount > 0) {
            for (WeightedSkin weighted : eligibleSkins(definition, pool)) {
                SkinDefinition skin = weighted.skin();
                result.add(new CrateCatalog.RewardPreview("skin", skinKey(skin), 1, skin.quality().id()));
            }
        }
        if (pool.extraRewards != null && (!unified || pool.rollCount > pool.minimumSkinCount)) {
            for (ExtraReward reward : pool.extraRewards) {
                if (reward == null || reward.amount <= 0 || reward.maxPerOpen <= 0
                        || (unified ? reward.weight <= 0 : reward.chance <= 0)) continue;
                result.add(new CrateCatalog.RewardPreview(reward.type,
                        "card".equals(reward.type) ? reward.cardKind : "green_apples", reward.amount, "white"));
            }
        }
        return List.copyOf(new java.util.LinkedHashSet<>(result));
    }

    public static synchronized void acceptCatalogJson(String json) {
        CrateCatalog.Entry[] entries = GSON.fromJson(json, CrateCatalog.Entry[].class);
        if (entries == null) return;
        CrateCatalog.replaceEntries(List.of(entries));
        DEFINITIONS.clear();
        for (CrateCatalog.Entry entry : CrateCatalog.entries()) DEFINITIONS.put(entry.id(),
                new Definition(entry.id(), entry.nameKey(), entry.keyId(), entry.icon(),
                        entry.color(), entry.primary(), entry.prism()));
    }

    public static synchronized boolean applyConfigJson(String json) {
        configError = "crates.config_failed";
        State previous = copyState(state);
        try {
            State proposed = GSON.fromJson(json == null ? "" : json, State.class);
            if (proposed == null) return false;
            if (proposed.revision != state.revision) throw new IllegalArgumentException("stale revision");
            normalizeMap(proposed.weeklyCaps);
            normalizeMap(proposed.monthlyCaps);
            Map<String, CratePool> next = new LinkedHashMap<>();
            if (proposed.crates == null || proposed.crates.isEmpty()) throw new IllegalArgumentException("missing crates");
            if (proposed.crates.size() > 256) throw new IllegalArgumentException("too many crates");
            for (Map.Entry<String, CratePool> entry : proposed.crates.entrySet()) {
                String id = entry.getKey();
                if (id == null || !id.matches("[a-z0-9_.-]{1,48}") || entry.getValue() == null)
                    throw new IllegalArgumentException("invalid crate id");
                CratePool pool = copyPool(entry.getValue());
                validateMetadata(id, pool);
                Definition definition = new Definition(id, pool.name, "key_" + id, pool.icon,
                        pool.color, SkinQuality.fromId(pool.tier), "prismatic".equals(id));
                validatePool(definition, pool);
                next.put(id, pool);
            }
            for (CrateCatalog.Entry builtin : CrateCatalog.builtins()) {
                if (!next.containsKey(builtin.id())) throw new IllegalArgumentException("built-in crate removed");
            }
            if (proposed.skinLifetimeCaps == null) proposed.skinLifetimeCaps = Map.of();
            if (proposed.skinLifetimeCaps.size() > 4096) throw new IllegalArgumentException("too many lifetime caps");
            Map<String, Integer> caps = new LinkedHashMap<>();
            proposed.skinLifetimeCaps.forEach((key, value) -> {
                String normalized = normalizeSkinKey(key);
                if (value != null && (value < 0 || value > 1_000_000_000))
                    throw new IllegalArgumentException("invalid lifetime cap");
                caps.put(normalized, value);
            });
            state.weeklyCaps = proposed.weeklyCaps;
            state.monthlyCaps = proposed.monthlyCaps;
            state.crates = next;
            state.skinLifetimeCaps = caps;
            state.revision++;
            state.schemaVersion = 4;
            if (saveState()) {
                refreshDefinitions();
                return true;
            }
            state = previous;
            configError = "crates.config_write_failed";
            return false;
        } catch (RuntimeException error) {
            state = previous;
            if ("stale revision".equals(error.getMessage())) configError = "screen.habitrain_lottery.crate_manage.conflict";
            HabiLotteryMod.LOGGER.warn("Invalid crate quota config", error);
            return false;
        }
    }

    public static synchronized String configError() { return configError; }

    private static void validateMetadata(String id, CratePool pool) {
        if (pool.name == null || pool.name.isBlank() || pool.name.length() > 64
                || pool.description == null || pool.description.length() > 256
                || pool.keyName == null || pool.keyName.isBlank() || pool.keyName.length() > 64
                || pool.tier == null || pool.tier.length() > 24
                || pool.appearancePreset == null || pool.appearancePreset.length() > 48
                || pool.badge == null || pool.badge.length() > 48)
            throw new IllegalArgumentException("invalid crate metadata");
        if (java.util.Arrays.stream(SkinQuality.values()).noneMatch(q -> q.id().equals(pool.tier)))
            throw new IllegalArgumentException("invalid crate tier");
        if (!Set.of("woodland", "cobalt", "amethyst", "gilded", "crimson", "prismatic",
                "industrial", "hazard").contains(pool.appearancePreset)
                || !Set.of("star", "diamond", "bolt", "none").contains(pool.badge))
            throw new IllegalArgumentException("invalid appearance preset");
        ResourceLocation crateIcon = ResourceLocation.parse(pool.icon);
        ResourceLocation keyIcon = ResourceLocation.parse(pool.keyIcon);
        if (!pool.icon.contains(":") || !pool.keyIcon.contains(":")
                || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(crateIcon)
                || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(keyIcon))
            throw new IllegalArgumentException("invalid icon id");
        if (pool.skinDrawCount < 0 || pool.skinDrawCount > 10 || pool.rollCount < 1 || pool.rollCount > 10
                || pool.minimumSkinCount < 0 || pool.minimumSkinCount > pool.rollCount)
            throw new IllegalArgumentException("invalid draw count");
        if (!"unified_pool".equals(pool.rewardMode) || !pool.customPool || pool.minimumSkinCount != 0)
            throw new IllegalArgumentException("invalid reward mode");
        if (pool.extraRewards == null || pool.extraRewards.size() > 32)
            throw new IllegalArgumentException("invalid extra rewards");
        Set<String> seenRewards = new HashSet<>();
        for (ExtraReward reward : pool.extraRewards) {
            if (reward == null || reward.amount <= 0 || reward.amount > 100_000
                    || reward.maxPerOpen <= 0 || reward.maxPerOpen > 10
                    || reward.chance < 0 || reward.chance > 1 || !Double.isFinite(reward.chance)
                    || reward.weight < 0 || reward.weight > MAX_WEIGHT)
                throw new IllegalArgumentException("invalid extra reward");
            if (!"green_apples".equals(reward.type) && !"card".equals(reward.type))
                throw new IllegalArgumentException("invalid reward type");
            if ("card".equals(reward.type) && com.habitrain.lottery.api.player.HabiCardKind.parse(reward.cardKind) == null)
                throw new IllegalArgumentException("invalid card kind");
            String kind = "card".equals(reward.type) ? "card/" + reward.cardKind : reward.type;
            if (!seenRewards.add(kind)) throw new IllegalArgumentException("duplicate extra reward");
        }
    }

    private static void normalizeMap(Map<String, Integer> map) {
        if (map == null) throw new IllegalArgumentException("missing caps");
        for (SkinQuality q : SkinQuality.values()) {
            int value = map.getOrDefault(q.id(), 0);
            if (value < 0 || value > MAX_WEIGHT) throw new IllegalArgumentException("invalid cap");
            map.put(q.id(), value);
        }
    }

    private static void validatePool(Definition definition, CratePool pool) {
        if (pool == null) throw new IllegalArgumentException("missing pool");
        if (pool.skinWeights == null) throw new IllegalArgumentException("missing skin weights");
        if (pool.skinWeights.size() > 4096) throw new IllegalArgumentException("too many skin entries");
        Map<String, Integer> normalized = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<String, Integer> entry : pool.skinWeights.entrySet()) {
            String key = normalizeSkinKey(entry.getKey());
            Integer rawWeight = entry.getValue();
            if (rawWeight == null || rawWeight < 0 || rawWeight > MAX_WEIGHT) {
                throw new IllegalArgumentException("invalid skin weight");
            }
            CratePool previous = state.crates == null ? null : state.crates.get(definition.id());
            if (rawWeight > 0 && HabiSkinApi.fromEntry(key).isEmpty()
                    && (previous == null || previous.skinWeights == null || !previous.skinWeights.containsKey(key)))
                throw new IllegalArgumentException("unregistered skin");
            if (normalized.put(key, rawWeight) != null) throw new IllegalArgumentException("duplicate skin entry");
            total += rawWeight;
            if (total > MAX_TOTAL_WEIGHT) throw new IllegalArgumentException("skin weights too large");
        }
        int requiredSkins = "unified_pool".equals(pool.rewardMode) ? pool.minimumSkinCount : pool.skinDrawCount;
        if (pool.customPool && pool.enabled && requiredSkins > 0 && normalized.values().stream().mapToLong(Integer::longValue).sum() <= 0) {
            throw new IllegalArgumentException("enabled crate has no weighted skins");
        }
        if (pool.customPool && pool.enabled && !pool.allowSameSkinInOneOpen && requiredSkins > 0) {
            long candidates = normalized.entrySet().stream().filter(e -> e.getValue() > 0).count();
            if (candidates < requiredSkins) throw new IllegalArgumentException("not enough unique skins");
        }
        if (pool.enabled && "skin_plus_bonus".equals(pool.rewardMode) && pool.skinDrawCount == 0
                && pool.extraRewards.stream().noneMatch(r -> r.chance > 0))
            throw new IllegalArgumentException("crate has no rewards");
        if (pool.enabled && "unified_pool".equals(pool.rewardMode)
                && normalized.values().stream().noneMatch(v -> v > 0)
                && pool.extraRewards.stream().noneMatch(r -> r.weight > 0))
            throw new IllegalArgumentException("empty unified pool");
        pool.skinWeights = normalized;
    }

    static String normalizeSkinKey(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("blank skin entry");
        String[] parts = raw.trim().toLowerCase(Locale.ROOT).split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("skin entry must be type/id");
        }
        String type = SkinTypeKeys.canonical(parts[0]);
        if (!SkinDefinition.SUPPORTED_TYPES.contains(type)) throw new IllegalArgumentException("invalid skin type");
        String id = SkinDefinition.normalizeSkinId(parts[1]);
        return HabiSkinApi.find(type, id).map(s -> s.type() + "/" + s.id()).orElse(type + "/" + id);
    }

    private static String skinKey(SkinDefinition skin) {
        return skin.type() + "/" + skin.id();
    }

    private static CratePool copyPool(CratePool source) {
        CratePool copy = new CratePool();
        if (source == null) return copy;
        copy.enabled = source.enabled;
        copy.duplicateProtection = source.duplicateProtection;
        copy.customPool = source.customPool;
        copy.skinWeights = source.skinWeights == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.skinWeights);
        copy.archived = source.archived;
        copy.name = source.name; copy.description = source.description; copy.tier = source.tier;
        copy.icon = source.icon; copy.keyName = source.keyName; copy.keyIcon = source.keyIcon;
        copy.color = source.color; copy.appearancePreset = source.appearancePreset; copy.badge = source.badge;
        copy.skinDrawCount = source.skinDrawCount; copy.rollCount = source.rollCount;
        copy.minimumSkinCount = source.minimumSkinCount;
        copy.allowSameSkinInOneOpen = source.allowSameSkinInOneOpen;
        copy.rewardMode = source.rewardMode;
        copy.extraRewards = source.extraRewards == null ? new ArrayList<>() : new ArrayList<>();
        if (source.extraRewards != null) for (ExtraReward reward : source.extraRewards) {
            ExtraReward item = new ExtraReward();
            item.type = reward.type; item.cardKind = reward.cardKind; item.amount = reward.amount;
            item.chance = reward.chance; item.weight = reward.weight; item.maxPerOpen = reward.maxPerOpen;
            copy.extraRewards.add(item);
        }
        return copy;
    }

    public static synchronized Map<String, Integer> inventory(java.util.UUID uuid) {
        if (!ready || uuid == null) return Map.of();
        PlayerLotteryData data = PlayerLotteryStore.get().getOrLoad(uuid);
        return data.systemItems == null ? Map.of() : Map.copyOf(data.systemItems);
    }

    public static synchronized OpenResult open(ServerPlayer player, String crateId, String keyId) {
        return open(player, crateId, keyId, UUID.randomUUID().toString());
    }

    public static synchronized OpenResult open(ServerPlayer player, String crateId, String keyId, String openId) {
        CrateCatalog.Entry catalogEntry = CrateCatalog.find(crateId);
        String canonicalCrate = catalogEntry == null ? "" : catalogEntry.id();
        String canonicalKey = keyId == null ? "" : keyId.trim().toLowerCase(Locale.ROOT);
        if (!ready || player == null) return fail(canonicalCrate, canonicalKey, "crates.not_ready");
        try { UUID.fromString(openId); }
        catch (RuntimeException error) { return fail(canonicalCrate, canonicalKey, "crates.invalid_request"); }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        PlayerLotteryData current = store.getOrLoad(player.getUUID());
        if (store.isLoadFailed(player.getUUID())) return fail(canonicalCrate, canonicalKey, "crates.write_failed");
        String existing = current.crateReceipts.get(openId);
        if (existing != null) {
            PendingOpen receipt = GSON.fromJson(existing, PendingOpen.class);
            if (receipt == null || !player.getUUID().toString().equals(receipt.playerId)
                    || !canonicalCrate.equals(receipt.crateId) || !canonicalKey.equals(receipt.keyId))
                return fail(canonicalCrate, canonicalKey, "crates.invalid_request");
            if (state.pendingOpens.containsKey(openId)) return finishPending(state.pendingOpens.get(openId), player);
            return resultFor(receipt);
        }
        if (state.pendingOpens.containsKey(openId)) {
            PendingOpen pending = state.pendingOpens.get(openId);
            if (!player.getUUID().toString().equals(pending.playerId)
                    || !canonicalCrate.equals(pending.crateId) || !canonicalKey.equals(pending.keyId))
                return fail(canonicalCrate, canonicalKey, "crates.invalid_request");
            return finishPending(pending, player);
        }
        if (current.crateOpenHistory.contains(openId))
            return fail(canonicalCrate, canonicalKey, "crates.replay_expired");
        for (PendingOpen pending : List.copyOf(state.pendingOpens.values())) {
            if (player.getUUID().toString().equals(pending.playerId)) {
                OpenResult recovered = finishPending(pending, player);
                return pending.crateId.equals(canonicalCrate) && pending.keyId.equals(canonicalKey)
                        ? recovered : fail(canonicalCrate, canonicalKey,
                        recovered.success() ? "crates.previous_recovered" : "crates.pending");
            }
        }
        Definition definition = DEFINITIONS.get(canonicalCrate);
        if (definition == null || !keyItemId(canonicalCrate).equals(canonicalKey)) {
            return fail(canonicalCrate, canonicalKey, "crates.wrong_key");
        }
        normalizeState();
        CratePool pool = state.crates.get(canonicalCrate);
        if (pool == null || !pool.enabled) return fail(canonicalCrate, canonicalKey, "crates.disabled");
        int crateCount = current.systemItems.getOrDefault(crateItemId(canonicalCrate), 0);
        int keyCount = current.systemItems.getOrDefault(canonicalKey, 0);
        if (crateCount <= 0 || keyCount <= 0) return fail(canonicalCrate, canonicalKey, "crates.missing_items");
        List<WeightedSkin> skins = eligibleSkins(definition);
        List<Reward> rewards = new ArrayList<>();
        Map<String, Integer> stagedSkins = new HashMap<>();
        Map<String, Integer> stagedWeekly = new HashMap<>(), stagedMonthly = new HashMap<>();
        Map<String, Integer> stagedExtras = new HashMap<>();
        int guaranteed = "unified_pool".equals(pool.rewardMode) ? pool.minimumSkinCount : pool.skinDrawCount;
        if (guaranteed > 0 && skins.isEmpty()) return fail(canonicalCrate, canonicalKey, "crates.no_skins");
        for (int i = 0; i < guaranteed; i++) {
            WeightedSkin picked = choose(skins, player, pool, stagedSkins, stagedWeekly, stagedMonthly);
            if (picked == null) return fail(canonicalCrate, canonicalKey, "crates.quota_reached");
            addSkinReward(rewards, stagedSkins, stagedWeekly, stagedMonthly, picked.skin());
        }
        if ("unified_pool".equals(pool.rewardMode)) {
            for (int i = guaranteed; i < pool.rollCount; i++) {
                WeightedSkin picked = null;
                ExtraReward extra = null;
                long total = 0;
                List<WeightedSkin> available = availableSkins(skins, player, pool, stagedSkins, stagedWeekly, stagedMonthly);
                for (WeightedSkin skin : available) total += skin.weight();
                for (int j = 0; j < pool.extraRewards.size(); j++) {
                    ExtraReward reward = pool.extraRewards.get(j);
                    if (reward.weight > 0 && extraEligible(reward, stagedExtras.getOrDefault(String.valueOf(j), 0), current,
                            player.getUUID()))
                        total += reward.weight;
                }
                if (total <= 0) return fail(canonicalCrate, canonicalKey, "crates.no_rewards");
                long roll = ThreadLocalRandom.current().nextLong(total);
                for (WeightedSkin skin : available) {
                    roll -= skin.weight();
                    if (roll < 0) { picked = skin; break; }
                }
                if (picked != null) addSkinReward(rewards, stagedSkins, stagedWeekly, stagedMonthly, picked.skin());
                else for (int j = 0; j < pool.extraRewards.size(); j++) {
                    ExtraReward candidate = pool.extraRewards.get(j);
                    if (candidate.weight <= 0 || !extraEligible(candidate,
                            stagedExtras.getOrDefault(String.valueOf(j), 0), current, player.getUUID())) continue;
                    roll -= candidate.weight;
                    if (roll < 0) { extra = candidate; stagedExtras.merge(String.valueOf(j), 1, Integer::sum); break; }
                }
                if (extra != null) rewards.add(toReward(extra));
            }
        } else for (ExtraReward extra : pool.extraRewards) {
            if (extra.chance > 0 && ThreadLocalRandom.current().nextDouble() < extra.chance)
                rewards.add(toReward(extra));
        }
        if (rewards.isEmpty()) return fail(canonicalCrate, canonicalKey, "crates.no_rewards");
        Map<String, Integer> cardAmounts = new LinkedHashMap<>();
        int apples = 0;
        try {
            for (Reward reward : rewards) {
                if ("green_apples".equals(reward.kind())) apples = Math.addExact(apples, reward.amount());
                if ("card".equals(reward.kind())) cardAmounts.merge(reward.id(), reward.amount(), Math::addExact);
            }
            Math.addExact(current.greenApples, apples);
            if (!cardAmounts.isEmpty() && (PlayerCardAdminService.snapshotOnline(player).status() == CardStoreStatus.CORRUPT
                    || LocalBackpackStore.loadResult(player.getUUID()).corrupt()))
                return fail(canonicalCrate, canonicalKey, "crates.card_unavailable");
            for (Map.Entry<String, Integer> entry : cardAmounts.entrySet()) {
                var kind = com.habitrain.lottery.api.player.HabiCardKind.parse(entry.getKey());
                int count = com.habitrain.lottery.api.player.HabiCardApi.get(player.getUUID(), kind);
                if (entry.getValue() > com.habitrain.lottery.api.player.HabiCardApi.MAX_DELTA
                        || Math.addExact(count, entry.getValue()) > com.habitrain.lottery.api.player.HabiCardApi.MAX_COUNT)
                    return fail(canonicalCrate, canonicalKey, "crates.card_full");
            }
        } catch (ArithmeticException error) { return fail(canonicalCrate, canonicalKey, "crates.reward_overflow"); }
        Map<String, Integer> cardsBefore = new LinkedHashMap<>();
        for (String id : cardAmounts.keySet()) cardsBefore.put(id, com.habitrain.lottery.api.player.HabiCardApi.get(
                player.getUUID(), com.habitrain.lottery.api.player.HabiCardKind.parse(id)));
        if (state.pendingOpens.size() >= 1024) return fail(canonicalCrate, canonicalKey, "crates.pending");
        PendingOpen pending = new PendingOpen();
        pending.openId = openId;
        pending.playerId = player.getUUID().toString();
        pending.crateId = canonicalCrate; pending.keyId = canonicalKey;
        pending.weekKey = state.weekKey; pending.monthKey = state.monthKey;
        Reward primary = rewards.stream().filter(r -> "skin".equals(r.kind())).findFirst().orElse(rewards.get(0));
        pending.primaryQualityId = "skin".equals(primary.kind())
                ? HabiSkinApi.fromEntry(primary.id()).map(s -> s.quality().id()).orElse("white") : "white";
        pending.rewards = List.copyOf(rewards);
        pending.cardBefore = cardsBefore;
        pending.skinDelta = stagedSkins;
        pending.weeklyDelta = stagedWeekly;
        pending.monthlyDelta = stagedMonthly;
        pending.apples = apples;
        state.pendingOpens.put(openId, pending);
        if (!saveState()) {
            state.pendingOpens.remove(openId);
            return fail(canonicalCrate, canonicalKey, "crates.write_failed");
        }
        return finishPending(pending, player);
    }

    private static OpenResult finishPending(PendingOpen pending, ServerPlayer online) {
        if (pending == null || pending.openId == null || pending.rewards == null || pending.rewards.isEmpty())
            return fail("", "", "crates.invalid_request");
        UUID uuid;
        try { uuid = UUID.fromString(pending.playerId); }
        catch (RuntimeException error) { return fail(pending.crateId, pending.keyId, "crates.invalid_request"); }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        PlayerLotteryData current = store.getOrLoad(uuid);
        if (store.isLoadFailed(uuid)) return fail(pending.crateId, pending.keyId, "crates.pending");
        Map<String, Integer> amounts = new LinkedHashMap<>();
        try {
            for (Reward reward : pending.rewards) if ("card".equals(reward.kind()))
                amounts.merge(reward.id(), reward.amount(), Math::addExact);
            for (Map.Entry<String, Integer> entry : amounts.entrySet()) {
                var kind = com.habitrain.lottery.api.player.HabiCardKind.parse(entry.getKey());
                Integer before = pending.cardBefore.get(entry.getKey());
                if (kind == null || before == null) return fail(pending.crateId, pending.keyId, "crates.pending");
                int target = Math.addExact(before, entry.getValue());
                int live = com.habitrain.lottery.api.player.HabiCardApi.get(uuid, kind);
                if (live == target) continue;
                if (live != before || !com.habitrain.lottery.api.player.HabiCardApi.set(uuid, kind, target).ok()) {
                    rollbackCards(uuid, pending.cardBefore, amounts);
                    return fail(pending.crateId, pending.keyId, "crates.pending");
                }
            }
            if (!current.crateReceipts.containsKey(pending.openId)) {
                boolean saved = store.applyToPlayerWithRollback(uuid, data -> {
                    if (!SystemItemBalances.change(data.systemItems, crateItemId(pending.crateId), -1)
                            || !SystemItemBalances.change(data.systemItems, pending.keyId, -1))
                        throw new IllegalStateException("missing crate/key for pending open");
                    data.greenApples = Math.addExact(data.greenApples, pending.apples);
                    for (Reward reward : pending.rewards) if ("skin".equals(reward.kind())) {
                        String[] parts = reward.id().split("/", 2);
                        PlayerLotteryStore.awardSkin(data, parts[0], parts[1], reward.amount());
                    }
                    data.inventoryRevision = Math.addExact(data.inventoryRevision, 1);
                    pending.inventoryRevision = data.inventoryRevision;
                    data.crateOpenHistory.add(pending.openId);
                    data.crateReceipts.put(pending.openId, GSON.toJson(pending));
                    if (data.crateReceipts.size() > 512) {
                        String oldest = data.crateReceipts.keySet().iterator().next();
                        data.crateReceipts.remove(oldest);
                    }
                });
                if (!saved) {
                    rollbackCards(uuid, pending.cardBefore, amounts);
                    return fail(pending.crateId, pending.keyId, "crates.pending");
                }
            } else {
                PendingOpen receipt = GSON.fromJson(current.crateReceipts.get(pending.openId), PendingOpen.class);
                if (receipt != null) pending.inventoryRevision = receipt.inventoryRevision;
            }
            if (state.pendingOpens.containsKey(pending.openId)) {
                State snapshot = copyState(state);
                if (state.weekKey.equals(pending.weekKey)) pending.weeklyDelta.forEach((q, n) -> state.weeklyUsed.merge(q, n, Integer::sum));
                if (state.monthKey.equals(pending.monthKey)) pending.monthlyDelta.forEach((q, n) -> state.monthlyUsed.merge(q, n, Integer::sum));
                pending.skinDelta.forEach((skin, n) -> state.skinProduced.merge(skin, n, Math::addExact));
                state.pendingOpens.remove(pending.openId);
                if (!saveState()) { state = snapshot; return fail(pending.crateId, pending.keyId, "crates.pending"); }
            }
            if (online != null) {
                PlayerLotteryData committed = store.getOrLoad(uuid);
                for (Reward reward : pending.rewards) if ("skin".equals(reward.kind())) {
                    String[] parts = reward.id().split("/", 2);
                    com.habitrain.lottery.skin.SkinNetwork.syncAccess(online, committed, parts[0], parts[1], true);
                }
            }
            return resultFor(pending);
        } catch (RuntimeException error) {
            HabiLotteryMod.LOGGER.error("Unable to finish crate open {}", pending.openId, error);
            return fail(pending.crateId, pending.keyId, "crates.pending");
        }
    }

    private static OpenResult resultFor(PendingOpen pending) {
        Reward primary = pending.rewards.stream().filter(r -> "skin".equals(r.kind())).findFirst().orElse(pending.rewards.get(0));
        String[] parts = "skin".equals(primary.kind()) ? primary.id().split("/", 2) : new String[]{primary.kind(), primary.id()};
        SkinQuality quality = SkinQuality.fromId(pending.primaryQualityId);
        return new OpenResult(true, pending.crateId, pending.keyId, parts[0], parts[1], quality,
                "crates.opened", remaining(state.weeklyCaps, state.weeklyUsed, quality),
                remaining(state.monthlyCaps, state.monthlyUsed, quality), List.copyOf(pending.rewards),
                pending.inventoryRevision);
    }

    private static Reward toReward(ExtraReward reward) {
        return new Reward(reward.type, "card".equals(reward.type) ? reward.cardKind : "green_apples", reward.amount);
    }

    private static boolean extraEligible(ExtraReward reward, int hits, PlayerLotteryData player, UUID uuid) {
        if (hits >= reward.maxPerOpen) return false;
        try {
            int total = Math.multiplyExact(reward.amount, hits + 1);
            if ("green_apples".equals(reward.type)) {
                Math.addExact(player.greenApples, total);
                return true;
            }
            var kind = com.habitrain.lottery.api.player.HabiCardKind.parse(reward.cardKind);
            return kind != null && total <= com.habitrain.lottery.api.player.HabiCardApi.MAX_DELTA
                    && Math.addExact(com.habitrain.lottery.api.player.HabiCardApi.get(uuid, kind), total)
                    <= com.habitrain.lottery.api.player.HabiCardApi.MAX_COUNT;
        } catch (ArithmeticException error) { return false; }
    }

    private static void addSkinReward(List<Reward> rewards, Map<String, Integer> stagedSkins,
                                      Map<String, Integer> stagedWeekly, Map<String, Integer> stagedMonthly,
                                      SkinDefinition skin) {
        String key = skinKey(skin);
        rewards.add(new Reward("skin", key, 1));
        stagedSkins.merge(key, 1, Integer::sum);
        stagedWeekly.merge(skin.quality().id(), 1, Integer::sum);
        stagedMonthly.merge(skin.quality().id(), 1, Integer::sum);
    }

    private static void rollbackCards(UUID uuid, Map<String, Integer> before, Map<String, Integer> amounts) {
        before.forEach((id, count) -> {
            var kind = com.habitrain.lottery.api.player.HabiCardKind.parse(id);
            if (kind == null || com.habitrain.lottery.api.player.HabiCardApi.get(uuid, kind) != (long) count + amounts.getOrDefault(id, 0))
                return;
            if (!com.habitrain.lottery.api.player.HabiCardApi.set(uuid, kind, count).ok())
                HabiLotteryMod.LOGGER.error("Failed to restore card {} for {} after crate failure", id, uuid);
        });
    }

    private static OpenResult fail(String crateId, String keyId, String message) {
        return new OpenResult(false, crateId == null ? "" : crateId, keyId == null ? "" : keyId,
                "", "", SkinQuality.WHITE, message, 0, 0, List.of(), 0);
    }

    private static int remaining(Map<String, Integer> caps, Map<String, Integer> used, SkinQuality q) {
        return Math.max(0, caps.getOrDefault(q.id(), 0) - used.getOrDefault(q.id(), 0));
    }

    private record WeightedSkin(SkinDefinition skin, int weight) {}

    private static List<WeightedSkin> eligibleSkins(Definition definition) {
        CratePool pool = state.crates.get(definition.id());
        return eligibleSkins(definition, pool);
    }

    private static List<WeightedSkin> eligibleSkins(Definition definition, CratePool pool) {
        List<WeightedSkin> configured = new ArrayList<>();
        if (pool != null && pool.customPool) {
            if (pool.skinWeights == null) return configured;
            for (Map.Entry<String, Integer> entry : pool.skinWeights.entrySet()) {
                if (entry.getValue() == null || entry.getValue() <= 0) continue;
                HabiSkinApi.fromEntry(entry.getKey()).ifPresent(skin -> configured.add(new WeightedSkin(skin, entry.getValue())));
            }
            return configured;
        }
        List<SkinDefinition> all = HabiSkinApi.registrations();
        List<WeightedSkin> exact = new ArrayList<>();
        for (SkinDefinition skin : all) {
            if (definition.prism() || skin.quality() == definition.primary()) {
                exact.add(new WeightedSkin(skin, DEFAULT_SKIN_WEIGHT));
            }
        }
        return exact;
    }

    private static List<WeightedSkin> availableSkins(List<WeightedSkin> skins, ServerPlayer player, CratePool pool,
                                                     Map<String, Integer> stagedSkins, Map<String, Integer> stagedWeekly,
                                                     Map<String, Integer> stagedMonthly) {
        List<WeightedSkin> available = skins.stream().filter(s -> {
            String key = skinKey(s.skin());
            Integer cap = state.skinLifetimeCaps.get(key);
            int reservedSkin = state.pendingOpens.values().stream().mapToInt(p -> p.skinDelta.getOrDefault(key, 0)).sum();
            int reservedWeek = state.pendingOpens.values().stream().filter(p -> state.weekKey.equals(p.weekKey))
                    .mapToInt(p -> p.weeklyDelta.getOrDefault(s.skin().quality().id(), 0)).sum();
            int reservedMonth = state.pendingOpens.values().stream().filter(p -> state.monthKey.equals(p.monthKey))
                    .mapToInt(p -> p.monthlyDelta.getOrDefault(s.skin().quality().id(), 0)).sum();
            return (pool.allowSameSkinInOneOpen || stagedSkins.getOrDefault(key, 0) == 0)
                    && (long) state.skinProduced.getOrDefault(key, 0) + reservedSkin + stagedSkins.getOrDefault(key, 0) < Integer.MAX_VALUE
                    && (cap == null || (long) state.skinProduced.getOrDefault(key, 0) + reservedSkin + stagedSkins.getOrDefault(key, 0) < cap)
                    && remaining(state.weeklyCaps, state.weeklyUsed, s.skin().quality()) > reservedWeek + stagedWeekly.getOrDefault(s.skin().quality().id(), 0)
                    && remaining(state.monthlyCaps, state.monthlyUsed, s.skin().quality()) > reservedMonth + stagedMonthly.getOrDefault(s.skin().quality().id(), 0);
        }).toList();
        if (pool.duplicateProtection && player != null) {
            List<WeightedSkin> fresh = available.stream()
                    .filter(s -> !PlayerLotteryStore.get().isSkinUnlocked(player.getUUID(), s.skin().type(), s.skin().id()))
                    .toList();
            if (!fresh.isEmpty()) available = fresh;
        }
        return available;
    }

    private static WeightedSkin choose(List<WeightedSkin> skins, ServerPlayer player, CratePool pool,
                                       Map<String, Integer> stagedSkins, Map<String, Integer> stagedWeekly,
                                       Map<String, Integer> stagedMonthly) {
        List<WeightedSkin> available = availableSkins(skins, player, pool, stagedSkins, stagedWeekly, stagedMonthly);
        if (available.isEmpty()) return null;
        long total = available.stream().mapToLong(WeightedSkin::weight).sum();
        if (total <= 0) return null;
        long pick = ThreadLocalRandom.current().nextLong(total);
        for (WeightedSkin candidate : available) {
            pick -= candidate.weight();
            if (pick < 0) return candidate;
        }
        return available.get(available.size() - 1);
    }

    private static State copyState(State source) {
        State copy = new State();
        copy.revision = source.revision;
        copy.schemaVersion = source.schemaVersion;
        copy.weekKey = source.weekKey; copy.monthKey = source.monthKey;
        copy.weeklyCaps = new LinkedHashMap<>(source.weeklyCaps);
        copy.monthlyCaps = new LinkedHashMap<>(source.monthlyCaps);
        copy.weeklyUsed = new LinkedHashMap<>(source.weeklyUsed);
        copy.monthlyUsed = new LinkedHashMap<>(source.monthlyUsed);
        copy.pools = new LinkedHashMap<>();
        if (source.pools != null) {
            source.pools.forEach((id, pool) -> copy.pools.put(id, copyPool(pool)));
        }
        copy.crates = new LinkedHashMap<>();
        if (source.crates != null) source.crates.forEach((id, pool) -> copy.crates.put(id, copyPool(pool)));
        copy.skinLifetimeCaps = source.skinLifetimeCaps == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.skinLifetimeCaps);
        copy.skinProduced = source.skinProduced == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.skinProduced);
        copy.pendingOpens = source.pendingOpens == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.pendingOpens);
        return copy;
    }
}
