# 每日任务 API v1

每日任务终端 `habitrain_lottery:daily_task_board` 展示本模组内置任务与扩展模组注册的任务。上游任务不会复制到列表。

## 内置任务：每日登录

本模组自己注册一条常驻任务 `habitrain_lottery:daily_login`（实现见 `daily/DailyLoginRewardTask`）：

| 字段 | 值 |
|---|---|
| 标题 / 说明 | 每日登录 / 登录游戏后即可领取 |
| 目标 | 1（登录即达标） |
| 奖励 | 绿苹果 ×160（`HabiLotteryApi.grantGreenApples`，去重键 `habitrain_lottery:daily_login:<UTC 日>`） |

玩家登录（或在线跨 UTC 00:00）时，服务端把当日进度推进到 1/1，奖励<b>仍需玩家在面板上点击领取</b>；未领取则当日一直可领，跨日后随任务进度一起重置。

该任务取代了 1.1.28 及以前的两条自动发放：登录白送 4 张阵营卡（杀手 / 平民 / 中立 / 杀手方中立各 1 张）与连续登录自动到账的绿苹果。**现在登录不再自动发放任何角色卡或绿苹果。** 每日阵营卡的使用次数上限（4 次/天）与突破上限卡机制不受影响。

## 注册任务

在服务端初始化阶段注册使用自己命名空间的任务 ID：

```java
HabiDailyTaskApi.register(new HabiDailyTaskApi.Task(
        ResourceLocation.fromNamespaceAndPath("example", "finish_round"),
        "完成一局列车对局",
        "参与并结束一局有效对局",
        1,
        "绿苹果 ×10",
        (player, taskId, epochDayUtc) -> {
            HabiAssetResult result = HabiLotteryApi.grantGreenApples(
                    player.getUUID(), 10,
                    "example:daily:" + taskId + ":" + epochDayUtc,
                    true);
            return result.ok() || result.failure() == HabiFailure.DUPLICATE_GRANT;
        }));
```

最多注册 32 个任务。定义包含 ID、标题、说明、目标值、奖励说明和 `ClaimAction`。`ClaimAction` 只有在奖励已经可靠落盘后才应返回 `true`。

## 进度与领取

- 游戏事件确认后调用 `HabiDailyTaskApi.advance(player, id, amount)`。
- `advance` 会把进度限制在目标值以内，并在写入失败时回滚。
- 玩家点击领取后，服务端先写入待处理标记，再运行 `ClaimAction`；成功后写入已领取标记。
- 回调抛异常或返回 `false` 时会清除待处理标记，任务可重试。
- 每日状态以 UTC 00:00 切换，`progress` 和 `claimed` 只返回当前 UTC 日的数据。
- `HabiDailyTaskApi.open(player)` 可以在不放置方块时打开任务页面。

回调应使用稳定的去重原因。绿苹果可调用 `grantGreenApples(..., true)`；角色卡、皮肤或特殊系统道具等组合奖励应由扩展模组维护自己的幂等记录。跨多个独立存档不能保证单事务原子性。

## 页面与快照

服务端发送绿苹果余额、角色卡总数、任务进度和奖励来源。客户端不自行计算奖励，也不相信客户端提交的余额。任务页面支持任务列表、资产摘要、来源页、筛选、滚动和 UTC 日切倒计时。

快照由 `DailyTaskBoardService` 生成，网络请求和领取都在服务端主线程执行。玩家存档损坏或世界尚未就绪时拒绝写入，不创建全零覆盖文件。

## 依赖声明

```json
{
  "depends": {
    "habitrain_lottery": ">=1.1.27"
  }
}
```

能力 ID 为 `habitrain_lottery_daily_api`，API 类为 `com.habitrain.lottery.api.daily.HabiDailyTaskApi`，版本常量为 `API_VERSION = 1`。
