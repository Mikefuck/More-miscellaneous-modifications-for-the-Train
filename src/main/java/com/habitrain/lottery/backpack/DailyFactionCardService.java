package com.habitrain.lottery.backpack;

import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 每日阵营卡<b>使用次数</b>上限与额外次数。
 *
 * <p>1.1.29 起本类不再发放每日登录阵营卡：旧的「登录白送 4 张阵营卡」已取消，
 * 登录奖励改为在每日任务终端领取 160 绿苹果（见
 * {@link com.habitrain.lottery.daily.DailyLoginRewardTask}）。这里只保留每日 4 次
 * 用卡配额、突破上限卡的额外次数与失败返还逻辑。
 */
public final class DailyFactionCardService {
    public static final int MAX_DAILY_USES = 4;
    public static final int MAX_BONUS_USES = Integer.MAX_VALUE - MAX_DAILY_USES;

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
