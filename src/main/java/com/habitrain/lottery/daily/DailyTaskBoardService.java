package com.habitrain.lottery.daily;

import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import com.habitrain.lottery.api.player.HabiCardApi;
import com.habitrain.lottery.daily.config.DailyTaskConfigService;
import com.habitrain.lottery.daily.shop.DailyShopService;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the authoritative board view from the green-apple, card and shop stores. */
public final class DailyTaskBoardService {
    private DailyTaskBoardService() { }

    public static DailyTaskSnapshot snapshot(ServerPlayer player) {
        long day = LoginRewardService.todayEpochDayUtc();
        var store = PlayerLotteryStore.get();
        Map<String, Integer> cards = HabiCardApi.all(player.getUUID());
        int total = cards.values().stream().mapToInt(Integer::intValue).sum();
        List<DailyTaskSnapshot.TaskRow> tasks = new ArrayList<>();
        for (var task : HabiDailyTaskApi.tasksFor(player)) {
            tasks.add(new DailyTaskSnapshot.TaskRow(task.id().toString(), task.title(),
                    task.description(), task.rewardLabel(), HabiDailyTaskApi.progress(player, task.id()),
                    task.target(), HabiDailyTaskApi.claimed(player, task.id()),
                    DailyTaskConfigService.isRandomPick(task.id())));
        }
        return new DailyTaskSnapshot(day, store.getGreenApples(player.getUUID()),
                cards, total, tasks, DailyShopService.open(), DailyShopService.rows(player));
    }
}
