package com.habitrain.lottery.grant;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.daily.DailyLoginRewardTask;
import com.habitrain.lottery.network.LotteryNetwork;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;

/**
 * UTC 登录日记账：维护连续登录天数与当月登录日历（登录月历方块的数据源）。
 *
 * <p>1.1.29 起这里<b>不再发放任何奖励</b>：
 * <ul>
 *   <li>旧的「连续登录第 N 天自动发 min(N, 10) 个绿苹果」已取消；</li>
 *   <li>旧的「每日自动白送 4 张阵营卡」已取消。</li>
 * </ul>
 * 两者合并为每日任务终端上的内置任务
 * {@link com.habitrain.lottery.daily.DailyLoginRewardTask}：登录即可在面板领取
 * 160 绿苹果。本类只负责日期/连续天数结算，供登录月历与玩家资产快照展示。
 */
public final class LoginRewardService {
    static final long UNOBSERVED_DAY = Long.MIN_VALUE;
    private static long observedEpochDayUtc = UNOBSERVED_DAY;

    private LoginRewardService() {
    }

    public record SettleOutcome(boolean mutated) {
    }

    public static long todayEpochDayUtc() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }

    public static LocalDate dateOf(long epochDay) {
        return LocalDate.ofEpochDay(epochDay);
    }

    /**
     * True when a previously observed UTC day is set and differs from {@code today}.
     * Uninitialized observation (first tick after boot) is not a roll — JOIN already settled.
     */
    public static boolean utcDayRolled(long previousObservedDay, long todayEpochDay) {
        return previousObservedDay != UNOBSERVED_DAY && previousObservedDay != todayEpochDay;
    }

    public static void resetObservedDayUtc() {
        observedEpochDayUtc = UNOBSERVED_DAY;
    }

    /**
     * Cheap END_SERVER_TICK hook: when the UTC day changes, settle the login day and
     * refresh the claimable daily-login task for every currently online player.
     */
    public static void onEndServerTick(MinecraftServer server) {
        if (server == null || !PlayerLotteryStore.get().isTakeoverActive()) {
            return;
        }
        long today = todayEpochDayUtc();
        if (!utcDayRolled(observedEpochDayUtc, today)) {
            if (observedEpochDayUtc == UNOBSERVED_DAY) {
                observedEpochDayUtc = today;
            }
            return;
        }
        observedEpochDayUtc = today;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player == null || PlayerLotteryStore.get().isLoadFailed(player.getUUID())) {
                    continue;
                }
                try {
                    settle(player, true);
                    // 新的 UTC 日：把当日登录奖励重新变成可领取状态。
                    DailyLoginRewardTask.onLogin(player);
                    LotteryNetwork.sendLoginState(player);
                } catch (Exception e) {
                    HabiLotteryMod.LOGGER.warn("Online UTC-day settle failed for {}: {}",
                            player.getGameProfile().getName(), e.toString());
                }
            }
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Online UTC-day settle failed: {}", e.toString());
        }
    }

    /**
     * Settle the login day for today (idempotent). Sends login state S2C.
     */
    public static void onPlayerJoin(ServerPlayer player) {
        if (player == null || !PlayerLotteryStore.get().isTakeoverActive()
                || PlayerLotteryStore.get().isLoadFailed(player.getUUID())) {
            return;
        }
        try {
            settle(player, true);
            LotteryNetwork.sendLoginState(player);
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Login settle failed for {}: {}",
                    player.getGameProfile().getName(), e.toString());
        }
    }

    /**
     * Force re-sync state (login-calendar block); does not re-settle if already recorded today.
     * Also repairs today's claimable daily-login task, so the calendar block stays a valid
     * entry point to the relocated reward.
     */
    public static void onInspect(ServerPlayer player) {
        if (player == null || !PlayerLotteryStore.get().isTakeoverActive()
                || PlayerLotteryStore.get().isLoadFailed(player.getUUID())) {
            return;
        }
        settle(player, false);
        DailyLoginRewardTask.onLogin(player);
        PlayerLotteryData d = PlayerLotteryStore.get().getOrLoad(player);
        player.sendSystemMessage(Component.literal(
                "§a[签到] 连续登录 " + d.consecutiveLoginDays
                        + " 天 · 每日登录奖励请在每日任务终端领取"));
        LotteryNetwork.sendLoginState(player);
    }

    /**
     * True when today's UTC day is already recorded: {@code lastLoginEpochDay == today}
     * and {@code dayOfMonth} is in the current month set.
     */
    public static boolean alreadySettledToday(PlayerLotteryData d, long today, String monthKey, int dayOfMonth) {
        if (d == null || d.lastLoginEpochDay != today) {
            return false;
        }
        if (d.loginDaysThisMonth == null || monthKey == null || !monthKey.equals(d.loginDaysMonthKey)) {
            return false;
        }
        return d.loginDaysThisMonth.contains(dayOfMonth);
    }

    /**
     * Pure login-day bookkeeping. Mutates {@code d} only when the UTC day or month set
     * needs an update. Grants nothing.
     */
    public static SettleOutcome applySettle(PlayerLotteryData d, long today, String monthKey, int dayOfMonth) {
        if (d == null) {
            return new SettleOutcome(false);
        }
        if (d.loginDaysThisMonth == null) {
            d.loginDaysThisMonth = new HashSet<>();
        }
        if (alreadySettledToday(d, today, monthKey, dayOfMonth)) {
            return new SettleOutcome(false);
        }

        if (d.loginDaysMonthKey == null || !d.loginDaysMonthKey.equals(monthKey)) {
            d.loginDaysMonthKey = monthKey;
            d.loginDaysThisMonth = new HashSet<>();
        }
        d.loginDaysThisMonth.add(dayOfMonth);

        if (d.lastLoginEpochDay == today) {
            return new SettleOutcome(true);
        }

        if (d.lastLoginEpochDay == today - 1) {
            d.consecutiveLoginDays = Math.max(1, d.consecutiveLoginDays) + 1;
        } else {
            d.consecutiveLoginDays = 1;
        }
        d.lastLoginEpochDay = today;
        return new SettleOutcome(true);
    }

    private static void settle(ServerPlayer player, boolean announceFailure) {
        long today = todayEpochDayUtc();
        LocalDate todayDate = dateOf(today);
        String monthKey = String.format("%04d-%02d", todayDate.getYear(), todayDate.getMonthValue());
        int dayOfMonth = todayDate.getDayOfMonth();

        PlayerLotteryStore store = PlayerLotteryStore.get();
        PlayerLotteryData current = store.getOrLoad(player);
        if (alreadySettledToday(current, today, monthKey, dayOfMonth)) {
            return;
        }

        PlayerLotteryData snapshot = current.copy();
        boolean wasDirty = store.isDirty(player.getUUID());
        store.update(player.getUUID(), d -> applySettle(d, today, monthKey, dayOfMonth));
        if (!store.flush(player.getUUID())) {
            store.restoreSnapshot(player.getUUID(), snapshot, wasDirty);
            HabiLotteryMod.LOGGER.error(
                    "Login day flush failed for {}; restored snapshot (UTC day not consumed)",
                    player.getUUID());
            if (announceFailure) {
                player.sendSystemMessage(Component.literal("§c[签到] 存档写入失败，登录记录未保存，请稍后重试"));
            }
        }
    }
}
