package com.habitrain.lottery.daily;

import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 内置「每日登录」任务的注册契约：登录即可领取 160 绿苹果（不再是角色卡）。 */
class DailyLoginRewardTaskTest {

    @Test
    void registersBuiltInLoginTask() {
        DailyLoginRewardTask.register();

        HabiDailyTaskApi.Task task = HabiDailyTaskApi.tasks().get(DailyLoginRewardTask.ID);
        assertNotNull(task, "内置每日登录任务必须已注册");
        assertEquals(DailyLoginRewardTask.ID, task.id());
        assertEquals("每日登录", task.title());
        // 登录即可达成，目标固定为 1。
        assertEquals(1, task.target());
        assertEquals(160, DailyLoginRewardTask.REWARD_GREEN_APPLES);
        assertTrue(task.rewardLabel().contains("160"), "奖励说明必须写明 160 绿苹果");
        assertTrue(task.rewardLabel().contains("绿苹果"), "奖励不再是角色卡");
    }

    @Test
    void registrationIsIdempotent() {
        assertDoesNotThrow(DailyLoginRewardTask::register);
        assertDoesNotThrow(DailyLoginRewardTask::register);
        assertEquals(1, HabiDailyTaskApi.tasks().values().stream()
                .filter(task -> task.id().equals(DailyLoginRewardTask.ID))
                .count(), "重复调用不得注册第二条同名任务");
    }

    @Test
    void grantKeyIsStableWithinOneUtcDay() {
        assertEquals(DailyLoginRewardTask.grantKey(20_000L), DailyLoginRewardTask.grantKey(20_000L));
        assertNotEquals(DailyLoginRewardTask.grantKey(20_000L), DailyLoginRewardTask.grantKey(20_001L));
        assertTrue(DailyLoginRewardTask.grantKey(20_000L).startsWith(DailyLoginRewardTask.GRANT_KEY_PREFIX));
    }
}
