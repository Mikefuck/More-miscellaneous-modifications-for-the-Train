package com.habitrain.lottery.api.daily;

import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.network.LotteryNetwork;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/**
 * Server-side extension point for the daily-task board. No built-in tasks are registered.
 * Register definitions during mod initialization, then call {@link #advance} on the server
 * thread when a player makes progress. Progress and claims are saved in the same world
 * player file and reset at 00:00 UTC. A claim action must be durable and idempotent for
 * its player/task/day key; the board persists a retryable pending marker before calling it.
 */
public final class HabiDailyTaskApi {
    public static final int API_VERSION = 1;
    public static final String PROVIDES = "habitrain_lottery_daily_api";
    private static final Map<ResourceLocation, Task> TASKS = new LinkedHashMap<>();

    private HabiDailyTaskApi() { }

    @FunctionalInterface
    public interface ClaimAction {
        /** Return true only after the reward has been durably issued. */
        boolean grant(ServerPlayer player, ResourceLocation taskId, long epochDayUtc);
    }

    public record Task(ResourceLocation id, String title, String description,
                       int target, String rewardLabel, ClaimAction claimAction) {
        public Task {
            if (id == null || id.toString().length() > 128 || title == null || title.isBlank()
                    || title.length() > 64 || target < 1 || target > 1_000_000
                    || description == null || description.length() > 120
                    || rewardLabel == null || rewardLabel.length() > 64 || claimAction == null) {
                throw new IllegalArgumentException("Invalid daily task definition");
            }
        }
    }

    public static synchronized void register(Task task) {
        if (TASKS.size() >= 32) throw new IllegalStateException("Daily task board is full (32 tasks)");
        if (TASKS.putIfAbsent(task.id(), task) != null) {
            throw new IllegalArgumentException("Duplicate daily task: " + task.id());
        }
    }

    public static synchronized Map<ResourceLocation, Task> tasks() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(TASKS));
    }

    /** Opens the board for a player without requiring the block. */
    public static void open(ServerPlayer player) {
        LotteryNetwork.sendDailyTaskSnapshot(player, true);
    }

    /** Advances one registered task. Values above the target are capped. */
    public static boolean advance(ServerPlayer player, ResourceLocation id, int amount) {
        Task task = TASKS.get(id);
        if (!ready(player) || PlayerLotteryStore.get().isFlushDeferred() || task == null || amount <= 0) return false;
        PlayerLotteryStore store = PlayerLotteryStore.get();
        PlayerLotteryData before = store.getOrLoad(player).copy();
        boolean dirty = store.isDirty(player.getUUID());
        long day = LoginRewardService.todayEpochDayUtc();
        store.update(player.getUUID(), data -> {
            resetDay(data, day);
            String key = id.toString();
            int old = progressOf(data, key);
            data.dailyTaskProgress.put(key, Math.min(task.target(), old + Math.min(amount, task.target())));
        });
        if (!store.flush(player.getUUID())) {
            store.restoreSnapshot(player.getUUID(), before, dirty);
            return false;
        }
        LotteryNetwork.sendDailyTaskSnapshot(player, false);
        return true;
    }

    /** Claims a completed task once per UTC day. Only the server may invoke this. */
    public static boolean claim(ServerPlayer player, ResourceLocation id) {
        Task task = TASKS.get(id);
        if (!ready(player) || PlayerLotteryStore.get().isFlushDeferred() || task == null) return false;
        PlayerLotteryStore store = PlayerLotteryStore.get();
        PlayerLotteryData data = store.getOrLoad(player);
        long day = LoginRewardService.todayEpochDayUtc();
        String key = id.toString();
        if (data.dailyTaskEpochDay != day || data.dailyTaskClaims != null && data.dailyTaskClaims.contains(key)
                || progressOf(data, key) < task.target()) {
            return false;
        }
        PlayerLotteryData before = data.copy();
        boolean dirty = store.isDirty(player.getUUID());
        store.update(player.getUUID(), value -> {
            resetDay(value, day);
            value.dailyTaskPending.add(key);
        });
        if (!store.flush(player.getUUID())) {
            store.restoreSnapshot(player.getUUID(), before, dirty);
            return false;
        }
        PlayerLotteryData pending = store.getOrLoad(player).copy();
        try {
            if (task.claimAction().grant(player, id, day)) {
                PlayerLotteryData rewardedPending = store.getOrLoad(player).copy();
                store.update(player.getUUID(), value -> {
                    value.dailyTaskPending.remove(key);
                    value.dailyTaskClaims.add(key);
                });
                if (store.flush(player.getUUID())) {
                    LotteryNetwork.sendDailyTaskSnapshot(player, false);
                    return true;
                }
                store.restoreSnapshot(player.getUUID(), rewardedPending, false);
                return false;
            }
        } catch (RuntimeException error) {
            com.habitrain.lottery.HabiLotteryMod.LOGGER.error("Daily task claim failed: {}", id, error);
        }
        store.update(player.getUUID(), value -> value.dailyTaskPending.remove(key));
        if (!store.flush(player.getUUID())) store.restoreSnapshot(player.getUUID(), pending, false);
        LotteryNetwork.sendDailyTaskSnapshot(player, false);
        return false;
    }

    public static int progress(ServerPlayer player, ResourceLocation id) {
        if (!ready(player)) return 0;
        PlayerLotteryData data = PlayerLotteryStore.get().getOrLoad(player);
        return data.dailyTaskEpochDay == LoginRewardService.todayEpochDayUtc() && data.dailyTaskProgress != null
                ? progressOf(data, id.toString()) : 0;
    }

    public static boolean claimed(ServerPlayer player, ResourceLocation id) {
        if (!ready(player)) return false;
        PlayerLotteryData data = PlayerLotteryStore.get().getOrLoad(player);
        return data.dailyTaskEpochDay == LoginRewardService.todayEpochDayUtc()
                && data.dailyTaskClaims != null && data.dailyTaskClaims.contains(id.toString());
    }

    private static boolean ready(ServerPlayer player) {
        if (player == null || !WorldLotteryPaths.ready()) return false;
        PlayerLotteryStore store = PlayerLotteryStore.get();
        store.getOrLoad(player);
        return !store.isLoadFailed(player.getUUID());
    }

    private static void resetDay(PlayerLotteryData data, long day) {
        if (data.dailyTaskEpochDay != day) {
            data.dailyTaskEpochDay = day;
            data.dailyTaskProgress = new LinkedHashMap<>();
            data.dailyTaskClaims = new java.util.HashSet<>();
            data.dailyTaskPending = new java.util.HashSet<>();
        }
        if (data.dailyTaskProgress == null) data.dailyTaskProgress = new LinkedHashMap<>();
        if (data.dailyTaskClaims == null) data.dailyTaskClaims = new java.util.HashSet<>();
        if (data.dailyTaskPending == null) data.dailyTaskPending = new java.util.HashSet<>();
    }

    private static int progressOf(PlayerLotteryData data, String key) {
        Integer count = data.dailyTaskProgress == null ? null : data.dailyTaskProgress.get(key);
        return count == null ? 0 : Math.max(0, count);
    }
}
