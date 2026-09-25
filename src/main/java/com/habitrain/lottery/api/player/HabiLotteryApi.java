package com.habitrain.lottery.api.player;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Public green-apple and player-asset API. Mutations must run on the server thread.
 * A successful result means the authoritative world JSON was written; a failed
 * write restores the in-memory snapshot.
 */
public final class HabiLotteryApi {
    public static final int API_VERSION = 2;
    public static final String MOD_ID = "habitrain_lottery";
    public static final String PROVIDES = "habitrain_lottery_player_api";
    private static final String DEFAULT_GRANT_REASON = "habitrain_api:manual";

    private HabiLotteryApi() { }

    public static boolean isReady() {
        return WorldLotteryPaths.ready();
    }

    public static boolean isPlayerStoreActive() {
        return PlayerLotteryStore.get().isTakeoverActive();
    }

    public static MinecraftServer server() {
        return HabiLotteryMod.getServer();
    }

    public static int getGreenApples(UUID uuid) {
        return uuid == null ? 0 : PlayerLotteryStore.get().getGreenApples(uuid);
    }

    public static int getGreenApples(ServerPlayer player) {
        return player == null ? 0 : getGreenApples(player.getUUID());
    }

    public static HabiAssetResult setGreenApples(UUID uuid, int amount) {
        return mutateGreenApples(uuid, HabiAssetOperation.SET, amount);
    }

    public static HabiAssetResult addGreenApples(UUID uuid, int delta) {
        return mutateGreenApples(uuid, HabiAssetOperation.ADD, delta);
    }

    public static HabiAssetResult setGreenApples(ServerPlayer player, int amount) {
        return setGreenApples(player == null ? null : player.getUUID(), amount);
    }

    public static HabiAssetResult addGreenApples(ServerPlayer player, int delta) {
        return addGreenApples(player == null ? null : player.getUUID(), delta);
    }

    /** Grants apples once per key when {@code dedupe} is true. */
    public static HabiAssetResult grantGreenApples(UUID uuid, int amount, String reason, boolean dedupe) {
        if (uuid == null) return HabiAssetResult.fail(HabiFailure.NOT_FOUND);
        if (!HabiAssetPolicy.isValidCurrencyOperation(HabiAssetOperation.ADD, amount))
            return HabiAssetResult.fail(HabiFailure.INVALID_VALUE);
        if (!isReady()) return HabiAssetResult.fail(HabiFailure.NOT_READY);
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (store.isLoadFailed(uuid)) return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        PlayerLotteryData before = store.getOrLoad(uuid).copy();
        if (store.isLoadFailed(uuid)) return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        boolean wasDirty = store.isDirty(uuid);
        String key = reason == null || reason.isBlank() ? DEFAULT_GRANT_REASON : reason;
        if (dedupe && !store.tryConsumeGrantKey(uuid, key))
            return HabiAssetResult.fail(HabiFailure.DUPLICATE_GRANT, before.greenApples);
        store.update(uuid, d -> d.greenApples =
                HabiAssetPolicy.applyCurrency(d.greenApples, HabiAssetOperation.ADD, amount));
        if (!store.flush(uuid)) {
            store.restoreSnapshot(uuid, before, wasDirty);
            return HabiAssetResult.fail(HabiFailure.WRITE_FAILED, before.greenApples);
        }
        return HabiAssetResult.success(store.getGreenApples(uuid));
    }

    public static int addGreenApplesToOnline(int delta) {
        MinecraftServer current = server();
        return current == null ? 0 : PlayerLotteryStore.get().addGreenApplesToOnline(current, delta);
    }

    public static int setGreenApplesToOnline(int amount) {
        MinecraftServer current = server();
        return current == null ? 0 : PlayerLotteryStore.get().setGreenApplesToOnline(current, amount);
    }

    public static int clearGreenApplesForOnline() {
        return setGreenApplesToOnline(0);
    }

    /** Complete per-player asset snapshot. Never {@code null}. */
    public static HabiPlayerAssets snapshot(UUID uuid) {
        if (uuid == null) return HabiPlayerAssets.empty(null);
        ServerPlayer online = online(uuid);
        String name = online == null ? "" : online.getGameProfile().getName();
        if (!isReady()) {
            HabiPlayerAssets empty = HabiPlayerAssets.empty(uuid);
            return new HabiPlayerAssets(uuid, name, online != null, 0, empty.factionCards(), 0, 0,
                    empty.unlockedSkins(), empty.equippedSkins(), empty.titles(), "", 0, -1L);
        }
        PlayerLotteryData data = PlayerLotteryStore.get().getOrLoad(uuid);
        Map<String, Integer> cards = HabiCardApi.all(uuid);
        return new HabiPlayerAssets(uuid, name, online != null, data.greenApples, cards,
                cards.getOrDefault(HabiCardKind.SELF_SELECT.id(), 0),
                cards.getOrDefault(HabiCardKind.LIMIT_BREAK.id(), 0),
                HabiSkinPlayerApi.unlockedAll(uuid), HabiSkinPlayerApi.equippedAll(uuid),
                HabiTitleApi.owned(uuid), HabiTitleApi.current(uuid),
                Math.max(0, data.consecutiveLoginDays), data.lastLoginEpochDay);
    }

    public static HabiPlayerAssets snapshot(ServerPlayer player) {
        return snapshot(player == null ? null : player.getUUID());
    }

    static ServerPlayer online(UUID uuid) {
        if (uuid == null) return null;
        try {
            MinecraftServer current = server();
            return current == null ? null : current.getPlayerList().getPlayer(uuid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static HabiAssetResult mutateGreenApples(UUID uuid, HabiAssetOperation operation, int value) {
        if (uuid == null) return HabiAssetResult.fail(HabiFailure.NOT_FOUND);
        if (operation == null) return HabiAssetResult.fail(HabiFailure.INVALID_OPERATION);
        if (!HabiAssetPolicy.isValidCurrencyOperation(operation, value))
            return HabiAssetResult.fail(HabiFailure.INVALID_VALUE);
        if (!isReady()) return HabiAssetResult.fail(HabiFailure.NOT_READY);
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (store.isLoadFailed(uuid)) return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        PlayerLotteryData before = store.getOrLoad(uuid).copy();
        if (store.isLoadFailed(uuid)) return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        boolean wasDirty = store.isDirty(uuid);
        store.update(uuid, d -> d.greenApples = HabiAssetPolicy.applyCurrency(d.greenApples, operation, value));
        if (!store.flush(uuid)) {
            store.restoreSnapshot(uuid, before, wasDirty);
            HabiLotteryMod.LOGGER.error("API green-apple write failed for {}; rolled back", uuid);
            return HabiAssetResult.fail(HabiFailure.WRITE_FAILED, before.greenApples);
        }
        return HabiAssetResult.success(store.getGreenApples(uuid));
    }

    static Map<String, Integer> emptyCardMap() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (HabiCardKind kind : HabiCardKind.ordered()) out.put(kind.id(), 0);
        return out;
    }
}
