package com.habitrain.lottery.api.player;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.bridge.EconomyMirror;
import com.habitrain.lottery.storage.MigrationService;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public player-asset API of 哈比列车抽奖补齐 (habitrain_lottery).
 *
 * <p>This facade covers the numeric per-player economy: 抽数 ({@code draws}),
 * 金币 ({@code coins}), grant de-duplication, whole-roster bulk updates and the
 * combined {@link HabiPlayerAssets} snapshot. Card balances live in
 * {@link HabiCardApi}, per-player skins in {@link HabiSkinPlayerApi}, titles in
 * {@link HabiTitleApi} and mail delivery in {@link HabiMailApi}.</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Server thread only.</b> Every method must be called from the server
 *       thread (for example inside a tick / command / player-join callback).
 *       Reads are safe but mutations are not thread safe with the tick loop.</li>
 *   <li><b>Readiness.</b> Mutations require {@link #isReady()} and fail with
 *       {@link HabiFailure#NOT_READY} otherwise. Reads degrade to defaults.</li>
 *   <li><b>Durability.</b> A successful {@link HabiAssetResult} means the
 *       authoritative world JSON was written. Failed writes roll the in-memory
 *       value back before returning.</li>
 *   <li><b>Online mirror.</b> If the target is online, SRE's live economy is
 *       refreshed automatically after a successful change.</li>
 * </ul>
 */
public final class HabiLotteryApi {
    /** Version of this API surface; bumped when a method changes semantics. */
    public static final int API_VERSION = 1;
    /** Mod id that provides this API. */
    public static final String MOD_ID = "habitrain_lottery";
    /** Fabric {@code provides} token for dependency declarations. */
    public static final String PROVIDES = "habitrain_lottery_player_api";

    private static final String DEFAULT_GRANT_REASON = "habitrain_api:manual";

    private HabiLotteryApi() {
    }

    /** True once the world lottery root exists (the server finished starting). */
    public static boolean isReady() {
        return WorldLotteryPaths.ready();
    }

    /** True when this mod has taken over the SRE economy for the current world. */
    public static boolean isEconomyActive() {
        return PlayerLotteryStore.get().isTakeoverActive();
    }

    /** Current server, or {@code null} when not running. */
    public static MinecraftServer server() {
        return HabiLotteryMod.getServer();
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** 抽数（抽奖次数）of one player. */
    public static int getDraws(UUID uuid) {
        return uuid == null ? 0 : PlayerLotteryStore.get().getLootChance(uuid);
    }

    public static int getDraws(ServerPlayer player) {
        return player == null ? 0 : getDraws(player.getUUID());
    }

    /** 金币 of one player. */
    public static int getCoins(UUID uuid) {
        return uuid == null ? 0 : PlayerLotteryStore.get().getCoinNum(uuid);
    }

    public static int getCoins(ServerPlayer player) {
        return player == null ? 0 : getCoins(player.getUUID());
    }

    // ------------------------------------------------------------------
    // Draws
    // ------------------------------------------------------------------

    /** Sets 抽数 to {@code amount} (clamped to {@code >= 0}). */
    public static HabiAssetResult setDraws(UUID uuid, int amount) {
        return mutateCurrency(uuid, HabiAssetOperation.SET, amount, false);
    }

    /** Adds {@code delta} to 抽数 (result clamped to {@code >= 0}). */
    public static HabiAssetResult addDraws(UUID uuid, int delta) {
        return mutateCurrency(uuid, HabiAssetOperation.ADD, delta, false);
    }

    public static HabiAssetResult setDraws(ServerPlayer player, int amount) {
        return setDraws(player == null ? null : player.getUUID(), amount);
    }

    public static HabiAssetResult addDraws(ServerPlayer player, int delta) {
        return addDraws(player == null ? null : player.getUUID(), delta);
    }

    /**
     * De-duplicable draw grant.
     *
     * @param reason grant key; identical keys are only consumed once when
     *               {@code dedupe} is true. A {@code null}/blank reason falls
     *               back to {@value #DEFAULT_GRANT_REASON}.
     * @param dedupe when true a repeated {@code reason} returns
     *               {@link HabiFailure#DUPLICATE_GRANT} without changing the balance
     */
    public static HabiAssetResult grantDraws(UUID uuid, int amount, String reason, boolean dedupe) {
        if (uuid == null) {
            return HabiAssetResult.fail(HabiFailure.NOT_FOUND);
        }
        if (!WorldLotteryPaths.ready()) {
            return HabiAssetResult.fail(HabiFailure.NOT_READY);
        }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (store.isLoadFailed(uuid)) {
            return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        }
        PlayerLotteryData before = store.getOrLoad(uuid).copy();
        if (store.isLoadFailed(uuid)) {
            return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        }
        if (amount == 0) {
            return HabiAssetResult.success(before.lootChance);
        }
        boolean wasDirty = store.isDirty(uuid);
        String key = reason == null || reason.isBlank() ? DEFAULT_GRANT_REASON : reason;
        if (dedupe && !store.tryConsumeGrantKey(uuid, key)) {
            return HabiAssetResult.fail(HabiFailure.DUPLICATE_GRANT, before.lootChance);
        }
        store.update(uuid, data -> data.lootChance =
                HabiAssetPolicy.applyCurrency(data.lootChance, HabiAssetOperation.ADD, amount));
        if (!store.flush(uuid)) {
            store.restoreSnapshot(uuid, before, wasDirty);
            return HabiAssetResult.fail(HabiFailure.WRITE_FAILED, before.lootChance);
        }
        ServerPlayer online = online(uuid);
        if (online != null) {
            EconomyMirror.syncChanceAndCoins(online, store.getOrLoad(uuid));
        }
        HabiLotteryMod.LOGGER.debug("API granted {} draws to {} reason={} dedupe={}",
                amount, uuid, key, dedupe);
        return HabiAssetResult.success(store.getLootChance(uuid));
    }

    // ------------------------------------------------------------------
    // Coins
    // ------------------------------------------------------------------

    /** Sets 金币 to {@code amount} (clamped to {@code >= 0}). */
    public static HabiAssetResult setCoins(UUID uuid, int amount) {
        return mutateCurrency(uuid, HabiAssetOperation.SET, amount, true);
    }

    /** Adds {@code delta} to 金币 (result clamped to {@code >= 0}). */
    public static HabiAssetResult addCoins(UUID uuid, int delta) {
        return mutateCurrency(uuid, HabiAssetOperation.ADD, delta, true);
    }

    public static HabiAssetResult setCoins(ServerPlayer player, int amount) {
        return setCoins(player == null ? null : player.getUUID(), amount);
    }

    public static HabiAssetResult addCoins(ServerPlayer player, int delta) {
        return addCoins(player == null ? null : player.getUUID(), delta);
    }

    // ------------------------------------------------------------------
    // Bulk (online roster)
    // ------------------------------------------------------------------

    /** Adds {@code delta} 金币 to every online player; returns the number touched. */
    public static int addCoinsToOnline(int delta) {
        MinecraftServer server = HabiLotteryMod.getServer();
        return server == null ? 0 : PlayerLotteryStore.get().addCoinsToOnline(server, delta);
    }

    /**
     * Sets every online player's 金币 to {@code amount}.
     *
     * <p><b>审核 B-20</b>：本方法过去自己循环 {@code setCoinNum + flush} 并丢弃 flush 返回值，
     * 然后把循环次数当成成功人数返回——磁盘满 / 世界只读时会虚报成功人数。
     * 现在委派给 {@link PlayerLotteryStore#setCoinsToOnline}（逐人快照 + 失败回滚），
     * <b>返回值是真正写入成功的人数</b>。
     *
     * @return 真正写入成功的玩家数（失败的玩家已回滚，未被计入）
     */
    public static int setCoinsToOnline(int amount) {
        MinecraftServer server = HabiLotteryMod.getServer();
        if (server == null || !WorldLotteryPaths.ready()) {
            return 0;
        }
        return PlayerLotteryStore.get().setCoinsToOnline(server, amount);
    }

    /** Clears every online player's 金币; returns the number touched. */
    public static int clearCoinsToOnline() {
        MinecraftServer server = HabiLotteryMod.getServer();
        return server == null ? 0 : PlayerLotteryStore.get().clearCoinsForOnline(server);
    }

    /** Adds {@code delta} 抽数 to every online player; returns the number touched. */
    public static int addDrawsToOnline(int delta) {
        MinecraftServer server = HabiLotteryMod.getServer();
        return server == null ? 0 : PlayerLotteryStore.get().addLootChanceToOnline(server, delta);
    }

    /** Sets every online player's 抽数 to {@code amount}; returns the number touched. */
    public static int setDrawsToOnline(int amount) {
        MinecraftServer server = HabiLotteryMod.getServer();
        return server == null ? 0 : PlayerLotteryStore.get().setLootChanceToOnline(server, amount);
    }

    // ------------------------------------------------------------------
    // Snapshot & migration
    // ------------------------------------------------------------------

    /** Complete per-player asset snapshot. Never {@code null}. */
    public static HabiPlayerAssets snapshot(UUID uuid) {
        if (uuid == null) {
            return HabiPlayerAssets.empty(null);
        }
        ServerPlayer online = online(uuid);
        String name = online == null ? "" : online.getGameProfile().getName();
        if (!WorldLotteryPaths.ready()) {
            HabiPlayerAssets empty = HabiPlayerAssets.empty(uuid);
            return new HabiPlayerAssets(uuid, name, online != null, 0, 0, empty.factionCards(), 0, 0,
                    empty.unlockedSkins(), empty.equippedSkins(), empty.titles(), "", 0, -1L);
        }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        PlayerLotteryData data = store.getOrLoad(uuid);
        Map<String, Integer> cards = HabiCardApi.all(uuid);
        Map<String, List<String>> unlocked = HabiSkinPlayerApi.unlockedAll(uuid);
        Map<String, String> equipped = HabiSkinPlayerApi.equippedAll(uuid);
        List<String> titles = HabiTitleApi.owned(uuid);
        return new HabiPlayerAssets(
                uuid,
                name,
                online != null,
                data.coinNum,
                data.lootChance,
                cards,
                cards.getOrDefault(HabiCardKind.SELF_SELECT.id(), 0),
                cards.getOrDefault(HabiCardKind.LIMIT_BREAK.id(), 0),
                unlocked,
                equipped,
                titles,
                HabiTitleApi.current(uuid),
                Math.max(0, data.consecutiveLoginDays),
                data.lastLoginEpochDay);
    }

    /** Convenience overload for an online player. */
    public static HabiPlayerAssets snapshot(ServerPlayer player) {
        return snapshot(player == null ? null : player.getUUID());
    }

    /** Forces an SRE→world migration for one online player. */
    public static void forceMigrate(ServerPlayer player) {
        if (player != null) {
            MigrationService.forceMigrate(player);
        }
    }

    // ------------------------------------------------------------------
    // Internals shared with the sibling API facades
    // ------------------------------------------------------------------

    static ServerPlayer online(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            MinecraftServer server = HabiLotteryMod.getServer();
            return server == null ? null : server.getPlayerList().getPlayer(uuid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static HabiAssetResult mutateCurrency(UUID uuid, HabiAssetOperation operation, int value, boolean coins) {
        String label = coins ? "金币" : "抽数";
        if (uuid == null) {
            return HabiAssetResult.fail(HabiFailure.NOT_FOUND);
        }
        if (operation == null) {
            return HabiAssetResult.fail(HabiFailure.INVALID_OPERATION);
        }
        if (!HabiAssetPolicy.isValidCurrencyOperation(operation, value)) {
            return HabiAssetResult.fail(HabiFailure.INVALID_VALUE);
        }
        if (!WorldLotteryPaths.ready()) {
            return HabiAssetResult.fail(HabiFailure.NOT_READY);
        }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (store.isLoadFailed(uuid)) {
            return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        }
        PlayerLotteryData before = store.getOrLoad(uuid).copy();
        if (store.isLoadFailed(uuid)) {
            return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        }
        boolean wasDirty = store.isDirty(uuid);
        int[] result = new int[1];
        store.update(uuid, data -> {
            if (coins) {
                data.coinNum = HabiAssetPolicy.applyCurrency(data.coinNum, operation, value);
                result[0] = data.coinNum;
            } else {
                data.lootChance = HabiAssetPolicy.applyCurrency(data.lootChance, operation, value);
                result[0] = data.lootChance;
            }
        });
        if (!store.flush(uuid)) {
            store.restoreSnapshot(uuid, before, wasDirty);
            HabiLotteryMod.LOGGER.error("API {} write failed for {}; rolled back", label, uuid);
            return HabiAssetResult.fail(HabiFailure.WRITE_FAILED, coins ? before.coinNum : before.lootChance);
        }
        ServerPlayer online = online(uuid);
        if (online != null) {
            EconomyMirror.syncChanceAndCoins(online, store.getOrLoad(uuid));
        }
        return HabiAssetResult.success(result[0]);
    }

    /** Ordered zero map of every {@link HabiCardKind}. */
    static Map<String, Integer> emptyCardMap() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (HabiCardKind kind : HabiCardKind.ordered()) {
            out.put(kind.id(), 0);
        }
        return out;
    }
}
