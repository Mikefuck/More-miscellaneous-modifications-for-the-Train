## 1.1.23 页面更新

每日任务使用顶部导航、横向摘要与全宽列表。薄荷绿表示可领取操作；每行依次显示任务、说明、奖励和进度。默认按可领取、进行中、已领取排列。筛选为全部、待领取、已领取。摘要中的资产为当前余额，不是任务额外奖励。接口与服务端结算规则不变。

# 每日任务终端与接入 API（1.1.22）

## 玩家使用

创造模式的「功能方块」里取出 **每日任务终端**（`habitrain_lottery:daily_task_board`），放置后右键打开。页面是参考样本复刻的**书页跨版**，浅色纸张主题（窗口只有 1px 描边和一层柔和投影，边框上没有装饰）：

- **左侧导航**：三张分页卡 —— `委托`（默认）/ `资产` / `来源`，选中项用主色左条标出；快捷键 `Q` / `T` / `C`。
- **左页**：标题、规则说明（按页宽折行）、进度环（环内是「已领取 / 总数」）、下面每项任务一枚菱形任务点，
  「额外奖励」是三枚带物品图标的方块（抽数 / 金币 / 角色卡，悬停显示来源说明），最底部是 UTC 日期与「距刷新 hh:mm:ss」。
- **右页工具栏**：任务计数（`4 项 · 1 项待领取`）与三段筛选 `全部 / 待领取 / 已完成`；快捷键 `F` 依次切换。
- **右页任务行**：状态方块 → 任务名 → 说明 → 奖励胶囊 → 领取按钮，进度条贴在卡片下沿，计数在进度带右上方。
  可领取为琥珀色实心按钮、已领取绿色、进行中灰禁用、已提交蓝色「领取中…」。列表超出高度时滚动（滚轮 / PageUp / PageDown），滚动到一半的行会被裁掉，不会画到列表外面。
- **右页脚注「奖励发放」**：整行都是「来源」页的入口（对应参考样本里的「额外奖励领取地点」）。
- **资产页**：六张角色卡的当前余额与用途；**来源页**：按 抽数 / 金币 / 角色卡 分类的已核对规则清单，行悬停显示完整说明。
- 关闭：右上角 ✕ 或 `Esc`；`R` 手动刷新。

窄窗口（面板宽 < 430 逻辑像素）会自动进入紧凑档：左页只保留进度环与倒计时，宽度让给任务列表，
奖励块与说明按「放不下就省略」处理，不会互相压住。

当前没有内置任务，因此任务区显示空状态；其他模组注册任务后自动出现。

任务、签到和进度的日切都采用 UTC 00:00。打开页面和每五秒刷新一次均从服务端取快照；任务进度与领取只能由服务端修改。点击「领取」后按钮立刻进入「领取中…」并冻结该任务的重复点击，直到服务端快照回来（失败时服务端会补发一次快照；超过 6 秒无响应则自动解锁）。文案全部走语言键 `screen.habitrain_lottery.daily.*`（`zh_cn` / `en_us` 均已补齐）。

版面几何集中在 `client/gui/DailyBoardLayout`（无状态纯函数，绘制与命中测试读同一份结果），`DailyBoardLayoutTest` 会在 320×240 → 1920×1080、0～32 项任务上校验「不出屏、两页不重叠、书脊夹在两页之间、筛选段不压关闭按钮、放不下的构件被省略」；配色与基础绘制件在 `client/gui/DailyBoardTheme`。

## 其他模组接入

在 Fabric 模组的 `onInitialize` 中注册任务；任务 ID 要使用**自己模组的命名空间**。注册顺序即页面展示顺序，不需修改本模组资源：

```java
HabiDailyTaskApi.register(new HabiDailyTaskApi.Task(
    ResourceLocation.fromNamespaceAndPath("your_mod", "finish_round"),
    "完成一局列车对局", "参与并结束一局有效对局", 1, "抽数 ×1",
    (player, taskId, epochDayUtc) -> {
        HabiAssetResult result = HabiLotteryApi.grantDraws(player.getUUID(), 1,
            "your_mod:daily:" + taskId + ":" + epochDayUtc, true);
        return result.ok() || result.failure() == HabiFailure.DUPLICATE_GRANT;
    }
));
```

在服务端确认事件后调用 `HabiDailyTaskApi.advance(player, id, 1)`。进度达到 `target` 后页面会显示「领取」按钮；点击会由服务端验证并运行 `ClaimAction`。`advance` 返回 `true` 表示进度已落盘，返回 `false` 表示未更新。模组也可以调用 `HabiDailyTaskApi.open(player)` 直接打开页面。

`ClaimAction` 必须对玩家、任务和 UTC 日期组成的键实现**幂等发放**。终端会先持久化「待发放」标记，再调用回调；若服务中断，下次点击可重试同一键。回调成功后才写入「已领取」。回调返回 `false` 或抛出运行异常时会尝试清除待发放标记。跨多个存档的奖励无法保证原子性。抽数请用 `HabiLotteryApi.grantDraws(..., true)` 和稳定的 `reason`；金币、角色卡等组合奖励建议由接入模组维护自己的幂等发放记录。回调要在奖励确实落盘后才返回 `true`。此接口仅在服务端主线程调用。

任务列表完全由注册方提供；本版本不自动把上游通行证任务搬入每日任务列表。`fabric.mod.json` 可依赖 `habitrain_lottery >=1.1.19`（每日任务 API 自 1.1.19 起可用，1.1.20 起的三次改版都只动页面），或依赖 `habitrain_lottery_daily_api` 能力 ID。API 类：`com.habitrain.lottery.api.daily.HabiDailyTaskApi`，`API_VERSION = 1`。

## 资产发放路径审计

页面显示的是**当前余额**。旧版没有完整的、按来源记录的历史流水，已发又花掉的金额无法可靠回推。对发放途径的核对如下，页面「发放来源」也按这份清单展示：

| 资产 | 发放入口 | 实际规则 / 存储关系 |
| --- | --- | --- |
| 抽数 | 本模组签到 | `LoginRewardService` 连续登录第 N 天发 `min(N, loginRewardCap)`，只结算一次；在线跨 UTC 日也会补发。 |
| 抽数 | 本模组对局奖励 | `GrantEventHooks` → `LotteryGrantService.grantEvent`；默认乘客胜 +1、杀手胜 +2、中立胜 +5、停电参与 +1、常规或维修参与 +1，实际乘模式倍率。`task_complete` 与 `level_every_n` 默认关闭。 |
| 抽数 | 金币兑换 | `CoinToDrawService` 以配置中的 `coinPerDraw` 价格将金币换成抽数。 |
| 抽数 | 上游等级 / 通行证任务 | `ProgressionDataManager` 每五级 +1；`SREPlayerProgressionComponent` 等级与任务奖励也可能加抽，受上游配置和具体任务奖励控制。它们经 `PlayerEconomyManager` / `ItemSkinManager` 进入本模组权威余额。 |
| 抽数 | 邮件 / 管理员 / API | `MailService` 领取附件，`LotteryNetwork` 的管理员操作及 `HabiLotteryApi` 可调整或发放。抽奖失败退款也可能使余额增加，但属于返还，不应算新奖励。 |
| 金币 | 抽奖 | `SkinLotteryRewards` 的金币项与重复皮肤返还；上游 `LotteryManager` 也有金币项和重复皮肤转币。金额取决于品质、奖池与倍率。 |
| 金币 | 上游对局 / 等级 / 任务 | `ProgressionDataManager` 胜局 +20，升级奖励 `20 + 等级×2`；`SREPlayerProgressionComponent` 的通行证任务和等级也有奖励，具体任务可变。 |
| 金币 | 上游职业 / 死亡事件 | 术士、亡灵领主、操纵者的特定能力，以及控制目标死亡事件可发金币；数量由上游配置与游戏条件决定。 |
| 金币 | 邮件 / 管理员 / API | `MailService` 领取附件、金币管理指令和 `HabiLotteryApi`。购买抽数和部分上游商店操作是支出，不算发放。 |
| 角色卡 | 本模组每日登录 | `DailyFactionCardService` 每日四阵营卡各 1 张；首次进服和跨 UTC 日均有结算，背包与本模组存档同步。 |
| 角色卡 | 上游背包 / 退款 | 上游 `/backpack` 可直接调整阵营卡，`BackpackManagerMixin` 会保存到本模组 JSON；强制分配失败退还已经消耗的阵营卡。 |
| 角色卡 | 邮件 / 管理员 / API | `MailService`、`PlayerCardAdminService`、`HabiCardApi` 支持阵营卡、自选卡和突破上限卡。 |
| 角色卡 | 上游旧进度任务 / 等级 | `SREPlayerProgressionComponent` 任务卡与 3/5/7 级卡写旧 CCA 进度字段，**未自动计入本页面的背包余额**；`ProgressionDataManager.addFactionCard` 的现有调用点是分配失败退款，不是新的正向发卡。 |

具体代码入口分别位于本模组的 `grant/`、`backpack/`、`mail/`、`network/`、`skin/`、`api/player/` 和上游 DLC 的 `progression/`、`cca/`、`backpack/`、`noellesroles/`。上游职业规则或任务配置变动后，应重新核对该清单。
