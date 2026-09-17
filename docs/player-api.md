# 哈比列车抽奖补齐 玩家数据 API v1

自 `habitrain_lottery` **1.1.6** 起，抽奖补齐模组对外开放一套 **玩家资产修改 API**。其他 Fabric 模组可以通过它读写某一个玩家的金币、抽数、皮肤解锁/装备、角色卡、称号、签到等数据，也可以直接投递带奖励的邮件。

- 包名：`com.habitrain.lottery.api.player`
- 入口：`HabiLotteryApi`（另有 `HabiCardApi` / `HabiSkinPlayerApi` / `HabiTitleApi` / `HabiMailApi`）
- `HabiLotteryApi.API_VERSION == 1`
- Fabric `provides`：`habitrain_lottery_player_api`
- 皮肤“注册”（新增皮肤定义、进奖池）请使用另一套 API：见 [skin-api.md](skin-api.md)

---

## 1. 运行要求

- Minecraft 1.21.1、Fabric Loader、Java 21。
- 扩展模组在 `fabric.mod.json` 中声明依赖：

```json
{
  "depends": {
    "habitrain_lottery": ">=1.1.6"
  }
}
```

- 本 API 的读写在 **服务端** 完成；专用服务器只需服务端安装抽奖补齐。扩展模组的这类调用应放在服务端逻辑里（命令、tick、事件回调等）。
- 本地 JAR 编译：

```groovy
modImplementation files("libs/habitrain_lottery-1.1.6.jar")
```

- 该 API 不需要专用入口点（entrypoint）。它没有“注册阶段”，在服务器运行期间随时可以调用。皮肤注册那种需要保证双端顺序的入口点仅存在于 `habitrain_lottery_skins`。

---

## 2. 通用契约

### 2.1 只在服务端主线程调用

所有会修改数据的方法都必须从 **服务端主线程** 调用（命令、`ServerTickEvents`、`ServerPlayConnectionEvents`、你自己的服务端网络包处理等）。存储层虽然使用了并发容器，但和原版 tick、SRE 组件、玩家背包的交互不是线程安全的。

### 2.2 就绪状态

| 方法 | 含义 |
| --- | --- |
| `HabiLotteryApi.isReady()` | 世界存档目录 `{world}/habitrain_lottery/` 已初始化（服务器启动完成）。所有写操作的前置条件。 |
| `HabiLotteryApi.isEconomyActive()` | 本模组已接管当前世界的 SRE 经济（`/hlt` 相关功能完全可用）。 |
| `HabiLotteryApi.server()` | 当前 `MinecraftServer`，未运行时为 `null`。 |

世界尚未就绪时调用写操作会返回 `HabiFailure.NOT_READY`，不会抛异常。

### 2.3 统一返回值

所有写操作返回 **`HabiAssetResult`**（不可变 record）：

| 成员 | 说明 |
| --- | --- |
| `boolean ok()` | true 表示已经 **持久化写入世界 JSON**。 |
| `HabiFailure failure()` | 失败原因；成功时为 `HabiFailure.NONE`。 |
| `int newValue()` | 数值类资产的修改后数值；无数值语义时为 0；部分失败时返回修改前的值。 |
| `String message()` | 失败原因的中文描述，成功时为空串。 |
| `boolean failed()` | `!ok()` 的便捷方法。 |

工厂方法：`HabiAssetResult.success()`、`HabiAssetResult.success(int newValue)`、`HabiAssetResult.fail(HabiFailure)`、`HabiAssetResult.fail(HabiFailure, int currentValue)`。

### 2.4 失败原因 `HabiFailure`

| 枚举 | 含义 |
| --- | --- |
| `NONE` | 无失败。 |
| `NOT_READY` | 世界存档尚未就绪（服务器未启动完成 / 世界卸载中）。 |
| `INVALID_OPERATION` | 操作符为 null（仅支持 `ADD` / `SET`）。 |
| `INVALID_VALUE` | 数值超出该资产的允许范围。 |
| `UNKNOWN_CARD_TYPE` | 未知角色卡类型。 |
| `UNKNOWN_SKIN_TYPE` | 皮肤类型不是 knife / revolver / bat / grenade / hat。 |
| `UNKNOWN_SKIN` | 该皮肤没有在 SRE 注册。 |
| `SKIN_NOT_UNLOCKED` | 玩家尚未拥有该皮肤，不能装备/撤销。 |
| `CORRUPT_STORAGE` | 玩家 JSON 损坏，拒绝覆盖。 |
| `WRITE_FAILED` | 写入失败；内存值已回滚。 |
| `DUPLICATE_GRANT` | `grantDraws` 去重命中。 |
| `NOT_FOUND` | 目标为 null。 |
| `PLAYER_OFFLINE` | 该操作要求玩家在线。 |
| `TITLE_NOT_OWNED` | 玩家未拥有该称号。 |
| `MAILBOX_CORRUPT` | 邮箱 JSON 损坏。 |
| `INTERNAL_ERROR` | 边界处捕获的意外异常。 |

### 2.5 写入语义（回滚）

成功返回代表 **权威世界 JSON 已落盘**。如果落盘失败，内存中的值会被恢复到调用前的快照后再返回 `WRITE_FAILED`，所以扩展模组不会看到“界面成功、重启丢失”的假成功。

如果目标玩家 **在线**，成功修改后会自动同步 SRE 的实时经济 / SRE+CCA 皮肤镜像 / 称号显示，无需调用方额外发包。

### 2.6 在线 / 离线

- 只接受 `java.util.UUID` 的方法对在线、离线玩家都可作用（`ServerPlayer` 重载等价于传入其 UUID）。
- 少数方法 **要求在线**，例如 `HabiCardApi.grantDailyFactionCardBonusUse(ServerPlayer)`（会写 SRE 组件并给提示），离线调用返回 `PLAYER_OFFLINE`。
- 在线玩家的角色卡余额从 SRE `BackpackManager` 读取；离线时从本模组的世界 JSON 读取。两种情况下写入都会同步到世界 JSON。

---

## 3. 总览

| 类 | 负责的资产 |
| --- | --- |
| `HabiLotteryApi` | 抽数、金币、全服在线批量修改、完整快照、强制迁移 |
| `HabiCardApi` | 4 种阵营卡 + 自选卡 + 突破上限卡、每日次数 |
| `HabiSkinPlayerApi` | 单个玩家的皮肤解锁、撤销、装备 |
| `HabiTitleApi` | 单个玩家的称号拥有与装备 |
| `HabiMailApi` | 发送邮件、构造奖励 |

---

## 4. `HabiLotteryApi`

### 4.1 常量与状态

| 成员 | 说明 |
| --- | --- |
| `API_VERSION` | `1` |
| `MOD_ID` | `"habitrain_lottery"` |
| `PROVIDES` | `"habitrain_lottery_player_api"` |
| `isReady()` / `isEconomyActive()` / `server()` | 见 2.2 |

### 4.2 抽数

| 方法 | 说明 |
| --- | --- |
| `int getDraws(UUID)` / `getDraws(ServerPlayer)` | 读取抽数。未就绪时返回 0。 |
| `HabiAssetResult setDraws(UUID, int amount)` | 设为 `amount`（`amount >= 0`）。 |
| `HabiAssetResult addDraws(UUID, int delta)` | 增减 `delta`，结果下界为 0。 |
| `HabiAssetResult grantDraws(UUID, int amount, String reason, boolean dedupe)` | 带原因键的发放。`dedupe=true` 时同一个 `reason` 只生效一次，重复返回 `DUPLICATE_GRANT`；`reason` 为空时使用固定键 `habitrain_api:manual`。`amount == 0` 直接返回当前值。 |

`grantDraws` 不会向玩家发聊天提示（方便扩展模组自行决定文案）。它和内部 `LotteryGrantService` 一样遵守“先消费去重键、写入失败则连同去重键一起回滚”的语义。

### 4.3 金币

| 方法 | 说明 |
| --- | --- |
| `int getCoins(UUID)` / `getCoins(ServerPlayer)` | 读取金币。 |
| `HabiAssetResult setCoins(UUID, int amount)` | 设为 `amount`（`amount >= 0`）。 |
| `HabiAssetResult addCoins(UUID, int delta)` | 增减 `delta`，结果下界为 0。 |
| `setCoins` / `addCoins` 的 `ServerPlayer` 重载 | 同上。 |

### 4.4 全服在线批量修改

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `addCoinsToOnline(int delta)` | 受影响人数 | 给所有在线玩家加金币。 |
| `setCoinsToOnline(int amount)` | 受影响人数 | 所有在线玩家金币设为 `amount`。 |
| `clearCoinsToOnline()` | 受影响人数 | 清空所有在线玩家金币。 |
| `addDrawsToOnline(int delta)` | 受影响人数 | 给所有在线玩家加抽数。 |
| `setDrawsToOnline(int amount)` | 受影响人数 | 所有在线玩家抽数设为 `amount`。 |

这些方法自动 flush 并同步 SRE 镜像；读取失败（存档损坏）的玩家会被跳过。

### 4.5 快照与迁移

| 方法 | 说明 |
| --- | --- |
| `HabiPlayerAssets snapshot(UUID)` / `snapshot(ServerPlayer)` | 一次性读取该玩家全部资产，见第 9 节。永不返回 null。 |
| `void forceMigrate(ServerPlayer)` | 强制把 SRE/CCA 的旧数据迁移进世界 JSON。 |

---

## 5. `HabiCardApi`

角色卡用 `HabiCardKind` 标识：

| 枚举 | id | 类型 |
| --- | --- | --- |
| `CIVILIAN` | `civilian` | SRE 阵营卡 |
| `NEUTRAL` | `neutral` | SRE 阵营卡 |
| `NEUTRAL_FOR_KILLER` | `neutral_for_killer` | SRE 阵营卡 |
| `KILLER` | `killer` | SRE 阵营卡 |
| `SELF_SELECT` | `self_select` | 虚拟卡（自选卡） |
| `LIMIT_BREAK` | `limit_break` | 虚拟卡（突破上限卡） |

辅助方法：`HabiCardKind.parse(String)`、`id()`、`isFactionCard()`、`isVirtualCard()`、`ordered()`。

### 5.1 余额

| 方法 | 说明 |
| --- | --- |
| `Map<String, Integer> all(UUID)` | 固定顺序的 6 个键（civilian, neutral, neutral_for_killer, killer, self_select, limit_break），缺省为 0。 |
| `int get(UUID, HabiCardKind)` | 单个余额。 |
| `HabiAssetResult add(UUID, HabiCardKind, int delta)` | 增量，`delta ∈ [-1000, 1000]` 且非 0。 |
| `HabiAssetResult set(UUID, HabiCardKind, int amount)` | 绝对赋值，`amount ∈ [0, 100000]`。 |
| `add` / `set` 的 `ServerPlayer` 重载 | 同上。 |

约束与游戏内管理界面完全一致（复用 `PlayerCardMutationPolicy`）：超出范围返回 `INVALID_VALUE`；在线玩家写入后会刷新背包并重发同步包。

### 5.2 每日次数

| 方法 | 说明 |
| --- | --- |
| `int dailyFactionCardRemaining(UUID)` | 今日剩余阵营卡使用次数（基础 4 次 + 突破上限卡加成 - 已用）。只读，不产生副作用。 |
| `int dailySelfSelectRemaining(UUID)` | 今日剩余自选卡使用次数（基础 4 次）。 |
| `HabiAssetResult grantDailyFactionCardBonusUse(ServerPlayer)` | 今日 +1 次阵营卡额度（等价于使用 1 张突破上限卡）。**要求在线**。 |
| `HabiAssetResult revokeDailyFactionCardBonusUse(ServerPlayer)` | 撤销一次加成。**要求在线**。 |
| `HabiAssetResult resetDailyFactionCardUsage(UUID)` | 把今日阵营卡使用次数与加成清零。 |
| `HabiAssetResult resetDailySelfSelectUsage(UUID)` | 把今日自选卡使用次数清零。 |

常量：`DAILY_FACTION_CARD_LIMIT == 4`、`DAILY_SELF_SELECT_LIMIT == 4`、`MAX_COUNT == 100000`、`MAX_DELTA == 1000`。

> 每日次数以 **UTC 日切** 为准，与签到系统的日界一致。

---

## 6. `HabiSkinPlayerApi`

这里处理的是“某个玩家拥有/装备了哪些皮肤”；注册新皮肤请看 [skin-api.md](skin-api.md)。

支持的皮肤类型（`SkinDefinition.SUPPORTED_TYPES`）：`knife`、`revolver`、`bat`、`grenade`、`hat`。类型 `gun` 会被规范化为 `revolver`；带命名空间的写法如 `starrailexpress:knife` 也会被规范化。皮肤 id 一律转小写。

| 方法 | 说明 |
| --- | --- |
| `boolean isRegistered(String type, String skin)` | 该 `type/id` 是否已在 SRE 注册（`default` 恒为 true）。 |
| `boolean isUnlocked(UUID, String type, String skin)` | 玩家是否拥有该皮肤。`default` 恒为 true。 |
| `List<String> unlocked(UUID, String type)` | 某类型已拥有皮肤 id（升序，不含 default）。 |
| `Map<String, List<String>> unlockedAll(UUID)` | 全部类型 → 已拥有皮肤。 |
| `String equipped(UUID, String type)` | 当前装备的皮肤 id；未装备返回 `"default"`。 |
| `Map<String, String> equippedAll(UUID)` | 全部非 default 的装备项。 |
| `HabiAssetResult unlock(UUID, String type, String skin)` | 解锁（赠送）。要求皮肤已注册，否则 `UNKNOWN_SKIN`；`default` 返回 `INVALID_VALUE`。 |
| `HabiAssetResult lock(UUID, String type, String skin)` | 撤销解锁，并同时从装备槽移除。允许撤销已卸载的扩展皮肤。 |
| `HabiAssetResult equip(UUID, String type, String skin)` | 装备一个 **已拥有** 的皮肤；未拥有返回 `SKIN_NOT_UNLOCKED`。 |
| `HabiAssetResult clearEquipped(UUID, String type)` | 卸下该类型皮肤（等价于 `equip(..., "default")`）。 |
| `unlock/lock/equip` 的 `ServerPlayer` 重载 | 同上。 |

语义要点：

- `unlock` / `lock` 走的是和 `/hlt skins unlock|lock` 完全相同的 **事务提交**（`PlayerLotteryStore.commitSkinAccess`），世界 JSON 是唯一权威；成功后在线玩家会立刻收到 SRE/CCA 同步。
- `equip` 对在线玩家调用 `SkinStateCoordinator`，会同步 SRE、CCA 并应用到背包里的物品；对离线玩家只写世界 JSON，玩家上线时按权威存档重建。`equip` 失败时在线路径会向玩家发一条失败提示。
- 修改皮肤状态前应确保 `HabiLotteryApi.isReady()`。

---

## 7. `HabiTitleApi`

称号是玩家拥有的显示文本（`String`），保存在 `titles/players/<uuid>.json` 并镜像到 SRE 的 `NameTagInventoryComponent`。

| 方法 | 说明 |
| --- | --- |
| `List<String> owned(UUID)` | 已拥有称号，按存档顺序；存档损坏时返回空列表。 |
| `String current(UUID)` | 当前佩戴称号，未佩戴为 `""`。 |
| `HabiAssetResult grant(UUID, String display)` | 授予称号，幂等（已拥有返回成功）。 |
| `HabiAssetResult revoke(UUID, String display)` | 撤销称号，幂等（未拥有返回成功）；若正在佩戴会一并清空。 |
| `HabiAssetResult setCurrent(UUID, String display)` | 佩戴已拥有的称号；未拥有返回 `TITLE_NOT_OWNED`。传入空白字符串表示卸下。 |
| `HabiAssetResult clear(UUID)` | 清空该玩家全部称号与佩戴项。 |
| `grant/revoke/setCurrent/clear` 的 `ServerPlayer` 重载 | 同上。 |

在线玩家改称号会同步组件、刷新头顶与 Tab 列表显示（与游戏内 `/nametag` 行为一致）。

---

## 8. `HabiMailApi`

邮件是给“可能离线”的玩家发奖励的推荐方式：奖励先落盘到邮箱 JSON，玩家领取时 **恰好结算一次**。

### 8.1 投递

| 方法 | 说明 |
| --- | --- |
| `boolean send(UUID target, MailDraft draft)` | 投递一封邮件；在线玩家会收到聊天提醒。 |
| `boolean send(UUID target, String nameHint, MailDraft draft)` | 同上，`nameHint` 仅用于离线日志。 |
| `boolean send(ServerPlayer target, MailDraft draft)` | 在线重载。 |

返回 `false` 表示未写入（世界未就绪、邮箱损坏、写盘失败）。

### 8.2 构造

| 方法 | 说明 |
| --- | --- |
| `MailDraft draft(String sender, String title, String content, List<MailReward> rewards)` | 永不过期。 |
| `MailDraft draft(String sender, String title, String content, long expiresAt, List<MailReward> rewards)` | `expiresAt` 是 epoch 毫秒；`0`（`NEVER_EXPIRES`）表示永不过期。 |
| `MailDraft draws(String sender, String title, String content, int amount)` | 单奖励：抽数。 |
| `MailDraft coins(String sender, String title, String content, int amount)` | 单奖励：金币。 |
| `MailReward draws(int)` / `coins(int)` | 奖励项。 |
| `MailReward factionCard(HabiCardKind kind, int amount)` | 阵营卡奖励；只接受 4 种真实阵营卡，虚拟卡返回 `null`。 |
| `MailReward selfSelectCard(int)` | 自选卡奖励。 |
| `MailReward limitBreakCard(int)` | 突破上限卡奖励。 |

`draft(...)` 会自动丢弃 `null` / 0 数量的奖励，把数量裁剪到 `[-100000, 100000]`，并把单封邮件奖励数限制为最多 32 项（与邮件撰写界面 `MailComposeLimits` 一致）。

### 8.3 领取

领取仍由玩家在游戏内邮箱界面或命令触发（`MailService.claim` / `claimAll`）。API **只负责投递**，不提供跳过玩家态检查的强制领取：领取会校验旁观/休息/死亡状态，且局中会拒发阵营卡类奖励。

---

## 9. 快照 `HabiPlayerAssets`

`HabiLotteryApi.snapshot(UUID)` 返回一次性聚合视图（record，全部字段只读）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `uuid` | `UUID` | 玩家。 |
| `name` | `String` | 在线时的玩家名；离线为空串。 |
| `online` | `boolean` | 当前是否在线。 |
| `coins` | `int` | 金币。 |
| `draws` | `int` | 抽数。 |
| `factionCards` | `Map<String,Integer>` | 6 种卡（含虚拟卡）余额。 |
| `selfSelectCards` | `int` | 自选卡数量（`factionCards` 的便捷副本）。 |
| `limitBreakCards` | `int` | 突破上限卡数量。 |
| `unlockedSkins` | `Map<String,List<String>>` | 类型 → 已拥有皮肤（升序）。 |
| `equippedSkins` | `Map<String,String>` | 类型 → 装备皮肤（仅非 default）。 |
| `titles` | `List<String>` | 已拥有称号。 |
| `currentTitle` | `String` | 当前佩戴称号。 |
| `loginStreak` | `int` | 连续登录天数。 |
| `lastLoginEpochDay` | `long` | 上次签到结算的 UTC 纪元日；`-1` 表示从未。 |

便捷方法：`card(HabiCardKind)`、`skins(String type)`、`equipped(String type)`。

---

## 10. 数值边界速查

| 资产 | SET 允许范围 | ADD 允许范围 | 结果钳制 |
| --- | --- | --- | --- |
| 抽数 / 金币 | `>= 0` | 任意 `int`（0 视为无操作返回当前值） | `[0, Integer.MAX_VALUE]` |
| 角色卡（`set` / `add`） | `[0, 100000]` | `[-1000, 1000]` 且非 0 | `[0, 100000]` |
| 邮件单项奖励数量 | — | — | `[-100000, 100000]` / 单封最多 32 项 |

这些边界与游戏内管理界面一致，可通过 `HabiAssetPolicy` 的常量与方法在扩展模组侧预先校验：

- `HabiAssetPolicy.MAX_CURRENCY`、`MAX_CARD_COUNT`、`MAX_CARD_DELTA`
- `isValidCurrencyOperation(op, value)`、`applyCurrency(current, op, value)`
- `isValidCardOperation(op, value)`、`applyCardCount(current, op, value)`
- `dailyRemaining(usedToday, bonusToday, dailyLimit)`

---

## 11. 示例

### 11.1 发放金币与抽数

```java
import com.habitrain.lottery.api.player.HabiAssetResult;
import com.habitrain.lottery.api.player.HabiLotteryApi;

HabiAssetResult coins = HabiLotteryApi.addCoins(player.getUUID(), 500);
if (coins.failed()) {
    // coins.message() 为中文失败原因
    return;
}
HabiLotteryApi.grantDraws(player.getUUID(), 3, "example:event:2026-spring", true);
```

### 11.2 用邮件给离线玩家发奖励

```java
import com.habitrain.lottery.api.player.HabiCardKind;
import com.habitrain.lottery.api.player.HabiMailApi;
import java.util.List;

boolean sent = HabiMailApi.send(targetUuid, "补偿", HabiMailApi.draft(
        "活动系统",
        "春节补偿",
        "感谢游玩，奖励已到账。",
        List.of(
                HabiMailApi.coins(1000),
                HabiMailApi.draws(5),
                HabiMailApi.factionCard(HabiCardKind.KILLER, 2),
                HabiMailApi.selfSelectCard(1))));
```

### 11.3 解锁并装备皮肤

```java
import com.habitrain.lottery.api.player.HabiFailure;
import com.habitrain.lottery.api.player.HabiSkinPlayerApi;

if (HabiSkinPlayerApi.unlock(uuid, "knife", "example_crystal_blade").ok()) {
    HabiAssetResult equipped = HabiSkinPlayerApi.equip(uuid, "knife", "example_crystal_blade");
    if (equipped.failure() == HabiFailure.SKIN_NOT_UNLOCKED) {
        // 理论上不会发生；仅演示失败分支
    }
}
```

### 11.4 修改角色卡

```java
import com.habitrain.lottery.api.player.HabiCardApi;
import com.habitrain.lottery.api.player.HabiCardKind;

HabiCardApi.add(uuid, HabiCardKind.CIVILIAN, 1);
HabiCardApi.set(uuid, HabiCardKind.SELF_SELECT, 3);
int today = HabiCardApi.dailyFactionCardRemaining(uuid);
```

### 11.5 查询完整资产

```java
import com.habitrain.lottery.api.player.HabiPlayerAssets;
import com.habitrain.lottery.api.player.HabiLotteryApi;

HabiPlayerAssets assets = HabiLotteryApi.snapshot(uuid);
int coins = assets.coins();
int draws = assets.draws();
String knife = assets.equipped("knife");
```

### 11.6 全服活动奖励

```java
int touched = HabiLotteryApi.addDrawsToOnline(2);
if (touched == 0) {
    // 没有在线玩家或世界未就绪
}
```

---

## 12. 没有纳入本 API 的部分

以下内容有意 **不** 通过玩家数据 API 暴露：

| 内容 | 原因 / 替代方案 |
| --- | --- |
| 奖池、倍率、品质、发次、画面等全局配置 | 这些是 **世界级配置**（`pools.json`、`LotteryConfigService`），不是“玩家数量”。请使用 Mod Menu 配置页或 `/hlt reload`，API 不会绕过配置校验写全局文件。 |
| 新增皮肤定义、把皮肤加入奖池 | 使用皮肤注册 API：`HabiSkinApi`（见 [skin-api.md](skin-api.md)）。 |
| 对局历史 / 抽奖流水 | `LotteryHistoryStore`、`LocalMatchRecordStore` 只做追加写入，没有读取接口；本 API 不导出内部文件格式。 |
| 邮件领取 | 领取包含玩家态检查与一次性事务，必须由玩家本人触发，避免外部模组绕过局中限制重复发放。 |
| 经济接管开关、迁移开关 | 属于世界生命周期状态，由本模组在 SERVER_STARTED/STOPPING 管理。 |
| 皮肤注册表本身的增删 | `HabiSkinApi` 只允许幂等注册；删除皮肤定义不是安全操作。 |

---

## 13. 版本与兼容

- 当前 `HabiLotteryApi.API_VERSION == 1`。
- 只要语义变化（例如某个方法的钳制范围、失败原因含义）就会递增该常量；扩展模组可在启动时读取并做兼容分支。
- 新增方法（不改旧行为）同样会递增该常量，但旧方法保持可用。
- `fabric.mod.json` 的 `custom` 段提供机器可读的版本信息：

```json
"habitrain_lottery:player_api": {
  "version": 1,
  "facade": "com.habitrain.lottery.api.player.HabiLotteryApi"
}
```
