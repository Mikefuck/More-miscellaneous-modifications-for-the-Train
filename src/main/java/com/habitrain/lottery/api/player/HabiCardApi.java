package com.habitrain.lottery.api.player;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.backpack.DailyFactionCardService;
import com.habitrain.lottery.backpack.DailySelfSelectService;
import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardMutationResult;
import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardOperation;
import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardSnapshot;
import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardStoreStatus;
import com.habitrain.lottery.backpack.PlayerCardAdminService;
import com.habitrain.lottery.backpack.PlayerCardMutationPolicy;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Public API for the six per-player card balances and their daily use quotas.
 *
 * <p>Faction cards ({@link HabiCardKind#isFactionCard()}) are read for online
 * players from SRE's live {@code BackpackManager} and otherwise from this mod's
 * world JSON. The two virtual cards (自选卡 / 突破上限卡) always live in the
 * world backpack JSON.</p>
 *
 * <ul>
 *   <li>Balances: {@link #all(UUID)}, {@link #get(UUID, HabiCardKind)},
 *       {@link #add(UUID, HabiCardKind, int)}, {@link #set(UUID, HabiCardKind, int)}</li>
 *   <li>Daily quota: {@link #dailyFactionCardRemaining(UUID)},
 *       {@link #dailySelfSelectRemaining(UUID)}, resets</li>
 * </ul>
 *
 * <p>Server thread only; mutations require {@link HabiLotteryApi#isReady()}.</p>
 */
public final class HabiCardApi {
    /** Largest absolute balance accepted by {@link #set}. */
    public static final int MAX_COUNT = HabiAssetPolicy.MAX_CARD_COUNT;
    /** Largest additive delta accepted by {@link #add} (absolute value). */
    public static final int MAX_DELTA = HabiAssetPolicy.MAX_CARD_DELTA;
    /** Daily faction-card uses before bonus cards. */
    public static final int DAILY_FACTION_CARD_LIMIT = DailyFactionCardService.MAX_DAILY_USES;
    /** Daily self-select uses. */
    public static final int DAILY_SELF_SELECT_LIMIT = DailySelfSelectService.MAX_DAILY_USES;

    private HabiCardApi() {
    }

    /** Ordered map of all six balances (missing values are {@code 0}). */
    public static Map<String, Integer> all(UUID uuid) {
        Map<String, Integer> out = HabiLotteryApi.emptyCardMap();
        if (uuid == null || !WorldLotteryPaths.ready()) {
            return out;
        }
        ServerPlayer online = HabiLotteryApi.online(uuid);
        CardSnapshot snapshot = online != null
                ? PlayerCardAdminService.snapshotOnline(online)
                : PlayerCardAdminService.snapshotOffline(uuid);
        if (snapshot.status() == CardStoreStatus.CORRUPT) {
            return out;
        }
        Map<String, Integer> live = snapshot.cards();
        if (live == null) {
            return out;
        }
        for (HabiCardKind kind : HabiCardKind.ordered()) {
            Integer value = live.get(kind.id());
            if (value == null) {
                value = live.get(kind.id().toLowerCase(Locale.ROOT));
            }
            out.put(kind.id(), value == null ? 0 : Math.max(0, value));
        }
        return out;
    }

    /** One balance; {@code 0} when unknown or unreadable. */
    public static int get(UUID uuid, HabiCardKind kind) {
        if (kind == null) {
            return 0;
        }
        return all(uuid).getOrDefault(kind.id(), 0);
    }

    /** Adds {@code delta} cards of {@code kind}. Delta must be in {@code [-1000, 1000]}. */
    public static HabiAssetResult add(UUID uuid, HabiCardKind kind, int delta) {
        return mutate(uuid, kind, HabiAssetOperation.ADD, delta);
    }

    /** Sets {@code kind} to {@code amount} cards. Amount must be in {@code [0, 100000]}. */
    public static HabiAssetResult set(UUID uuid, HabiCardKind kind, int amount) {
        return mutate(uuid, kind, HabiAssetOperation.SET, amount);
    }

    public static HabiAssetResult add(ServerPlayer player, HabiCardKind kind, int delta) {
        return add(player == null ? null : player.getUUID(), kind, delta);
    }

    public static HabiAssetResult set(ServerPlayer player, HabiCardKind kind, int amount) {
        return set(player == null ? null : player.getUUID(), kind, amount);
    }

    // ------------------------------------------------------------------
    // Daily quota
    // ------------------------------------------------------------------

    /**
     * Remaining faction-card uses for the current UTC day
     * ({@link #DAILY_FACTION_CARD_LIMIT} plus bonuses, minus today's uses).
     */
    public static int dailyFactionCardRemaining(UUID uuid) {
        if (uuid == null || !WorldLotteryPaths.ready()) {
            return 0;
        }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (store.isLoadFailed(uuid)) {
            return 0;
        }
        PlayerLotteryData data = store.getOrLoad(uuid);
        if (store.isLoadFailed(uuid)) {
            return 0;
        }
        long today = LoginRewardService.todayEpochDayUtc();
        boolean sameDay = data.lastFactionCardUseEpochDay == today;
        int used = sameDay ? Math.max(0, data.factionCardUsesToday) : 0;
        int bonus = sameDay ? Math.max(0, data.factionCardBonusUsesToday) : 0;
        return HabiAssetPolicy.dailyRemaining(used, bonus, DAILY_FACTION_CARD_LIMIT);
    }

    /** Remaining self-select uses for the current UTC day. */
    public static int dailySelfSelectRemaining(UUID uuid) {
        if (uuid == null || !WorldLotteryPaths.ready()) {
            return 0;
        }
        return DailySelfSelectService.remaining(uuid);
    }

    /**
     * Persists one extra faction-card use for today (the effect of redeeming one
     * 突破上限卡). Requires the player to be online.
     */
    public static HabiAssetResult grantDailyFactionCardBonusUse(ServerPlayer player) {
        if (player == null) {
            return HabiAssetResult.fail(HabiFailure.PLAYER_OFFLINE);
        }
        if (!WorldLotteryPaths.ready()) {
            return HabiAssetResult.fail(HabiFailure.NOT_READY);
        }
        boolean ok = DailyFactionCardService.grantBonusUse(player);
        return ok ? HabiAssetResult.success(dailyFactionCardRemaining(player.getUUID()))
                : HabiAssetResult.fail(HabiFailure.WRITE_FAILED, dailyFactionCardRemaining(player.getUUID()));
    }

    /** Reverts a previously granted bonus use for today. Requires the player to be online. */
    public static HabiAssetResult revokeDailyFactionCardBonusUse(ServerPlayer player) {
        if (player == null) {
            return HabiAssetResult.fail(HabiFailure.PLAYER_OFFLINE);
        }
        if (!WorldLotteryPaths.ready()) {
            return HabiAssetResult.fail(HabiFailure.NOT_READY);
        }
        boolean ok = DailyFactionCardService.revokeBonusUse(player);
        return ok ? HabiAssetResult.success(dailyFactionCardRemaining(player.getUUID()))
                : HabiAssetResult.fail(HabiFailure.WRITE_FAILED, dailyFactionCardRemaining(player.getUUID()));
    }

    /** Resets today's faction-card use counters (uses and bonuses) to zero. */
    public static HabiAssetResult resetDailyFactionCardUsage(UUID uuid) {
        return resetDailyCounter(uuid, true);
    }

    /** Resets today's self-select use counter to zero. */
    public static HabiAssetResult resetDailySelfSelectUsage(UUID uuid) {
        return resetDailyCounter(uuid, false);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static HabiAssetResult mutate(UUID uuid, HabiCardKind kind,
                                          HabiAssetOperation operation, int value) {
        if (uuid == null) {
            return HabiAssetResult.fail(HabiFailure.NOT_FOUND);
        }
        if (kind == null) {
            return HabiAssetResult.fail(HabiFailure.UNKNOWN_CARD_TYPE);
        }
        if (operation == null) {
            return HabiAssetResult.fail(HabiFailure.INVALID_OPERATION);
        }
        if (!HabiAssetPolicy.isValidCardOperation(operation, value)) {
            return HabiAssetResult.fail(HabiFailure.INVALID_VALUE);
        }
        if (!WorldLotteryPaths.ready()) {
            return HabiAssetResult.fail(HabiFailure.NOT_READY);
        }
        CardOperation cardOperation = operation == HabiAssetOperation.ADD ? CardOperation.ADD : CardOperation.SET;
        CardMutationResult result;
        if (kind.isFactionCard()) {
            FactionCardType type = PlayerCardMutationPolicy.parseType(kind.id());
            if (type == null || type == FactionCardType.NONE) {
                return HabiAssetResult.fail(HabiFailure.UNKNOWN_CARD_TYPE);
            }
            result = PlayerCardAdminService.mutate(HabiLotteryMod.getServer(), uuid, type, cardOperation, value);
        } else if (kind == HabiCardKind.SELF_SELECT) {
            result = PlayerCardAdminService.mutateSelfSelect(uuid, cardOperation, value);
        } else {
            result = PlayerCardAdminService.mutateLimitBreak(uuid, cardOperation, value);
        }
        if (result.ok()) {
            return HabiAssetResult.success(result.newCount());
        }
        HabiFailure failure = result.status() == CardStoreStatus.CORRUPT
                ? HabiFailure.CORRUPT_STORAGE
                : HabiFailure.WRITE_FAILED;
        return HabiAssetResult.fail(failure, get(uuid, kind));
    }

    private static HabiAssetResult resetDailyCounter(UUID uuid, boolean factionCard) {
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
        boolean wasDirty = store.isDirty(uuid);
        long today = LoginRewardService.todayEpochDayUtc();
        store.update(uuid, data -> {
            if (factionCard) {
                data.lastFactionCardUseEpochDay = today;
                data.factionCardUsesToday = 0;
                data.factionCardBonusUsesToday = 0;
            } else {
                data.lastSelfSelectUseEpochDay = today;
                data.selfSelectUsesToday = 0;
            }
        });
        if (!store.flush(uuid)) {
            store.restoreSnapshot(uuid, before, wasDirty);
            return HabiAssetResult.fail(HabiFailure.WRITE_FAILED);
        }
        return HabiAssetResult.success();
    }
}
