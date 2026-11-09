package com.habitrain.lottery.backpack;

import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.time.LotteryDayClock;
import java.util.UUID;

/** Independent self-select quota. No automatic daily card grant. */
public final class DailySelfSelectService {
    public static final int MAX_DAILY_USES = DailyFactionCardService.MAX_DAILY_USES;
    private DailySelfSelectService() {}

    public static int remaining(UUID uuid) {
        if (uuid == null) return 0;
        var store = PlayerLotteryStore.get();
        var data = store.getOrLoad(uuid);
        if (store.isLoadFailed(uuid)) return 0;
        long today = LotteryDayClock.monotonicEpochDay(data.lastSelfSelectUseEpochDay);
        int used = data.lastSelfSelectUseEpochDay == today
                ? data.selfSelectUsesToday : 0;
        return Math.max(0, MAX_DAILY_USES - used);
    }

    public static boolean recordSuccessfulUse(UUID uuid) {
        if (uuid == null || remaining(uuid) <= 0) return false;
        var store = PlayerLotteryStore.get();
        if (store.isLoadFailed(uuid) || store.isFlushDeferred()) return false;
        var before = store.getOrLoad(uuid).copy();
        if (store.isLoadFailed(uuid)) return false;
        boolean wasDirty = store.isDirty(uuid);
        long today = LotteryDayClock.monotonicEpochDay(before.lastSelfSelectUseEpochDay);
        store.update(uuid, data -> {
            if (data.lastSelfSelectUseEpochDay != today) {
                data.lastSelfSelectUseEpochDay = today;
                data.selfSelectUsesToday = 0;
            }
            data.selfSelectUsesToday++;
        });
        if (!store.flush(uuid)) {
            store.restoreSnapshot(uuid, before, wasDirty);
            return false;
        }
        return true;
    }

    public static boolean refundSuccessfulUse(UUID uuid) {
        if (uuid == null) return false;
        var store = PlayerLotteryStore.get();
        if (store.isLoadFailed(uuid) || store.isFlushDeferred()) return false;
        var before = store.getOrLoad(uuid).copy();
        if (store.isLoadFailed(uuid)) return false;
        boolean wasDirty = store.isDirty(uuid);
        long today = LotteryDayClock.monotonicEpochDay(before.lastSelfSelectUseEpochDay);
        store.update(uuid, data -> {
            if (data.lastSelfSelectUseEpochDay == today) {
                data.selfSelectUsesToday = Math.max(0, data.selfSelectUsesToday - 1);
            }
        });
        if (!store.flush(uuid)) {
            store.restoreSnapshot(uuid, before, wasDirty);
            return false;
        }
        return true;
    }
}
