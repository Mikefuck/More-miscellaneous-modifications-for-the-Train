package com.habitrain.lottery.backpack;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import io.wifi.starrailexpress.backpack.BackpackManager;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Daily faction-card grant and successful-use limit, both using the login UTC day boundary. */
public final class DailyFactionCardService {
    public static final int MAX_DAILY_USES = 4;
    public static final int MAX_BONUS_USES = Integer.MAX_VALUE - MAX_DAILY_USES;
    public static final int DAILY_LOGIN_CARD_COUNT = 4;
    private static final FactionCardType[] GRANTABLE = {
            FactionCardType.KILLER,
            FactionCardType.CIVILIAN,
            FactionCardType.NEUTRAL,
            FactionCardType.NEUTRAL_FOR_KILLER
    };

    private DailyFactionCardService() {}

    public static boolean hasReachedLimit(ServerPlayer player) {
        if (player == null) {
            return true;
        }
        normalizeUseDay(player);
        return remaining(player) <= 0;
    }

    public static int remaining(ServerPlayer player) {
        if (player == null) return 0;
        var store = PlayerLotteryStore.get();
        if (store.isLoadFailed(player.getUUID())) return 0;
        normalizeUseDay(player);
        if (store.isLoadFailed(player.getUUID())) return 0;
        PlayerLotteryData data = store.getOrLoad(player);
        return Math.max(0, MAX_DAILY_USES + Math.min(MAX_BONUS_USES, data.factionCardBonusUsesToday)
                - data.factionCardUsesToday);
    }

    /** Persist one extra use before its card is consumed. */
    public static boolean grantBonusUse(ServerPlayer player) {
        if (player == null) return false;
        var store = PlayerLotteryStore.get();
        UUID uuid = player.getUUID();
        if (store.isLoadFailed(uuid)) return false;
        PlayerLotteryData before = store.getOrLoad(player).copy();
        if (store.isLoadFailed(uuid)) return false;
        boolean wasDirty = store.isDirty(uuid);
        long today = LoginRewardService.todayEpochDayUtc();
        int currentBonus = before.lastFactionCardUseEpochDay == today
                ? before.factionCardBonusUsesToday : 0;
        if (currentBonus >= MAX_BONUS_USES) return false;
        store.update(uuid, data -> {
            if (data.lastFactionCardUseEpochDay != today) {
                data.lastFactionCardUseEpochDay = today;
                data.factionCardUsesToday = 0;
                data.factionCardBonusUsesToday = 0;
            }
            data.factionCardBonusUsesToday++;
        });
        if (!store.flush(uuid)) {
            store.restoreSnapshot(uuid, before, wasDirty);
            return false;
        }
        return true;
    }

    /** Compensates a failed virtual-card debit after the bonus was persisted. */
    public static boolean revokeBonusUse(ServerPlayer player) {
        if (player == null) return false;
        var store = PlayerLotteryStore.get();
        UUID uuid = player.getUUID();
        if (store.isLoadFailed(uuid)) return false;
        PlayerLotteryData before = store.getOrLoad(player).copy();
        boolean wasDirty = store.isDirty(uuid);
        if (before.lastFactionCardUseEpochDay != LoginRewardService.todayEpochDayUtc()
                || before.factionCardBonusUsesToday <= 0) return false;
        store.update(uuid, data -> data.factionCardBonusUsesToday--);
        if (!store.flush(uuid)) {
            store.restoreSnapshot(uuid, before, wasDirty);
            return false;
        }
        return true;
    }

    public static void recordSuccessfulUse(ServerPlayer player) {
        if (player == null) {
            return;
        }
        long today = LoginRewardService.todayEpochDayUtc();
        PlayerLotteryStore.get().update(player.getUUID(), data -> {
            if (data.lastFactionCardUseEpochDay != today) {
                data.lastFactionCardUseEpochDay = today;
                data.factionCardUsesToday = 0;
                data.factionCardBonusUsesToday = 0;
            }
            data.factionCardUsesToday = (int) Math.min(
                    (long) MAX_DAILY_USES + data.factionCardBonusUsesToday,
                    (long) data.factionCardUsesToday + 1);
        });
        PlayerLotteryStore.get().flush(player.getUUID());
        player.sendSystemMessage(Component.literal("§e[职业卡] 今日还可使用 " + remaining(player) + " 次"));
    }

    /**
     * 失败返还职业卡时，恢复一次今日使用配额。
     * <p>职业卡激活时 {@link #recordSuccessfulUse} 使 {@code factionCardUsesToday}+1；
     * 若该卡在分配时未能兑现被退回，则此处-1，让每日 4 次上限配额复原。
     * 仅当今日确有成功使用记录时递减；跨 UTC 日时计数器已重置，无需退还。
     */
    public static void refundSuccessfulUse(ServerPlayer player) {
        if (player == null) {
            return;
        }
        refundSuccessfulUse(player.getUUID());
    }

    /** Offline-safe daily-use refund; does not require a {@link ServerPlayer}. */
    public static void refundSuccessfulUse(UUID uuid) {
        if (uuid == null) {
            return;
        }
        long today = LoginRewardService.todayEpochDayUtc();
        PlayerLotteryStore.get().update(uuid, data -> {
            if (data.lastFactionCardUseEpochDay != today) {
                return;
            }
            data.factionCardUsesToday = Math.max(0, data.factionCardUsesToday - 1);
        });
        PlayerLotteryStore.get().flush(uuid);
    }

    public static void grantLoginCard(ServerPlayer player) {
        if (player == null || !grantLoginCards(player.getUUID(),
                new PlayerCardAdminService.ServerOnlineCardAccess(player))) return;
        FactionCardType[] granted = GRANTABLE.clone();
        MutableComponent message = Component.literal(
                "§a[职业卡] 今日上线获得 " + DAILY_LOGIN_CARD_COUNT + " 张：");
        for (int i = 0; i < granted.length; i++) {
            if (i > 0) {
                message.append(Component.literal("、"));
            }
            message.append(Component.translatable(granted[i].displayName));
        }
        player.sendSystemMessage(message);
    }

    /** All four grants and the day marker must succeed before reporting a daily reward. */
    static boolean grantLoginCards(UUID uuid, PlayerCardAdminService.OnlineCardAccess access) {
        if (uuid == null || access == null || access.storageCorrupt()) return false;
        var store = PlayerLotteryStore.get();
        var before = store.getOrLoad(uuid).copy();
        long today = LoginRewardService.todayEpochDayUtc();
        if (store.isLoadFailed(uuid) || before.lastFactionCardGrantEpochDay == today) return false;
        boolean wasDirty = store.isDirty(uuid);
        var cardsBefore = new java.util.EnumMap<FactionCardType, Integer>(FactionCardType.class);
        cardsBefore.putAll(access.cards());
        try {
            for (FactionCardType type : GRANTABLE) {
                int count = cardsBefore.getOrDefault(type, 0);
                if (count == Integer.MAX_VALUE) throw new IllegalStateException("Faction card balance is full");
                access.add(type, 1);
                if (access.count(type) != count + 1) throw new IllegalStateException("Daily faction card did not apply");
            }
            if (!access.persist()) throw new IllegalStateException("Daily faction cards could not be persisted");
            store.update(uuid, data -> data.lastFactionCardGrantEpochDay = today);
            if (!store.flush(uuid)) throw new IllegalStateException("Daily faction card day could not be persisted");
            return true;
        } catch (RuntimeException failure) {
            HabiLotteryMod.LOGGER.error("Daily faction card grant failed for {}, rolling back", uuid, failure);
            store.restoreSnapshot(uuid, before, wasDirty);
            boolean restored = true;
            for (FactionCardType type : GRANTABLE) {
                try {
                    int delta = cardsBefore.getOrDefault(type, 0) - access.count(type);
                    if (delta != 0) access.add(type, delta);
                } catch (RuntimeException restoreFailure) {
                    restored = false;
                    HabiLotteryMod.LOGGER.error("Daily faction card rollback failed for {} / {}", uuid, type, restoreFailure);
                }
            }
            try {
                restored &= access.persist();
            } catch (RuntimeException restoreFailure) {
                restored = false;
            }
            if (!restored) {
                // Block automatic retries for this day until an administrator checks the storage.
                store.update(uuid, data -> data.lastFactionCardGrantEpochDay = today);
                store.flush(uuid);
                HabiLotteryMod.LOGGER.error("Daily faction card grant for {} needs manual recovery", uuid);
            }
            return false;
        }
    }

    private static void normalizeUseDay(ServerPlayer player) {
        long today = LoginRewardService.todayEpochDayUtc();
        PlayerLotteryData data = PlayerLotteryStore.get().getOrLoad(player);
        if (data.lastFactionCardUseEpochDay == today) {
            return;
        }
        PlayerLotteryStore.get().update(player.getUUID(), d -> {
            d.lastFactionCardUseEpochDay = today;
            d.factionCardUsesToday = 0;
            d.factionCardBonusUsesToday = 0;
        });
        PlayerLotteryStore.get().flush(player.getUUID());
    }
}
