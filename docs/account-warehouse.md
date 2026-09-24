# 账户仓库（1.1.25）

上游背包覆盖页的「背包」入口打开 `WarehouseScreen`，默认展示系统奖励仓库。它只展示当前账户的金币、抽奖次数、六类角色卡、已拥有皮肤、称号与特殊系统道具，不读取玩家物品栏、末影箱或世界容器。

仓库支持搜索（名称、ID、拼音）、货币/特殊道具/外观筛选、名称/数量排序、滚动和详情抽屉。角色卡页可筛选已拥有/可使用，抽屉中确认消耗；自选卡在同一页面切入可选职业网格，支持职业名称搜索、绑定职业和已占用状态。对局开始后取消未发送的确认动作并关闭用卡详情；服务端仍以原有用卡规则为准。发送后等待库存回执，超时必须重新同步，不显示假成功。

视觉使用烟灰背景、石灰色物品展示区、紧凑横向导航和绿色数量标记。开场约 520ms，分类切换 320ms，详情展开 240ms/收回 180ms，确认反馈 180ms；「动效/静态」可减少动态效果。鼠标滚轮只影响指针所在列表，方向键移动网格焦点，PageUp/PageDown 翻动内容，Tab/Enter 访问操作，Esc 依次返回详情、职业选择和父页。窄屏详情可滚动，头部和确认按钮固定。

## 同步与部署

服务端只使用请求发送者的 UUID 组装快照。快照分块传输，客户端校验请求编号、偏移和总量，完整收齐后替换列表。关闭页面后的回包不会重新打开页面。每块最多 48 条，总量最多 16384 条；存储错误、超时和服务器不支持均显示明确状态。角色卡库存与每日次数继续使用现有协议，不在客户端扣除。

客户端与服务端均需更新至 1.1.25。旧页面 `CardBackpackScreen`、`CardUseMenuScreen`、`RoleSelectScreen` 和 `CardUiStyle` 已移除，职业元数据解析由 `WarehouseRole` 保留。原角色卡插画继续用于新网格。

## 特殊系统道具 API v1

入口：`com.habitrain.lottery.api.player.HabiSystemItemApi`。数量写入原玩家 JSON 的 `systemItems`，与金币及抽数使用相同原子写入/失败回滚机制。旧存档无需迁移。道具是账户计数，不生成 Minecraft 物品。

模组初始化时注册名称、说明、图标和颜色，名称/说明可为翻译键或文本：

```java
var id = ResourceLocation.parse("my_rewards:event_ticket");
HabiSystemItemApi.register(new HabiSystemItemApi.Definition(
    id, "item.my_rewards.event_ticket", "item.my_rewards.event_ticket.description",
    ResourceLocation.parse("minecraft:amethyst_shard"), 0xFFB49ACD));
```

在服务端线程的奖励结算回调中调用：

```java
HabiAssetResult result = HabiSystemItemApi.grant(player.getUUID(), id, 3);
if (!result.ok()) { /* 上层奖励任务保留失败状态并处理重试 */ }
// 使用效果由拥有此道具的奖励系统实现，成功扣除才返回 ok。
HabiAssetResult consumed = HabiSystemItemApi.consume(player.getUUID(), id, 1);
Map<String, Integer> all = HabiSystemItemApi.balances(player.getUUID());
```

grant/consume 拒绝非正参数、溢出、余额不足和不可写存档；成功表示已落盘。一次账户最多存 4096 类特殊道具，数量为非负 int。重复发奖事件的去重由调用者负责，本接口不将不同奖励事件合并。缺少定义时保留余额，仓库以道具 ID 和箱子图标展示，避免卸载扩展后道具消失。

OP 可使用 `/hlt system_item <在线玩家> <命名空间:道具ID> <数量>` 发放，别名 `/habitrain_lottery system_item ...`。该命令同样通过持久化 API，不允许普通玩家改余额。仓库刷新后可看到结果。对于无通用使用效果的特殊道具，详情显示「已存入账户」，不会提供虚假的使用按钮。

开发验证：`./gradlew build`。独立 GUI 客户端检查仅放在 `tools` 中，不打入发布 JAR。

客户端检查命令：`./gradlew -I tools/warehouse-smoke.gradle runClient --gradle-user-home ../.gradle-user-home`。
该检查使用测试账户数据驱动正式 `WarehouseScreen`，通过真实 Minecraft 物品渲染管线输出宽屏、320×240 GUI、详情滚动和职业选择截图到 `build/warehouse-client-run/screenshots/`，并检查不完整/旧请求快照不覆盖列表、方向键/Enter/Esc 操作。它不连接正式服务器，不验证多人服实际发奖与用卡结果。
