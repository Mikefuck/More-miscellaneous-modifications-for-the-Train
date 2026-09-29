package com.habitrain.lottery.daily.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.player.HabiTitleApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.daily.DailyTaskSnapshot;
import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.daily.config.DailyRewardService;
import com.habitrain.lottery.daily.config.DailyTaskConfig;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.mail.MailService;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Server authority for {@code config/daily_shop.json} and for purchases made on the daily terminal.
 *
 * <p>A purchase is one all-or-nothing transaction: the price is a negative green-apple line in the
 * same {@link MailService#grantTransactional} call that credits the goods, so a failed write can
 * never leave the player charged without goods (or the reverse).</p>
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
        long day = LoginRewardService.todayEpochDayUtc();
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
                    alreadyOwned(player, item)));
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
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (store.isLoadFailed(player.getUUID())) return "unavailable";
        DailyShopConfig shop = config;
        DailyShopItem item = shop.find(itemId);
        if (!shop.enabled || item == null || !item.enabled) return "gone";
        if (item.price != expectedPrice) return "price_changed";
        long day = LoginRewardService.todayEpochDayUtc();
        PlayerLotteryData data = store.getOrLoad(player);
        PlayerLotteryData.ShopCounter before = data.shopPurchases == null ? null : data.shopPurchases.get(item.id);
        if (DailyShopLimits.remaining(before, item, day) <= 0) return "sold_out";
        if (data.greenApples < item.price) return "insufficient";
        if (alreadyOwned(player, item)) return "owned";

        List<MailReward> goods = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (DailyRewardEntry reward : item.rewards) {
            if (DailyRewardEntry.TITLE.equals(reward.kind)) {
                titles.add(reward.id);
                continue;
            }
            MailReward converted = DailyRewardService.toMailReward(player, "shop_" + item.id, reward, day);
            if (converted == null) return "broken";
            goods.add(converted);
        }
        if (MailService.blockedDuringMatch(player, goods)) return "in_match";

        // Titles live in their own store: grant them first and take them back if the payment fails.
        List<String> granted = new ArrayList<>();
        for (String title : titles) {
            if (!HabiTitleApi.grant(player, title).ok()) {
                revoke(player, granted);
                return "failed";
            }
            granted.add(title);
        }
        // The counter is written before the payment so the green-apple flush persists both at once.
        boolean limited = item.limitEnabled;
        if (limited) setCounter(store, player, item.id, DailyShopLimits.plusOne(before, item, day));
        List<MailReward> transaction = new ArrayList<>();
        if (item.price > 0) transaction.add(MailReward.greenApples(-item.price));
        transaction.addAll(goods);
        if (!MailService.grantTransactional(player, transaction, TAG)) {
            revoke(player, granted);
            if (limited) {
                setCounter(store, player, item.id, before);
                store.flush(player.getUUID());
            }
            return "failed";
        }
        if (limited && !store.flush(player.getUUID())) {
            HabiLotteryMod.LOGGER.error("Shop purchase {} for {} paid out but the limit counter is not yet on disk",
                    item.id, player.getUUID());
        }
        player.sendSystemMessage(Component.literal("§a" + TAG + " 购买成功：" + item.title));
        for (String title : granted) player.sendSystemMessage(Component.literal("§a" + TAG + " 获得称号「" + title + "」"));
        HabiLotteryMod.LOGGER.info("{} bought shop item {} for {} green apples",
                player.getGameProfile().getName(), item.id, item.price);
        return "ok";
    }

    private static void setCounter(PlayerLotteryStore store, ServerPlayer player, String id,
                                   PlayerLotteryData.ShopCounter counter) {
        store.update(player, d -> {
            if (d.shopPurchases == null) d.shopPurchases = new java.util.HashMap<>();
            if (counter == null) d.shopPurchases.remove(id);
            else d.shopPurchases.put(id, counter);
        });
    }

    private static void revoke(ServerPlayer player, List<String> titles) {
        for (String title : titles) {
            if (!HabiTitleApi.revoke(player, title).ok()) {
                HabiLotteryMod.LOGGER.error("Could not take back shop title '{}' from {}", title, player.getUUID());
            }
        }
    }

    /**
     * True when every unique good (skin, title) of the item is owned already and the item has
     * nothing else to give — buying it again would only cost apples.
     */
    static boolean alreadyOwned(ServerPlayer player, DailyShopItem item) {
        boolean anyUnique = false;
        for (DailyRewardEntry reward : item.rewards) {
            if (!DailyRewardEntry.isUnique(reward.kind)) return false;
            anyUnique = true;
            boolean owned = switch (reward.kind) {
                case DailyRewardEntry.SKIN -> HabiSkinApi.fromEntry(reward.id)
                        .map(skin -> PlayerLotteryStore.get().isSkinUnlocked(player.getUUID(), skin.type(), skin.id()))
                        .orElse(false);
                case DailyRewardEntry.TITLE -> HabiTitleApi.owned(player.getUUID()).contains(reward.id);
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
