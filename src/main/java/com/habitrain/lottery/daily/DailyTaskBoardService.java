package com.habitrain.lottery.daily;

import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import com.habitrain.lottery.api.player.HabiCardApi;
import com.habitrain.lottery.config.LotteryConfigService;
import com.habitrain.lottery.config.GrantsConfig;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the authoritative board view from the existing economy and card stores. */
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
        return new DailyTaskSnapshot(day, store.getLootChance(player.getUUID()),
                store.getCoinNum(player.getUUID()), cards, total, tasks, sources());
    }

    private static List<DailyTaskSnapshot.SourceRow> sources() {
        List<DailyTaskSnapshot.SourceRow> out = new ArrayList<>();
        int cap = LotteryConfigService.get().getRates().loginRewardCap();
        out.add(row("draws", "每日签到", "连续登录第 N 天发 min(N, " + cap + ") 抽；UTC 00:00 刷新"));
        GrantsConfig grants = LotteryConfigService.get().getGrants();
        if (grants != null && grants.events != null) {
            for (GrantsConfig.GrantEvent event : grants.events) {
                if (event == null || event.id == null) continue;
                String name = switch (event.id) {
                    case "win_passenger" -> "乘客胜利";
                    case "win_killer" -> "杀手胜利";
                    case "win_neutral" -> "中立胜利";
                    case "blackout_participate" -> "停电模式参与";
                    case "sre_participate" -> "常规 / 维修参与";
                    case "task_complete" -> "旧任务事件接口";
                    case "level_every_n" -> "等级里程碑";
                    default -> event.id;
                };
                String detail = event.enabled ? "基础 +" + event.amount + " 抽，实际受模式倍率影响"
                        : "当前配置未启用";
                if (event.everyLevels != null && event.everyLevels > 0) detail += "；每 " + event.everyLevels + " 级";
                out.add(row("draws", name, detail));
            }
        }
        out.add(row("draws", "金币兑换", "消耗 " + LotteryConfigService.get().getRates().coinPerDraw() + " 金币换 1 抽"));
        out.add(row("draws", "上游等级里程碑", "上游进度每 5 级 +1 抽；是否触发取决于进度系统配置"));
        out.add(row("draws", "上游通行证任务", "上游任务可直接发抽数；奖励由任务配置决定"));
        out.add(row("draws", "邮件 / 管理员 / API", "领取邮件附件、管理员调整和其他模组通过玩家 API 发放"));
        out.add(row("coins", "抽奖结算", "抽中金币或重复皮肤时返还金币；实际数量由奖池和倍率决定"));
        out.add(row("coins", "上游对局与等级", "胜局 +20 金币；升级获 20 + 等级×2 金币"));
        out.add(row("coins", "上游通行证任务", "上游任务可发金币；奖励由任务配置决定"));
        out.add(row("coins", "职业技能 / 事件", "上游个别职业技能与控制目标死亡事件会发金币"));
        out.add(row("coins", "邮件 / 管理员 / API", "领取邮件附件、管理员调整和其他模组通过玩家 API 发放"));
        out.add(row("cards", "每日四阵营卡", "每日每类各 1 张：平民、中立、杀手方中立、杀手"));
        out.add(row("cards", "上游任务 / 等级旧卡", "上游任务与 3/5/7 级奖励写旧进度卡字段，未计入当前背包余额"));
        out.add(row("cards", "上游背包命令", "上游 /backpack 直接增减阵营卡，背包改动同步至本模组存档"));
        out.add(row("cards", "分配失败退款", "已消耗的阵营卡若强制分配失败，原卡退回背包"));
        out.add(row("cards", "邮件 / 管理员 / API", "阵营卡、自选卡和突破上限卡可由邮件、管理员或玩家 API 发放"));
        return out;
    }

    private static DailyTaskSnapshot.SourceRow row(String category, String title, String detail) {
        return new DailyTaskSnapshot.SourceRow(category, title, detail);
    }
}
