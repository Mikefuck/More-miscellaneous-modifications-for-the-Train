package com.habitrain.lottery.daily;

import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import com.habitrain.lottery.api.player.HabiCardApi;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the authoritative board view from the green-apple and card stores. */
public final class DailyTaskBoardService {
    private DailyTaskBoardService() { }

    public static DailyTaskSnapshot snapshot(ServerPlayer player) {
        long day = LoginRewardService.todayEpochDayUtc();
        var store = PlayerLotteryStore.get();
        Map<String, Integer> cards = HabiCardApi.all(player.getUUID());
        int total = cards.values().stream().mapToInt(Integer::intValue).sum();
        List<DailyTaskSnapshot.TaskRow> tasks = new ArrayList<>();
        for (var task : HabiDailyTaskApi.tasks().values()) {
            tasks.add(new DailyTaskSnapshot.TaskRow(task.id().toString(), task.title(),
                    task.description(), task.rewardLabel(), HabiDailyTaskApi.progress(player, task.id()),
                    task.target(), HabiDailyTaskApi.claimed(player, task.id())));
        }
        return new DailyTaskSnapshot(day, store.getGreenApples(player.getUUID()),
                cards, total, tasks, sources());
    }

    private static List<DailyTaskSnapshot.SourceRow> sources() {
        List<DailyTaskSnapshot.SourceRow> out = new ArrayList<>();
        out.add(row("green_apples", "每日登录", "登录后在本终端领取 "
                + DailyLoginRewardTask.REWARD_GREEN_APPLES
                + " 绿苹果（原每日 4 张阵营卡已并入此奖励）；UTC 00:00 刷新"));
        out.add(row("green_apples", "邮件 / 管理员 / API", "领取邮件附件、管理员调整和其他模组通过玩家 API 发放"));
        out.add(row("cards", "邮件 / 管理员 / API", "阵营卡、自选卡和突破上限卡可由邮件、管理员或玩家 API 发放"));
        out.add(row("cards", "上游背包命令", "上游 /backpack 直接增减阵营卡，背包改动同步至本模组存档"));
        out.add(row("cards", "分配失败退款", "已消耗的阵营卡若强制分配失败，原卡退回背包"));
        out.add(row("cards", "上游任务 / 等级旧卡", "上游任务与 3/5/7 级奖励写旧进度卡字段，未计入当前背包余额"));
        return out;
    }

    private static DailyTaskSnapshot.SourceRow row(String category, String title, String detail) {
        return new DailyTaskSnapshot.SourceRow(category, title, detail);
    }
}
