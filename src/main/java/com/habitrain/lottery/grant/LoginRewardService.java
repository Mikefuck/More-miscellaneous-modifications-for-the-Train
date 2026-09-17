package com.habitrain.lottery.grant;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.backpack.DailyFactionCardService;
import com.habitrain.lottery.bridge.EconomyMirror;
import com.habitrain.lottery.config.LotteryConfigService;
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
 * UTC consecutive-login rewards: day N grants min(N, loginRewardCap) draws once per day.
 */
public final class LoginRewardService {
    static final long UNOBSERVED_DAY = Long.MIN_VALUE;
    private static long observedEpochDayUtc = UNOBSERVED_DAY;

    private LoginRewardService() {
    }

    public record SettleOutcome(boolean mutated, int granted) {
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
     * Cheap END_SERVER_TICK hook: when the UTC day changes, settle login + daily cards
     * for every currently online player.
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
                    DailyFactionCardService.grantLoginCard(player);
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
     * Settle login for today (idempotent). Sends login state S2C.
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
            HabiLotteryMod.LOGGER.warn("Login reward settle failed for {}: {}",
                    player.getGameProfile().getName(), e.toString());
        }
    }

    /**
     * Force re-sync state (block back face); does not re-grant if already settled today.
     */
    public static void onInspect(ServerPlayer player) {
        if (player == null || !PlayerLotteryStore.get().isTakeoverActive()
                || PlayerLotteryStore.get().isLoadFailed(player.getUUID())) {
            return;
        }
        settle(player, false);
        PlayerLotteryData d = PlayerLotteryStore.get().getOrLoad(player);
        player.sendSystemMessage(Component.literal(
                "§a[签到] 连续登录 " + d.consecutiveLoginDays
                        + " 天 · 今日奖励 "
                        + Math.min(Math.max(1, d.consecutiveLoginDays),
                        LotteryConfigService.get().getRates().loginRewardCap())
                        + " 抽（UTC 日切）"));
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
     * Pure login-day settlement. Mutates {@code d} only when the UTC day or month set needs an update.
     */
    public static SettleOutcome applySettle(PlayerLotteryData d, long today, String monthKey, int dayOfMonth, int cap) {
        if (d == null) {
            return new SettleOutcome(false, 0);
        }
        if (d.loginDaysThisMonth == null) {
            d.loginDaysThisMonth = new HashSet<>();
        }
        if (alreadySettledToday(d, today, monthKey, dayOfMonth)) {
            return new SettleOutcome(false, 0);
        }

        if (d.loginDaysMonthKey == null || !d.loginDaysMonthKey.equals(monthKey)) {
            d.loginDaysMonthKey = monthKey;
            d.loginDaysThisMonth = new HashSet<>();
        }
        d.loginDaysThisMonth.add(dayOfMonth);

        if (d.lastLoginEpochDay == today) {
            return new SettleOutcome(true, 0);
        }

        if (d.lastLoginEpochDay == today - 1) {
            d.consecutiveLoginDays = Math.max(1, d.consecutiveLoginDays) + 1;
        } else {
            d.consecutiveLoginDays = 1;
        }
        d.lastLoginEpochDay = today;

        int amount = Math.min(d.consecutiveLoginDays, cap);
        d.lootChance = Math.max(0, d.lootChance + amount);
        return new SettleOutcome(true, amount);
    }

    private static void settle(ServerPlayer player, boolean announceGrant) {
        long today = todayEpochDayUtc();
        LocalDate todayDate = dateOf(today);
        String monthKey = String.format("%04d-%02d", todayDate.getYear(), todayDate.getMonthValue());
        int dayOfMonth = todayDate.getDayOfMonth();
        int cap = LotteryConfigService.get().getRates().loginRewardCap();

        PlayerLotteryStore store = PlayerLotteryStore.get();
        PlayerLotteryData current = store.getOrLoad(player);
        if (alreadySettledToday(current, today, monthKey, dayOfMonth)) {
            return;
        }

        PlayerLotteryData snapshot = current.copy();
        boolean wasDirty = store.isDirty(player.getUUID());
        final int[] granted = {0};
        store.update(player.getUUID(), d -> {
            SettleOutcome outcome = applySettle(d, today, monthKey, dayOfMonth, cap);
            granted[0] = outcome.granted();
        });
        boolean ok = store.flush(player.getUUID());
        if (!ok) {
            store.restoreSnapshot(player.getUUID(), snapshot, wasDirty);
            HabiLotteryMod.LOGGER.error(
                    "Login settle flush failed for {}; restored snapshot (UTC day not consumed)",
                    player.getUUID());
            if (announceGrant) {
                player.sendSystemMessage(Component.literal("§c[签到] 存档写入失败，今日奖励未发放，请稍后重试"));
            }
            return;
        }

        if (granted[0] > 0) {
            EconomyMirror.syncChanceAndCoins(player, store.getOrLoad(player));
            if (announceGrant) {
                player.sendSystemMessage(Component.literal(
                        "§a[签到] 连续第 " + store.getOrLoad(player).consecutiveLoginDays
                                + " 天，获得 " + granted[0] + " 次抽奖机会"));
            }
            HabiLotteryMod.LOGGER.info("Login reward {} draws for {} streak={}",
                    granted[0], player.getGameProfile().getName(),
                    store.getOrLoad(player).consecutiveLoginDays);
        }
    }

    public static int rewardForStreak(int streak) {
        int cap = LotteryConfigService.get().getRates().loginRewardCap();
        return Math.min(Math.max(0, streak), cap);
    }
}
