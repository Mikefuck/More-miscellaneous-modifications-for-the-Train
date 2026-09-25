# 账户仓库

上游背包入口可以打开本模组的 `WarehouseScreen`。仓库展示账户奖励，不读取玩家物品栏、末影箱或世界容器。

当前条目包括：

- 绿苹果账户余额；
- 四类阵营卡、自选卡和突破上限卡；
- 已拥有的独立皮肤与当前装备状态；
- 已拥有称号；
- 扩展模组注册的特殊系统道具。

服务端按请求玩家的 UUID 组装快照，客户端按请求编号和分块偏移收齐后替换列表。单块最多 48 项，总量最多 16384 项。存储错误、超时和不支持状态都会显示为错误，不会伪装成空仓库。

## 特殊系统道具 API v1

入口类：`com.habitrain.lottery.api.player.HabiSystemItemApi`。道具余额写入玩家 JSON 的 `systemItems`，是账户计数，不会生成 Minecraft 物品。

```java
ResourceLocation id = ResourceLocation.parse("example:event_ticket");
HabiSystemItemApi.register(new HabiSystemItemApi.Definition(
        id,
        "item.example.event_ticket",
        "item.example.event_ticket.description",
        ResourceLocation.parse("minecraft:amethyst_shard"),
        0xFFB49ACD));

HabiAssetResult granted = HabiSystemItemApi.grant(player.getUUID(), id, 3);
HabiAssetResult consumed = HabiSystemItemApi.consume(player.getUUID(), id, 1);
Map<String, Integer> balances = HabiSystemItemApi.balances(player.getUUID());
```

所有操作必须在服务端线程执行。数量不能为负，余额溢出、余额不足、未知定义和损坏存档都会失败；成功表示已落盘。最多保存 4096 类道具。扩展卸载后余额仍保留，仓库会使用道具 ID 和箱子图标作为回退显示。

管理员可以使用 `/hlt system_item <在线玩家> <命名空间:道具ID> <数量>` 发放道具。普通玩家不能修改账户条目。
