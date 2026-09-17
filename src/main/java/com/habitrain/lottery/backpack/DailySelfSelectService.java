package com.habitrain.lottery.backpack;

import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import java.util.UUID;

/** Independent self-select quota. No automatic daily card grant. */
public final class DailySelfSelectService {
    public static final int MAX_DAILY_USES = DailyFactionCardService.MAX_DAILY_USES;
    private DailySelfSelectService() {}

    public static int remaining(UUID uuid) {
        if (uuid == null) return 0;
        var data = PlayerLotteryStore.get().getOrLoad(uuid);
        if (PlayerLotteryStore.get().isLoadFailed(uuid)) return 0;
        int used = data.lastSelfSelectUseEpochDay == LoginRewardService.todayEpochDayUtc()
                ? data.selfSelectUsesToday : 0;
        return Math.max(0, MAX_DAILY_USES - used);
    }

    public static boolean recordSuccessfulUse(UUID uuid) {
        if (uuid == null || remaining(uuid) <= 0) return false;
        var store = PlayerLotteryStore.get();
        var before = store.getOrLoad(uuid).copy();
        if (store.isLoadFailed(uuid)) return false;
        boolean wasDirty = store.isDirty(uuid);
        long today = LoginRewardService.todayEpochDayUtc();
        PlayerLotteryStore.get().update(uuid, data -> {
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
        var before = store.getOrLoad(uuid).copy();
        if (store.isLoadFailed(uuid)) return false;
        boolean wasDirty = store.isDirty(uuid);
        long today = LoginRewardService.todayEpochDayUtc();
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
