package com.habitrain.lottery.daily.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.api.player.HabiCardApi;
import com.habitrain.lottery.api.player.HabiTitleApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.card.ServerMatchGate;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.daily.DailyTaskSnapshot;
import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.daily.config.DailyRewardService;
import com.habitrain.lottery.daily.config.DailyTaskConfig;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.mail.RewardDelivery;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.time.LotteryDayClock;
import com.habitrain.lottery.title.LocalTitleStore;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Server authority for {@code config/daily_shop.json} and for purchases made on the daily terminal.
 *
 * <p>A purchase uses a crash-safe two-phase commit: first an unpaid {@link RewardDelivery} intent is
 * armed on disk, then a single atomic {@link PlayerLotteryStore} write deducts green apples, records
 * the limit counter, and authorizes the {@code shop:*} key in {@code paidShopPurchases}. Finally,
 * {@link RewardDelivery} delivers all goods and titles idempotently.</p>
 */
public final class DailyShopService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String FILE = "daily_shop.json";
    private static final String TAG = "[商店]";

    private static volatile DailyShopConfig config = DailyShopConfig.defaults();
    private static boolean writable;
    private static String lastError = "";

    private DailyShopService() { }

    /** Editor payload. */
    public record Snapshot(DailyShopConfig config, boolean writable) { }

    public static synchronized void onServerStarted() {
        config = DailyShopConfig.defaults();
        writable = false;
        if (!WorldLotteryPaths.ready()) return;
        var load = AtomicJsonFiles.readJson(WorldLotteryPaths.configFile(FILE), DailyShopConfig.class, GSON);
        if (load.corrupt()) {
            HabiLotteryMod.LOGGER.error("Daily shop config is corrupt; the shop stays closed until it is repaired");
            config.enabled = false;
            return;
        }
        if (load.ok() && load.value() != null) {
            DailyShopConfig loaded = load.value();
            try {
                // Lenient: a removed skin or crate fails that one purchase instead of closing the shop.
                loaded.normalize(DailyTaskConfig.Checks.LENIENT);
                config = loaded;
            } catch (IllegalArgumentException invalid) {
                HabiLotteryMod.LOGGER.error("Daily shop config is invalid ({}); shop closed, file left untouched",
                        invalid.getMessage());
                config.enabled = false;
                return;
            }
        } else {
            config.normalize(DailyTaskConfig.Checks.LENIENT);
            if (!save(config)) HabiLotteryMod.LOGGER.warn("Could not write default daily shop config");
        }
        writable = true;
    }

    public static synchronized void onServerStopping() {
        config = DailyShopConfig.defaults();
        writable = false;
    }

    static synchronized void setConfigForTest(DailyShopConfig next) {
        config = next == null ? DailyShopConfig.defaults() : next;
        writable = true;
    }

    public static synchronized String lastError() {
        return lastError;
    }

    public static synchronized String snapshotJson() {
        return GSON.toJson(new Snapshot(config.copy(), writable));
    }

    /** Applies an editor save; see {@code DailyTaskConfigService.apply} for the same contract. */
    public static synchronized boolean apply(String json) {
        lastError = "";
        if (!writable) {
            lastError = "not_writable";
            return false;
        }
        DailyShopConfig proposed;
        try {
            proposed = GSON.fromJson(json == null ? "" : json, DailyShopConfig.class);
        } catch (RuntimeException malformed) {
            lastError = "malformed";
            return false;
        }
        if (proposed == null || proposed.schemaVersion != DailyShopConfig.SCHEMA_VERSION) {
            lastError = "outdated";
            return false;
        }
        if (proposed.revision != config.revision) {
            lastError = "conflict";
            return false;
        }
        try {
            proposed.normalize(strictChecks());
        } catch (IllegalArgumentException invalid) {
            lastError = invalid.getMessage();
            return false;
        }
        proposed.revision = config.revision + 1;
        if (!save(proposed)) {
            lastError = "write_failed";
            return false;
        }
        config = proposed;
        return true;
    }

    // ------------------------------------------------------------------ board view

    public static boolean open() {
        return config.enabled;
    }

    /** Enabled listings with this player's limit state, in configured order. */
    public static List<DailyTaskSnapshot.ShopRow> rows(ServerPlayer player) {
        DailyShopConfig shop = config;
        if (player == null || !shop.enabled) return List.of();
        long day = LotteryDayClock.todayEpochDay();
        PlayerLotteryData data = PlayerLotteryStore.get().getOrLoad(player);
        List<DailyTaskSnapshot.ShopRow> out = new ArrayList<>();
        for (DailyShopItem item : shop.items) {
            if (!item.enabled) continue;
            PlayerLotteryData.ShopCounter counter = data.shopPurchases == null ? null : data.shopPurchases.get(item.id);
            out.add(new DailyTaskSnapshot.ShopRow(item.id, item.title, item.description, item.rewardLabel,
                    item.rewards.isEmpty() ? "" : item.rewards.getFirst().kind, item.price,
                    item.limitEnabled, item.limitCount, item.limitDays,
                    item.limitEnabled ? DailyShopLimits.used(counter, item, day) : 0,
                    item.limitEnabled ? DailyShopLimits.nextResetDay(day, item.limitDays) : -1L,
                    alreadyOwned(player, item), matchLocked(player, item, day)));
        }
        return out;
    }

    // ------------------------------------------------------------------ purchase

    /**
     * Buys one unit of {@code itemId}. {@code expectedPrice} is the price the player saw; an OP
     * repricing in between refuses the purchase instead of charging an amount nobody confirmed.
     *
     * @return {@code "ok"} or a failure code ({@code screen.habitrain_lottery.daily.shop.fail.*})
     */
    public static String buy(ServerPlayer player, String itemId, int expectedPrice) {
        if (player == null || !WorldLotteryPaths.ready()) return "unavailable";
        if (PlayerStateGate.spectatorRestOrDead(player)) return "in_match";
        return buyForAccount(player.getUUID(), player, itemId, expectedPrice);
    }

    static String buyForAccount(UUID uuid, ServerPlayer online, String itemId, int expectedPrice) {
        if (uuid == null || !WorldLotteryPaths.ready()) return "unavailable";
        if (online != null && PlayerStateGate.spectatorRestOrDead(online)) return "in_match";
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (store.isFlushDeferred() || store.isLoadFailed(uuid)) return "unavailable";

        DailyShopConfig shop = config;
        DailyShopItem item = shop.find(itemId);
        if (!shop.enabled || item == null || !item.enabled) return "gone";
        if (item.price != expectedPrice) return "price_changed";
        if (item.price < 0) return "broken";

        long day = LotteryDayClock.todayEpochDay();
        PlayerLotteryData data = store.getOrLoad(uuid);
        if (store.isLoadFailed(uuid)) return "unavailable";
        PlayerLotteryData.ShopCounter before = data.shopPurchases == null ? null : data.shopPurchases.get(item.id);
        if (DailyShopLimits.remaining(before, item, day) <= 0) return "sold_out";
        if (data.greenApples < item.price) return "insufficient";
        if (alreadyOwned(uuid, item)) return "owned";
        if (data.paidShopPurchases != null
                && data.paidShopPurchases.size() >= PlayerLotteryData.PAID_SHOP_ORDERS_CAP) {
            return "unavailable";
        }

        List<MailReward> goods = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (DailyRewardEntry reward : item.rewards) {
            if (reward == null) return "broken";
            if (DailyRewardEntry.TITLE.equals(reward.kind)) {
                if (reward.id == null || reward.id.isBlank()) return "broken";
                titles.add(reward.id);
                continue;
            }
            MailReward converted = purchaseReward(online, item.id, reward, day, ThreadLocalRandom.current());
            if (converted == null) return "broken";
            goods.add(converted);
        }

        boolean hasCards = hasCardRewards(goods);
        boolean activeMatch = online != null
                ? ServerMatchGate.hasActiveMatch(online)
                : ServerMatchGate.hasActiveMatch((ServerPlayer) null);
        if (hasCards && activeMatch) {
            return "in_match";
        }

        if (!titles.isEmpty()) {
            LocalTitleStore titleStore = LocalTitleStore.get();
            titleStore.loadCatalog();
            if (titleStore.isCatalogCorrupt() || titleStore.loadPlayerDetailed(uuid).corrupt()) {
                return "unavailable";
            }
        }

        if (hasCards) {
            if (online != null && !LocalBackpackStore.prepareOnline(online)) {
                return "unavailable";
            }
            if (!validateCardBounds(uuid, goods)) {
                return "failed";
            }
        }

        var ownedBefore = new HashSet<>(HabiTitleApi.owned(uuid));
        List<String> newlyGrantedTitles = titles.stream()
                .filter(t -> !ownedBefore.contains(t))
                .distinct()
                .toList();

        String purchaseKey = "shop:" + UUID.randomUUID();
        RewardDelivery.Plan plan = new RewardDelivery.Plan(goods, titles, null);
        if (!RewardDelivery.armShopPlan(uuid, purchaseKey, plan)) {
            return "failed";
        }

        String[] debitFailure = {null};
        boolean debited;
        try {
            debited = store.applyToPlayerWithRollback(uuid, d -> {
                PlayerLotteryData.ShopCounter cur = d.shopPurchases == null ? null : d.shopPurchases.get(item.id);
                if (DailyShopLimits.remaining(cur, item, day) <= 0) {
                    debitFailure[0] = "sold_out";
                    throw new IllegalStateException("Shop item sold out");
                }
                int nextApples = Math.subtractExact(d.greenApples, item.price);
                if (nextApples < 0) {
                    debitFailure[0] = "insufficient";
                    throw new IllegalStateException("Insufficient green apples");
                }
                if (d.paidShopPurchases != null
                        && d.paidShopPurchases.size() >= PlayerLotteryData.PAID_SHOP_ORDERS_CAP) {
                    debitFailure[0] = "unavailable";
                    throw new IllegalStateException("Too many pending paid shop orders");
                }
                d.greenApples = nextApples;
                if (item.limitEnabled) {
                    if (d.shopPurchases == null) d.shopPurchases = new HashMap<>();
                    d.shopPurchases.put(item.id, DailyShopLimits.plusOne(cur, item, day));
                }
                if (d.paidShopPurchases == null) {
                    d.paidShopPurchases = new PlayerLotteryData.BoundedStringSet(PlayerLotteryData.PAID_SHOP_ORDERS_CAP);
                }
                d.paidShopPurchases.add(purchaseKey);
            });
        } catch (RuntimeException rollbackReason) {
            debited = false;
        }
        if (!debited) {
            RewardDelivery.cleanupCompleted(uuid, purchaseKey);
            return debitFailure[0] != null ? debitFailure[0] : "failed";
        }

        boolean delivered = RewardDelivery.deliver(uuid, purchaseKey, () -> null);
        if (online != null) {
            RewardDelivery.syncCommitted(online);
        }
        if (!delivered) {
            if (online != null) {
                online.sendSystemMessage(Component.translatable(
                        "message.habitrain_lottery.shop.partial_delivery", item.title));
            }
            HabiLotteryMod.LOGGER.warn(
                    "Shop purchase {} ({}) for {} debited; remaining goods stay queued for recovery",
                    item.id, purchaseKey, uuid);
            return "ok";
        }

        if (online != null) {
            online.sendSystemMessage(Component.translatable(
                    "message.habitrain_lottery.shop.bought", item.title));
            for (String title : newlyGrantedTitles) {
                online.sendSystemMessage(Component.translatable(
                        "message.habitrain_lottery.shop.granted_title", title));
            }
            HabiLotteryMod.LOGGER.info("{} bought shop item {} for {} green apples",
                    online.getGameProfile().getName(), item.id, item.price);
        } else {
            HabiLotteryMod.LOGGER.info("{} bought shop item {} for {} green apples",
                    uuid, item.id, item.price);
        }
        return "ok";
    }

    private static boolean hasCardRewards(List<MailReward> goods) {
        if (goods == null) return false;
        for (MailReward r : goods) {
            if (r == null) continue;
            if ((r.kind() == MailReward.Kind.FACTION_CARD
                    || r.kind() == MailReward.Kind.SELF_SELECT_CARD
                    || r.kind() == MailReward.Kind.LIMIT_BREAK_CARD) && r.amount() != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean validateCardBounds(UUID uuid, List<MailReward> goods) {
        var backpack = LocalBackpackStore.loadResult(uuid);
        if (backpack.corrupt()) return false;
        Map<String, Integer> tempCards = backpack.ok()
                ? new HashMap<>(backpack.cards())
                : LocalBackpackStore.defaultCards();
        int selfSelect = backpack.selfSelectCards();
        int limitBreak = backpack.limitBreakCards();
        try {
            for (MailReward r : goods) {
                if (r == null || r.amount() == 0) continue;
                switch (r.kind()) {
                    case FACTION_CARD -> {
                        FactionCardType type = FactionCardType.fromString(r.factionType());
                        if (type == FactionCardType.NONE) return false;
                        String key = type.questKey.toLowerCase(Locale.ROOT);
                        int next = Math.addExact(tempCards.getOrDefault(key, 0), r.amount());
                        if (next < 0 || next > HabiCardApi.MAX_BALANCE) return false;
                        tempCards.put(key, next);
                    }
                    case SELF_SELECT_CARD -> {
                        selfSelect = Math.addExact(selfSelect, r.amount());
                        if (selfSelect < 0 || selfSelect > HabiCardApi.MAX_BALANCE) return false;
                    }
                    case LIMIT_BREAK_CARD -> {
                        limitBreak = Math.addExact(limitBreak, r.amount());
                        if (limitBreak < 0 || limitBreak > HabiCardApi.MAX_BALANCE) return false;
                    }
                    default -> {
                    }
                }
            }
            return true;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    /** Draw anew for each purchase, without changing the configured reward or daily-task draws. */
    static MailReward purchaseReward(ServerPlayer player, String itemId, DailyRewardEntry reward,
                                     long day, RandomGenerator random) {
        if ((DailyRewardEntry.CRATE.equals(reward.kind) || DailyRewardEntry.KEY.equals(reward.kind))
                && DailyRewardEntry.RANDOM.equals(reward.id)) {
            List<CrateCatalog.Entry> published = CrateCatalog.publishedEntries();
            List<CrateCatalog.Entry> candidates = published.stream().filter(CrateCatalog.Entry::enabled).toList();
            if (candidates.isEmpty()) candidates = published;
            if (candidates.isEmpty()) return null;
            String crateId = candidates.get(random.nextInt(candidates.size())).id();
            reward = new DailyRewardEntry(reward.kind, crateId, reward.amount);
        }
        return DailyRewardService.toMailReward(player, "shop_" + itemId, reward, day);
    }

    /**
     * True when the item gives cards and a match is running (or player is dead/spectating), i.e.
     * {@link #buy} would answer {@code in_match}. Only card kinds are converted, so a random crate
     * is never drawn here.
     */
    static boolean matchLocked(ServerPlayer player, DailyShopItem item, long day) {
        if (player != null && PlayerStateGate.spectatorRestOrDead(player)) {
            return true;
        }
        List<MailReward> cards = new ArrayList<>();
        for (DailyRewardEntry reward : item.rewards) {
            if (!DailyRewardEntry.CARD.equals(reward.kind) && !DailyRewardEntry.SELF_SELECT.equals(reward.kind)
                    && !DailyRewardEntry.LIMIT_BREAK.equals(reward.kind)) continue;
            MailReward converted = DailyRewardService.toMailReward(player, "shop_" + item.id, reward, day);
            if (converted != null) cards.add(converted);
        }
        return !cards.isEmpty() && ServerMatchGate.hasActiveMatch(player);
    }

    /**
     * True when every unique good (skin, title) of the item is owned already and the item has
     * nothing else to give — buying it again would only cost apples.
     */
    static boolean alreadyOwned(ServerPlayer player, DailyShopItem item) {
        return player != null && alreadyOwned(player.getUUID(), item);
    }

    static boolean alreadyOwned(UUID uuid, DailyShopItem item) {
        if (uuid == null || item == null) return false;
        boolean anyUnique = false;
        for (DailyRewardEntry reward : item.rewards) {
            if (!DailyRewardEntry.isUnique(reward.kind)) return false;
            anyUnique = true;
            boolean owned = switch (reward.kind) {
                case DailyRewardEntry.SKIN -> HabiSkinApi.fromEntry(reward.id)
                        .map(skin -> PlayerLotteryStore.get().isSkinUnlocked(uuid, skin.type(), skin.id()))
                        .orElse(false);
                case DailyRewardEntry.TITLE -> HabiTitleApi.owned(uuid).contains(reward.id);
                default -> false;
            };
            if (!owned) return false;
        }
        return anyUnique;
    }

    private static DailyTaskConfig.Checks strictChecks() {
        return new DailyTaskConfig.Checks() {
            public boolean skinExists(String entry) { return HabiSkinApi.fromEntry(entry).isPresent(); }
            public boolean crateExists(String crateId) {
                CrateCatalog.Entry entry = CrateCatalog.find(crateId);
                return entry != null && !entry.archived();
            }
        };
    }

    private static boolean save(DailyShopConfig value) {
        return WorldLotteryPaths.ready()
                && AtomicJsonFiles.writeJson(WorldLotteryPaths.configFile(FILE), value, GSON, true);
    }
}
