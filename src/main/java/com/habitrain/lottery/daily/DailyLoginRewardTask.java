package com.habitrain.lottery.daily;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import com.habitrain.lottery.api.player.HabiAssetResult;
import com.habitrain.lottery.api.player.HabiFailure;
import com.habitrain.lottery.api.player.HabiLotteryApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * 旧版「每日登录」任务兼容入口；正常初始化使用 DailyTaskConfigService 的可配置任务。
 *
 * <p>历史行为（1.1.28 及以前）：登录时自动白送 4 张阵营卡（杀手 / 平民 / 中立 /
 * 杀手方中立各 1 张），另有一套自动到账的连续签到绿苹果。1.1.29 起两者一并取消，
 * 并入本任务：登录把进度推进到 1/1，玩家在每日任务终端手动领取
 * {@link #REWARD_GREEN_APPLES} 绿苹果。<b>奖励不再是角色卡。</b>
 *
 * <p>显式调用本兼容入口的扩展使用 {@link HabiLotteryApi#grantGreenApples} 的最近 64 个
 * 不同键去重窗口。当前默认任务由 DailyRewardService 使用持久化意图和长期回执恢复。
 */
public final class DailyLoginRewardTask {
    /** 任务 ID：{@code habitrain_lottery:daily_login}。 */
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "daily_login");
    /** 每日登录奖励：160 绿苹果。 */
    public static final int REWARD_GREEN_APPLES = 160;
    /** 登录即达标，任务目标固定为 1。 */
    public static final int TARGET = 1;
    public static final String TITLE = "每日登录";
    public static final String DESCRIPTION = "登录游戏后即可领取";
    public static final String REWARD_LABEL = "绿苹果 ×" + REWARD_GREEN_APPLES;
    /** 发放去重键前缀，后接 UTC epoch day。 */
    public static final String GRANT_KEY_PREFIX = "habitrain_lottery:daily_login:";

    private static boolean registered;

    private DailyLoginRewardTask() {
    }

    /**
     * 显式启用旧任务的兼容入口，主模组不调用。重复调用是空操作。
     */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        HabiDailyTaskApi.register(new HabiDailyTaskApi.Task(ID, TITLE, DESCRIPTION, TARGET, REWARD_LABEL,
                (player, taskId, epochDayUtc) -> {
                    HabiAssetResult result = HabiLotteryApi.grantGreenApples(
                            player.getUUID(), REWARD_GREEN_APPLES, grantKey(epochDayUtc), true);
                    // DUPLICATE_GRANT 表示这一天已经发过（领取回调重试），按成功处理。
                    return result.ok() || result.failure() == HabiFailure.DUPLICATE_GRANT;
                }));
        registered = true;
    }

    /** 某一 UTC 日的发放去重键。 */
    public static String grantKey(long epochDayUtc) {
        return GRANT_KEY_PREFIX + epochDayUtc;
    }

    /**
     * 玩家登录（或在线跨 UTC 日）时把当日「每日登录」任务推进到可领取状态。
     * 幂等：进度已满或今日已领取时不写盘。
     */
    public static void onLogin(ServerPlayer player) {
        // 可配置任务：所有「登录」类型的每日任务（默认配置里的 daily_login 即旧的 160 绿苹果）。
        com.habitrain.lottery.daily.config.DailyTaskTracker.onLogin(player);
        if (!registered) {
            return;
        }
        if (player == null || HabiDailyTaskApi.claimed(player, ID)
                || HabiDailyTaskApi.progress(player, ID) >= TARGET) {
            return;
        }
        if (!HabiDailyTaskApi.advance(player, ID, TARGET)) {
            HabiLotteryMod.LOGGER.warn("Daily login task could not be advanced for {}",
                    player.getGameProfile().getName());
        }
    }
}
