# 玩家资产 API v2

`com.habitrain.lottery.api.player` 提供服务端玩家资产读写。当前货币只有绿苹果；角色卡、皮肤、称号、邮件和特殊系统道具是独立资产。

## 接入

在扩展模组的 `fabric.mod.json` 中声明：

```json
{
  "depends": { "habitrain_lottery": ">=1.1.27" }
}
```

API 版本常量为 `HabiLotteryApi.API_VERSION == 2`，能力 ID 为 `habitrain_lottery_player_api`。所有写操作都必须在服务端主线程执行。

## 就绪与结果

- `HabiLotteryApi.isReady()`：世界账户目录已经初始化。
- `HabiLotteryApi.isPlayerStoreActive()`：玩家账户存储已经初始化并可写。
- `HabiLotteryApi.server()`：当前服务器，未运行时为 `null`。

写方法返回 `HabiAssetResult`。`ok()` 表示权威 JSON 已成功落盘；失败时内存快照会恢复，`failure()` 提供稳定的 `HabiFailure` 枚举值。常见失败值有 `NOT_READY`、`INVALID_VALUE`、`CORRUPT_STORAGE`、`WRITE_FAILED` 和 `DUPLICATE_GRANT`。

## 绿苹果

```java
UUID uuid = player.getUUID();

int balance = HabiLotteryApi.getGreenApples(uuid);
HabiAssetResult added = HabiLotteryApi.addGreenApples(uuid, 25);
HabiAssetResult set = HabiLotteryApi.setGreenApples(uuid, 100);

// reason 在 dedupe=true 时作为持久化去重键。
HabiAssetResult once = HabiLotteryApi.grantGreenApples(
        uuid, 10, "example:welcome:2026", true);
```

`addGreenApples` 允许正负增量并把结果限制在 `0..Integer.MAX_VALUE`；`setGreenApples` 只接受非负值。`addGreenApplesToOnline`、`setGreenApplesToOnline` 和 `clearGreenApplesForOnline` 用于当前在线玩家。

旧金币和旧抽数字段不会被这些方法读取，也不会自动折算成绿苹果。

## 角色卡

`HabiCardApi` 支持 `HabiCardKind` 的六类余额：`CIVILIAN`、`NEUTRAL`、`NEUTRAL_FOR_KILLER`、`KILLER`、`SELF_SELECT` 和 `LIMIT_BREAK`。

```java
int count = HabiCardApi.get(uuid, HabiCardKind.SELF_SELECT);
HabiAssetResult result = HabiCardApi.add(uuid, HabiCardKind.SELF_SELECT, 1);
Map<String, Integer> all = HabiCardApi.all(uuid);
```

四类阵营卡与上游角色分配同步；自选卡和突破上限卡只保存在本模组账户仓库。每日次数和额外次数使用 `dailyFactionCardRemaining`、`dailySelfSelectRemaining` 及对应的 bonus/reset 方法。

## 皮肤与称号

- `HabiSkinPlayerApi.isRegistered/isUnlocked/unlocked/unlockedAll/equipped/equippedAll` 用于查询。
- `HabiSkinPlayerApi.unlock/lock/equip/clearEquipped` 用于服务端写入。
- `HabiTitleApi.owned/current/grant/revoke/setCurrent/clear` 管理称号。

皮肤必须先由 `habitrain_lottery_skins` 入口点注册。皮肤 API 不包含随机奖励、奖池或兑换逻辑。

## 邮件

邮件附件使用 `HabiMailApi` 构造：

```java
MailDraft draft = HabiMailApi.draft(
        "系统", "欢迎", "请查收",
        List.of(HabiMailApi.greenApples(20),
                HabiMailApi.selfSelectCard(1)));
HabiMailApi.send(player.getUUID(), player.getGameProfile().getName(), draft);
```

可用附件为绿苹果、四类阵营卡、自选卡、突破上限卡和已注册皮肤。邮件编码器会忽略旧的已退休货币附件，不会把它们转换成绿苹果；同一封邮件中的其他有效附件仍按事务领取。

箱子和钥匙附件也从统一的 `CrateCatalog` 清单读取：

```java
List.of(HabiMailApi.crate("cobalt", 2), HabiMailApi.key("cobalt", 2));
```

邮件领取时会把它们写入账户仓库的系统道具余额；清单中的所有箱子和专用钥匙都能被在线、离线和全员邮件自动识别。

## 快照

`HabiLotteryApi.snapshot(UUID)` 返回不可变的 `HabiPlayerAssets`，字段包括：

- `greenApples`
- `factionCards`、`selfSelectCards`、`limitBreakCards`
- `unlockedSkins`、`equippedSkins`
- `titles`、`currentTitle`
- `loginStreak`、`lastLoginEpochDay`

快照在世界未就绪时返回全零的安全值，不会创建或覆盖损坏的存档。

## 存储与线程

玩家账户位于 `<world>/habitrain_lottery/players/<uuid>.json`。邮件和角色卡使用同一世界根目录下的独立存储。读取损坏文件会返回失败并拒绝覆盖；扩展模组应在收到 `WRITE_FAILED` 后保留自己的重试状态。
