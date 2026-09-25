package com.habitrain.lottery.crate;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.player.HabiSystemItemApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinQuality;
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
                             int weeklyRemaining, int monthlyRemaining) {}

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
    }

    public static final class State {
        public int schemaVersion = 2;
        public String weekKey = "";
        public String monthKey = "";
        public Map<String, Integer> weeklyCaps = new LinkedHashMap<>();
        public Map<String, Integer> monthlyCaps = new LinkedHashMap<>();
        public Map<String, Integer> weeklyUsed = new LinkedHashMap<>();
        public Map<String, Integer> monthlyUsed = new LinkedHashMap<>();
        public Map<String, CratePool> pools = new LinkedHashMap<>();
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
        state = new State();
        if (WorldLotteryPaths.ready()) {
            var load = AtomicJsonFiles.readJson(WorldLotteryPaths.configFile(STATE_FILE), State.class, GSON);
            if (load.ok() && load.value() != null) state = load.value();
        }
        normalizeState();
        ready = true;
        saveState();
    }

    public static synchronized void onServerStopping() {
        if (ready) saveState();
        ready = false;
        state = new State();
    }

    private static void normalizeState() {
        if (state == null) state = new State();
        state.schemaVersion = Math.max(2, state.schemaVersion);
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
        state.pools.keySet().removeIf(id -> !DEFINITIONS.containsKey(id));
        for (Definition definition : DEFINITIONS.values()) {
            CratePool pool = state.pools.computeIfAbsent(definition.id(), ignored -> new CratePool());
            if (pool.skinWeights == null) pool.skinWeights = new LinkedHashMap<>();
            normalizePool(definition, pool);
            if (pool.customPool) {
                try {
                    validatePool(definition, pool);
                } catch (RuntimeException error) {
                    HabiLotteryMod.LOGGER.warn("Invalid crate pool {}; disabling custom pool", definition.id(), error);
                    pool.customPool = false;
                    pool.enabled = false;
                    normalizePool(definition, pool);
                }
            }
        }
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
        // Keep disabled/missing entries for round-tripping, but expose newly installed
        // skins in the editor with zero weight until an OP enables them.
        for (SkinDefinition skin : HabiSkinApi.registrations()) {
            pool.skinWeights.putIfAbsent(skinKey(skin), 0);
        }
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

    public static synchronized boolean applyConfigJson(String json) {
        State previous = copyState(state);
        try {
            State proposed = GSON.fromJson(json == null ? "" : json, State.class);
            if (proposed == null) return false;
            normalizeMap(proposed.weeklyCaps);
            normalizeMap(proposed.monthlyCaps);
            Map<String, CratePool> nextPools = new LinkedHashMap<>();
            if (proposed.pools != null) {
                for (String id : proposed.pools.keySet()) {
                    if (CrateCatalog.find(id) == null) throw new IllegalArgumentException("unknown crate");
                }
            }
            for (Definition definition : DEFINITIONS.values()) {
                CratePool incoming = proposed.pools == null ? null : proposed.pools.get(definition.id());
                if (incoming == null) {
                    CratePool current = state.pools == null ? null : state.pools.get(definition.id());
                    incoming = current == null ? new CratePool() : copyPool(current);
                } else {
                    validatePool(definition, incoming);
                    incoming = copyPool(incoming);
                }
                nextPools.put(definition.id(), incoming);
            }
            state.weeklyCaps = proposed.weeklyCaps;
            state.monthlyCaps = proposed.monthlyCaps;
            state.pools = nextPools;
            state.schemaVersion = 2;
            normalizeState();
            if (saveState()) return true;
            state = previous;
            return false;
        } catch (RuntimeException error) {
            state = previous;
            HabiLotteryMod.LOGGER.warn("Invalid crate quota config", error);
            return false;
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
            if (HabiSkinApi.fromEntry(key).isEmpty()) {
                // Providers may be removed after a config was saved. Keep the entry in
                // storage, but never allow a typo to become an active drop.
                normalized.put(key, 0);
                continue;
            }
            if (normalized.put(key, rawWeight) != null) throw new IllegalArgumentException("duplicate skin entry");
            total += rawWeight;
            if (total > MAX_TOTAL_WEIGHT) throw new IllegalArgumentException("skin weights too large");
        }
        if (pool.customPool && pool.enabled && normalized.values().stream().mapToLong(Integer::longValue).sum() <= 0) {
            throw new IllegalArgumentException("enabled crate has no weighted skins");
        }
        pool.skinWeights = normalized;
    }

    private static String normalizeSkinKey(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("blank skin entry");
        String[] parts = raw.trim().toLowerCase(Locale.ROOT).split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("skin entry must be type/id");
        }
        return HabiSkinApi.find(parts[0], parts[1]).map(s -> s.type() + "/" + s.id()).orElse(raw.trim().toLowerCase(Locale.ROOT));
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
        return copy;
    }

    public static synchronized Map<String, Integer> inventory(java.util.UUID uuid) {
        if (!ready || uuid == null) return Map.of();
        PlayerLotteryData data = PlayerLotteryStore.get().getOrLoad(uuid);
        return data.systemItems == null ? Map.of() : Map.copyOf(data.systemItems);
    }

    public static synchronized OpenResult open(ServerPlayer player, String crateId, String keyId) {
        CrateCatalog.Entry catalogEntry = CrateCatalog.find(crateId);
        String canonicalCrate = catalogEntry == null ? "" : catalogEntry.id();
        String canonicalKey = keyId == null ? "" : keyId.trim().toLowerCase(Locale.ROOT);
        if (!ready || player == null) return fail(canonicalCrate, canonicalKey, "crates.not_ready");
        Definition definition = DEFINITIONS.get(canonicalCrate);
        if (definition == null || !keyItemId(canonicalCrate).equals(canonicalKey)) {
            return fail(canonicalCrate, canonicalKey, "crates.wrong_key");
        }
        normalizeState();
        CratePool pool = state.pools.get(canonicalCrate);
        if (pool != null && !pool.enabled) return fail(canonicalCrate, canonicalKey, "crates.disabled");
        PlayerLotteryStore store = PlayerLotteryStore.get();
        PlayerLotteryData current = store.getOrLoad(player.getUUID());
        int crateCount = current.systemItems.getOrDefault(crateItemId(canonicalCrate), 0);
        int keyCount = current.systemItems.getOrDefault(canonicalKey, 0);
        if (crateCount <= 0 || keyCount <= 0) return fail(canonicalCrate, canonicalKey, "crates.missing_items");
        List<WeightedSkin> skins = eligibleSkins(definition);
        if (skins.isEmpty()) return fail(canonicalCrate, canonicalKey, "crates.no_skins");
        WeightedSkin weighted = choose(skins, player, pool != null && pool.duplicateProtection);
        if (weighted == null) return fail(canonicalCrate, canonicalKey, "crates.quota_reached");
        SkinDefinition selected = weighted.skin();
        SkinQuality quality = selected.quality();
        int weeklyRemaining = remaining(state.weeklyCaps, state.weeklyUsed, quality);
        int monthlyRemaining = remaining(state.monthlyCaps, state.monthlyUsed, quality);
        if (weeklyRemaining <= 0 || monthlyRemaining <= 0) {
            return fail(canonicalCrate, canonicalKey, "crates.quota_reached");
        }
        State snapshot = copyState(state);
        state.weeklyUsed.merge(quality.id(), 1, Integer::sum);
        state.monthlyUsed.merge(quality.id(), 1, Integer::sum);
        if (!saveState()) {
            state = snapshot;
            return fail(canonicalCrate, canonicalKey, "crates.write_failed");
        }
        boolean saved = store.applyToPlayerWithRollback(player.getUUID(), data -> {
            if (!SystemItemBalances.change(data.systemItems, crateItemId(canonicalCrate), -1)
                    || !SystemItemBalances.change(data.systemItems, canonicalKey, -1)) {
                throw new IllegalStateException("missing crate/key");
            }
            String skinId = selected.id();
            for (String writeKey : SkinTypeKeys.writeKeys(selected.type())) {
                data.unlocked.computeIfAbsent(writeKey, ignored -> new LinkedHashMap<>()).put(skinId, true);
            }
            data.unlocked.computeIfAbsent(SkinTypeKeys.canonical(selected.type()), ignored -> new LinkedHashMap<>())
                    .put(skinId, true);
        });
        if (!saved) {
            state = snapshot;
            saveState();
            return fail(canonicalCrate, canonicalKey, "crates.write_failed");
        }
        return new OpenResult(true, canonicalCrate, canonicalKey, selected.type(), selected.id(), quality,
                "crates.opened", remaining(state.weeklyCaps, state.weeklyUsed, quality),
                remaining(state.monthlyCaps, state.monthlyUsed, quality));
    }

    private static OpenResult fail(String crateId, String keyId, String message) {
        return new OpenResult(false, crateId == null ? "" : crateId, keyId == null ? "" : keyId,
                "", "", SkinQuality.WHITE, message, 0, 0);
    }

    private static int remaining(Map<String, Integer> caps, Map<String, Integer> used, SkinQuality q) {
        return Math.max(0, caps.getOrDefault(q.id(), 0) - used.getOrDefault(q.id(), 0));
    }

    private record WeightedSkin(SkinDefinition skin, int weight) {}

    private static List<WeightedSkin> eligibleSkins(Definition definition) {
        CratePool pool = state.pools.get(definition.id());
        List<WeightedSkin> configured = new ArrayList<>();
        if (pool != null && pool.customPool) {
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
        if (!exact.isEmpty()) return exact;
        List<WeightedSkin> fallback = new ArrayList<>();
        for (SkinDefinition skin : all) {
            if (remaining(state.weeklyCaps, state.weeklyUsed, skin.quality()) > 0
                    && remaining(state.monthlyCaps, state.monthlyUsed, skin.quality()) > 0) {
                fallback.add(new WeightedSkin(skin, DEFAULT_SKIN_WEIGHT));
            }
        }
        return fallback;
    }

    private static WeightedSkin choose(List<WeightedSkin> skins, ServerPlayer player, boolean protectDuplicates) {
        List<WeightedSkin> available = skins.stream().filter(s ->
                remaining(state.weeklyCaps, state.weeklyUsed, s.skin().quality()) > 0
                        && remaining(state.monthlyCaps, state.monthlyUsed, s.skin().quality()) > 0).toList();
        if (available.isEmpty()) return null;
        if (protectDuplicates && player != null) {
            List<WeightedSkin> fresh = available.stream()
                    .filter(s -> !PlayerLotteryStore.get().isSkinUnlocked(player.getUUID(), s.skin().type(), s.skin().id()))
                    .toList();
            if (!fresh.isEmpty()) available = fresh;
        }
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
        copy.weekKey = source.weekKey; copy.monthKey = source.monthKey;
        copy.weeklyCaps = new LinkedHashMap<>(source.weeklyCaps);
        copy.monthlyCaps = new LinkedHashMap<>(source.monthlyCaps);
        copy.weeklyUsed = new LinkedHashMap<>(source.weeklyUsed);
        copy.monthlyUsed = new LinkedHashMap<>(source.monthlyUsed);
        copy.pools = new LinkedHashMap<>();
        if (source.pools != null) {
            source.pools.forEach((id, pool) -> copy.pools.put(id, copyPool(pool)));
        }
        return copy;
    }
}
