# 大厅元功能本地化桥接 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `habitrain_lottery` 中用 world JSON 权威 + mixin 桥接，修好并对管理员开放：对局记录、地图介绍、地图轮换（跳转哈比 API 投票设置）、邮箱（完整发信 GUI + 抽数/金币/阵营卡奖励）。

**Architecture:** 不重写 SRE 玩家 UI。邮件/战绩/背包的 MySQL 读写被旁路到 `{world}/habitrain_lottery/{mail,records,backpack}/`；大厅「地图轮换」客户端改跳 `habitrain_core` 配置中心投票 Tab；发信与领取走本 mod 服务 + 结构化奖励，并复用 `MailboxScreen` / `MatchRecordsScreen`。

**Tech Stack:** Fabric 1.21.1, Java 21, Mixin, Fabric Networking, Mod Menu, soft-depend `habitrain_core`, hard-depend `starrailexpress` 4.3.0 jar in `libs/`.

## Global Constraints

- 工作区：`D:\Backup\mc mod\哈比列车抽奖补齐`；禁止访问 `D:\Backup\mc mod\backup\`
- 称呼用户为 Mike
- 每次改完必须 `gradlew.bat clean build`，JAR 复制到 `D:\Backup\mc mod\临时\`（assemble 已有 copyReleaseJar）
- 正式 design/spec 路径：`docs/superpowers/specs/2026-07-17-hub-meta-features-local-bridge-design.md`（Task 0 写入）
- 正式 plan 路径：`docs/superpowers/plans/2026-07-17-hub-meta-features-local-bridge-plan.md`（Task 0 同步本文件）
- 首版不做物品附件；离线邮件要做；阵营卡始终本地权威
- `habitrain_core` 为 optional；无 core 时地图轮换提示 + fallback 原 `MapRotationScreen`
- 沿用现有代码风格：Gson pretty、world 路径经 `WorldLotteryPaths`、OP level 默认 2

---

## File Structure

### Create in `habitrain_lottery`

| Path | Responsibility |
|------|----------------|
| `src/main/java/com/habitrain/lottery/storage/MetaFeaturePaths.java` | mail/records/backpack 子路径 |
| `src/main/java/com/habitrain/lottery/mail/MailReward.java` | 结构化奖励模型 |
| `src/main/java/com/habitrain/lottery/mail/MailDraft.java` | 发信草稿 |
| `src/main/java/com/habitrain/lottery/mail/LocalMailboxStore.java` | 每玩家邮件 JSON 读写 |
| `src/main/java/com/habitrain/lottery/mail/MailService.java` | send / claim 结构化奖励 |
| `src/main/java/com/habitrain/lottery/mail/MailCommandsCodec.java` | 结构化奖励 ↔ claimCommands 前缀编码 |
| `src/main/java/com/habitrain/lottery/network/MailComposeC2SPayload.java` | OP 发信包 |
| `src/main/java/com/habitrain/lottery/client/gui/MailComposeScreen.java` | OP 发信 GUI |
| `src/main/java/com/habitrain/lottery/record/LocalMatchRecordStore.java` | 战绩本地 IO |
| `src/main/java/com/habitrain/lottery/backpack/LocalBackpackStore.java` | 阵营卡本地 IO |
| `src/main/java/com/habitrain/lottery/meta/HabiCoreMenuBridge.java` | 打开 core 投票配置（反射，避免硬依赖） |
| `src/main/java/com/habitrain/lottery/mixin/MailboxMysqlBypassMixin.java` | 邮箱 DB 旁路 |
| `src/main/java/com/habitrain/lottery/mixin/MailboxClaimMixin.java` | 领取时发结构化奖励 |
| `src/main/java/com/habitrain/lottery/mixin/MatchRecordStoreMixin.java` | 战绩 store 旁路 |
| `src/main/java/com/habitrain/lottery/mixin/MatchRecordServiceMixin.java` | 去掉 stats 开关硬依赖 |
| `src/main/java/com/habitrain/lottery/mixin/BackpackManagerMixin.java` | 背包本地权威 |
| `src/main/java/com/habitrain/lottery/mixin/client/GameMenuEntriesHubMixin.java` | 地图轮换跳转 |
| `src/main/java/com/habitrain/lottery/mixin/client/MapIntroduceEmptyMixin.java` | 地图介绍空态提示 |
| `src/test/java/com/habitrain/lottery/mail/MailCommandsCodecTest.java` | 奖励编解码单测 |
| `src/test/java/com/habitrain/lottery/record/LocalMatchRecordStoreTest.java` | 战绩 index 分页单测 |

### Modify in `habitrain_lottery`

| Path | Change |
|------|--------|
| `src/main/java/com/habitrain/lottery/storage/WorldLotteryPaths.java` | 暴露/初始化 meta 子目录 |
| `src/main/java/com/habitrain/lottery/HabiLotteryMod.java` | 注册网络、路径、服务 |
| `src/main/java/com/habitrain/lottery/client/HabiLotteryClient.java` | 客户端网络/开屏 |
| `src/main/java/com/habitrain/lottery/command/LotteryCommands.java` | `/hlt mail`、`/hlt coin` |
| `src/main/java/com/habitrain/lottery/client/gui/LotteryConfigRootScreen.java` | Tab「邮箱」 |
| `src/main/java/com/habitrain/lottery/network/LotteryNetwork.java` | 注册 compose payload |
| `src/main/resources/habitrain_lottery.mixins.json` | server mixins |
| `src/main/resources/habitrain_lottery.client.mixins.json` | client mixins |
| `src/main/resources/fabric.mod.json` | suggests `habitrain_core` |
| `build.gradle` | test 依赖 / compileOnly core jar if present |
| `README.md` | 功能说明 |

### Modify in `哈比列车api` (optional small API)

| Path | Change |
|------|--------|
| `src/main/java/com/habitrain/core/client/gui/config/ConfigRootScreen.java` | `openVote(Screen parent)` 工厂 |

若暂时不发 core 新版本：补齐侧纯反射设置 `selectedTab` 字段仍可工作（Task 6 双路径）。

---

### Task 0: Spec/Plan 入库 + 路径脚手架

**Files:**
- Create: `docs/superpowers/specs/2026-07-17-hub-meta-features-local-bridge-design.md`
- Create: `docs/superpowers/plans/2026-07-17-hub-meta-features-local-bridge-plan.md`
- Create: `src/main/java/com/habitrain/lottery/storage/MetaFeaturePaths.java`
- Modify: `src/main/java/com/habitrain/lottery/storage/WorldLotteryPaths.java`
- Modify: `src/main/java/com/habitrain/lottery/HabiLotteryMod.java`

**Interfaces:**
- Produces: `MetaFeaturePaths.mailPlayer(UUID)`, `recordsIndex()`, `matchFile(String id)`, `backpackPlayer(UUID)`, `ensureDirs()`

- [ ] **Step 1: 写入 design/spec 与 plan 到仓库 docs**

从本 plan 文件复制内容到上述两个 docs 路径（design 用先前确认的规格正文）。

- [ ] **Step 2: 实现 MetaFeaturePaths**

```java
package com.habitrain.lottery.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class MetaFeaturePaths {
    private MetaFeaturePaths() {}

    public static Path root() {
        return WorldLotteryPaths.root(); // 若现有 API 不同，改为现有 world root 方法
    }

    public static Path mailDir() { return root().resolve("mail").resolve("players"); }
    public static Path mailPlayer(UUID id) { return mailDir().resolve(id + ".json"); }

    public static Path recordsDir() { return root().resolve("records"); }
    public static Path recordsIndex() { return recordsDir().resolve("index.jsonl"); }
    public static Path matchesDir() { return recordsDir().resolve("matches"); }
    public static Path matchFile(String matchId) { return matchesDir().resolve(matchId + ".json"); }

    public static Path backpackDir() { return root().resolve("backpack").resolve("players"); }
    public static Path backpackPlayer(UUID id) { return backpackDir().resolve(id + ".json"); }

    public static void ensureDirs() throws java.io.IOException {
        Files.createDirectories(mailDir());
        Files.createDirectories(matchesDir());
        Files.createDirectories(backpackDir());
    }
}
```

先读 `WorldLotteryPaths`，对齐其 `ready()` / root 命名；不要发明第二套 world root。

- [ ] **Step 3: 在服务器 started 钩子调用 ensureDirs**

在 `HabiLotteryMod` 现有 `ServerLifecycleEvents.SERVER_STARTED`（或等价）中，`WorldLotteryPaths` 就绪后调用 `MetaFeaturePaths.ensureDirs()`。

- [ ] **Step 4: 编译确认**

Run: `gradlew.bat compileJava`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-07-17-hub-meta-features-local-bridge-design.md \
  docs/superpowers/plans/2026-07-17-hub-meta-features-local-bridge-plan.md \
  src/main/java/com/habitrain/lottery/storage/MetaFeaturePaths.java \
  src/main/java/com/habitrain/lottery/storage/WorldLotteryPaths.java \
  src/main/java/com/habitrain/lottery/HabiLotteryMod.java
git commit -m "docs+feat: hub meta features spec and world path scaffolding"
```

---

### Task 1: 邮件奖励编解码 + LocalMailboxStore

**Files:**
- Create: `src/main/java/com/habitrain/lottery/mail/MailReward.java`
- Create: `src/main/java/com/habitrain/lottery/mail/MailCommandsCodec.java`
- Create: `src/main/java/com/habitrain/lottery/mail/LocalMailboxStore.java`
- Create: `src/test/java/com/habitrain/lottery/mail/MailCommandsCodecTest.java`
- Modify: `build.gradle`（若尚无 `testImplementation` junit）

**Interfaces:**
- Produces:
  - `record MailReward(Kind kind, int amount, String factionType)` with `enum Kind { DRAWS, COINS, FACTION_CARD }`
  - `MailCommandsCodec.encode(List<MailReward>) -> List<String>`
  - `MailCommandsCodec.decode(List<String>) -> List<MailReward>`
  - `LocalMailboxStore.load(UUID) / save(UUID, List<MailJson>)`  
    邮件持久化用本 mod 自己的 `MailJson` DTO（字段对齐 SRE `Mail` 元数据 + rewards），不要在 store 层直接依赖 ItemStack

**编码约定（写死）：**

```
hltmail:v1:DRAWS:<amount>
hltmail:v1:COINS:<amount>
hltmail:v1:FACTION_CARD:<typeKey>:<amount>
```

`typeKey` ∈ `killer|civilian|neutral|neutral_for_killer`（对齐 `FactionCardType.questKey`）。

- [ ] **Step 1: 写失败单测**

```java
package com.habitrain.lottery.mail;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MailCommandsCodecTest {
    @Test
    void roundTripAllKinds() {
        List<MailReward> in = List.of(
            new MailReward(MailReward.Kind.DRAWS, 3, null),
            new MailReward(MailReward.Kind.COINS, 160, null),
            new MailReward(MailReward.Kind.FACTION_CARD, 2, "killer")
        );
        List<String> cmds = MailCommandsCodec.encode(in);
        assertTrue(cmds.stream().allMatch(s -> s.startsWith("hltmail:v1:")));
        List<MailReward> out = MailCommandsCodec.decode(cmds);
        assertEquals(3, out.size());
        assertEquals(MailReward.Kind.DRAWS, out.get(0).kind());
        assertEquals(3, out.get(0).amount());
        assertEquals("killer", out.get(2).factionType());
    }

    @Test
    void ignoresNonPrefixedCommands() {
        List<MailReward> out = MailCommandsCodec.decode(List.of(
            "say hello",
            "hltmail:v1:COINS:10"
        ));
        assertEquals(1, out.size());
        assertEquals(10, out.get(0).amount());
    }
}
```

- [ ] **Step 2: 跑测确认失败**

Run: `gradlew.bat test --tests com.habitrain.lottery.mail.MailCommandsCodecTest`  
Expected: 编译失败或测试失败（类不存在）

- [ ] **Step 3: 实现 MailReward + MailCommandsCodec + LocalMailboxStore**

`LocalMailboxStore` 文件格式：

```json
{
  "version": 1,
  "mails": [
    {
      "id": "uuid",
      "sender": "系统",
      "title": "...",
      "content": "...",
      "claimed": false,
      "read": false,
      "sentAt": 0,
      "expiresAt": 0,
      "commands": ["hltmail:v1:DRAWS:1"]
    }
  ]
}
```

- [ ] **Step 4: 跑测通过**

Run: `gradlew.bat test --tests com.habitrain.lottery.mail.MailCommandsCodecTest`  
Expected: BUILD SUCCESSFUL, tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/lottery/mail \
  src/test/java/com/habitrain/lottery/mail \
  build.gradle
git commit -m "feat(mail): reward codec and local mailbox store"
```

---

### Task 2: MailService + Mailbox mixin 旁路与领取

**Files:**
- Create: `src/main/java/com/habitrain/lottery/mail/MailService.java`
- Create: `src/main/java/com/habitrain/lottery/mail/MailDraft.java`
- Create: `src/main/java/com/habitrain/lottery/mixin/MailboxMysqlBypassMixin.java`
- Create: `src/main/java/com/habitrain/lottery/mixin/MailboxClaimMixin.java`
- Modify: `src/main/resources/habitrain_lottery.mixins.json`
- Modify: `src/main/java/com/habitrain/lottery/grant/LotteryGrantService.java`（仅当需要 `grantCoins` 助手时新增 public 方法）
- Modify: `src/main/java/com/habitrain/lottery/command/LotteryCommands.java`（增加 `/hlt coin` 供备份命令）

**Interfaces:**
- Consumes: `LocalMailboxStore`, `MailCommandsCodec`, `LotteryGrantService.grant`, `PlayerLotteryStore`, `BackpackManager.addCard`
- Produces:
  - `MailService.send(ServerPlayer target, MailDraft draft) -> boolean`
  - `MailService.sendOffline(UUID uuid, String nameHint, MailDraft draft) -> boolean`
  - `MailService.applyRewards(ServerPlayer player, List<MailReward> rewards) -> void`
  - `MailDraft(String sender, String title, String content, long expiresAt, List<MailReward> rewards)`

- [ ] **Step 1: 实现 MailDraft + MailService.send**

逻辑：

1. `commands = MailCommandsCodec.encode(draft.rewards())`（可再 append 人类可读备份：`hlt grant {player} N` 仅 DRAWS）
2. `new Mail(UUID.randomUUID(), sender, title, content, List.of(), commands, now, expiresAt)`
3. `MailboxComponent.KEY.get(player).sendMail(mail)`
4. 从 component 读回列表序列化到 `LocalMailboxStore.save`
5. `mailbox.sync()`

离线：`LocalMailboxStore.load` → append → save（不上线不调 CCA）。

- [ ] **Step 2: MailboxMysqlBypassMixin**

Target: `io.wifi.starrailexpress.content.mail.MailboxComponent`  
Inject:

- `loadFromDatabase` HEAD：从 `LocalMailboxStore` 加载，merge 进 mails（用已有 `mergeFromDatabase` 或复制其逻辑），`ci.cancel()`，设 `dbLoaded` 相关状态
- `saveToDatabase` HEAD：把当前 mails 存本地，return true / cancel

注意字段名：`dbLoaded`、`mails` 可能 private —— 用 `@Shadow` 或 access widener；优先 `@Shadow` + mixin。

若 `mergeFromDatabase` 为 private，在 mixin 中用自己的 merge 或 `@Invoker`。

- [ ] **Step 3: MailboxClaimMixin**

Inject `claimMail` 在 `executeClaimCommands` **之前**（或 HEAD 成功路径内）：

```java
// 伪代码
List<MailReward> rewards = MailCommandsCodec.decode(m.claimCommands);
if (!rewards.isEmpty()) {
    MailService.applyRewards(serverPlayer, rewards);
}
// 然后让原方法继续执行 claimCommands（hltmail: 前缀命令应 no-op 或被过滤）
```

`applyRewards`:

```java
for (MailReward r : rewards) {
  switch (r.kind()) {
    case DRAWS -> LotteryGrantService.grant(player, r.amount(), "mail-reward", false);
    case COINS -> PlayerLotteryStore.get().update(player, d -> d.coinNum = Math.max(0, d.coinNum + r.amount()));
                 PlayerLotteryStore.get().flush(player.getUUID());
                 // EconomyMirror 若 update 内未推，则显式 push
    case FACTION_CARD -> {
      var type = ProgressionState.FactionCardType.fromString(r.factionType());
      if (type != ProgressionState.FactionCardType.NONE) {
        BackpackManager.addCard(player, type, r.amount());
      }
    }
  }
}
```

同时：在 `executeClaimCommands` 前过滤掉 `hltmail:v1:` 前缀，避免被当作真实 MC 命令执行报错。可用 Redirect 或在 mixin 里改 list 副本。

- [ ] **Step 4: `/hlt coin <player> <amount>`**

与 grant 对称，写 `PlayerLotteryStore`，供备份 claimCommands 与 OP 调试。

- [ ] **Step 5: 注册 mixin + compile**

Run: `gradlew.bat compileJava`  
Expected: SUCCESS（mixin 注解正确）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/habitrain/lottery/mail \
  src/main/java/com/habitrain/lottery/mixin/MailboxMysqlBypassMixin.java \
  src/main/java/com/habitrain/lottery/mixin/MailboxClaimMixin.java \
  src/main/resources/habitrain_lottery.mixins.json \
  src/main/java/com/habitrain/lottery/command/LotteryCommands.java
git commit -m "feat(mail): local mailbox persistence and structured claim rewards"
```

---

### Task 3: OP 发信 GUI + 网络

**Files:**
- Create: `src/main/java/com/habitrain/lottery/network/MailComposeC2SPayload.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/MailComposeScreen.java`
- Modify: `src/main/java/com/habitrain/lottery/network/LotteryNetwork.java`
- Modify: `src/main/java/com/habitrain/lottery/client/HabiLotteryClient.java`
- Modify: `src/main/java/com/habitrain/lottery/client/gui/LotteryConfigRootScreen.java`
- Modify: `src/main/java/com/habitrain/lottery/command/LotteryCommands.java`
- Modify: `src/main/java/com/habitrain/lottery/HabiLotteryMod.java`

**Interfaces:**
- Consumes: `MailService`, OP level from `RatesConfig.opPermissionLevel`
- Produces: C2S payload fields: target mode (ONLINE_LIST | ALL_ONLINE | OFFLINE_NAME), names/uuids, sender, title, content, expiresDays, rewards list

- [ ] **Step 1: 定义 MailComposeC2SPayload**

使用与现有 `LotteryNetwork` 相同的 Fabric payload 风格（`CustomPacketPayload` + `StreamCodec`）。服务端 handler：

```java
if (!player.hasPermissions(opLevel)) return;
MailDraft draft = payload.toDraft();
switch (payload.targetMode()) {
  case ALL_ONLINE -> for (ServerPlayer p : server.getPlayerList().getPlayers()) MailService.send(p, draft);
  case ONLINE_LIST -> for (name : payload.names()) resolve online and send;
  case OFFLINE_NAME -> resolve UUID via user cache / offline UUID and MailService.sendOffline;
}
player.sendSystemMessage(success count);
```

- [ ] **Step 2: MailComposeScreen UI**

布局（对齐 `LotteryConfigRootScreen` 简洁风格即可）：

- 标题 EditBox
- 正文 MultilineTextArea（已有组件可复用）
- 发件人 EditBox 默认「系统」
- 目标：按钮切换 在线列表多选 / 全服 / 离线输入
- 奖励行：类型循环按钮 + 数量 EditBox +「添加奖励」列表
- 过期：0=永不过期，或天数
- 「发送」→ 发包 C2S
- 「取消」→ parent

- [ ] **Step 3: 入口**

1. `LotteryConfigRootScreen` TABS 增加 `"邮箱"`，内容区按钮「撰写邮件」→ `MailComposeScreen`
2. `/hlt mail` → `LotteryNetwork.sendOpenMailCompose(player)` S2C 开屏（或仅客户端命令不行：用 S2C open 包，与 loot UI 相同模式）

- [ ] **Step 4: 编译**

Run: `gradlew.bat compileJava`  
Expected: SUCCESS

- [ ] **Step 5: 手动冒烟清单（写进 commit message 检查项）**

进服 OP：`/hlt mail` → 给自己发 1 抽 + 10 金币 + 1 killer 卡 → 邮箱领取 → `/hlt inspect` 验证 chance/coins；背包界面验证卡。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/habitrain/lottery/network \
  src/main/java/com/habitrain/lottery/client \
  src/main/java/com/habitrain/lottery/command/LotteryCommands.java \
  src/main/java/com/habitrain/lottery/HabiLotteryMod.java
git commit -m "feat(mail): OP compose GUI and network send"
```

---

### Task 4: 对局记录本地化

**Files:**
- Create: `src/main/java/com/habitrain/lottery/record/LocalMatchRecordStore.java`
- Create: `src/main/java/com/habitrain/lottery/mixin/MatchRecordStoreMixin.java`
- Create: `src/main/java/com/habitrain/lottery/mixin/MatchRecordServiceMixin.java`
- Create: `src/test/java/com/habitrain/lottery/record/LocalMatchRecordStoreTest.java`
- Modify: `src/main/resources/habitrain_lottery.mixins.json`

**Interfaces:**
- Produces:
  - `LocalMatchRecordStore.isReady(): boolean`
  - `save(MatchRecord): boolean` — 写 match json + index 行
  - `listWindow(offset, limit): MatchPage` — 与 `MatchRecordStore.MatchPage` 兼容（或返回同结构数据供 mixin 填充）
  - `load(matchId): Optional<MatchRecord>`
  - 上限 `MAX_RECORDS = 200`

**Index 行 JSONL：** 使用 `MatchRecord.Summary` 若存在；否则最小字段：`matchId, createdAt, playerCount, winningTeam, winningTitleJson`。

- [ ] **Step 1: 写 LocalMatchRecordStore 分页单测（纯文件 IO，用 junit temp dir）**

```java
@Test
void listWindowNewestFirst() throws Exception {
  Path root = Files.createTempDirectory("hlt-rec");
  // 注入 root 或用 package-visible test hook
  // save 3 records with increasing createdAt
  // listWindow(0,2) returns 2 newest
}
```

若 `MatchRecord` 构造过重，单测只测 index 行 DTO。

- [ ] **Step 2: 实现 LocalMatchRecordStore**

序列化优先复用 `MatchRecord.toJson()` / 现有 summary JSON 方法（读 SRE 源确认方法名）。

- [ ] **Step 3: MatchRecordStoreMixin**

```java
@Mixin(value = MatchRecordStore.class, remap = false)
public class MatchRecordStoreMixin {
  @Inject(method = "isAvailable", at = @At("HEAD"), cancellable = true)
  private static void habi$avail(CallbackInfoReturnable<Boolean> cir) {
    if (WorldLotteryPaths.ready()) cir.setReturnValue(true);
  }

  @Inject(method = "saveAsync", at = @At("HEAD"), cancellable = true)
  private static void habi$save(MatchRecord record, CallbackInfoReturnable<CompletableFuture<Boolean>> cir) {
    if (!WorldLotteryPaths.ready()) return;
    cir.setReturnValue(CompletableFuture.supplyAsync(() -> LocalMatchRecordStore.save(record)));
  }

  // 同样旁路 listWindowAsync / loadAsync
}
```

- [ ] **Step 4: MatchRecordServiceMixin**

```java
@Inject(method = "recordFinishedMatch", at = @At("HEAD"), cancellable = true)
private static void habi$record(ServerLevel level, CallbackInfo ci) {
  if (level == null || !WorldLotteryPaths.ready()) return;
  // 复制原方法体逻辑但跳过 isStatsSyncEnabled 检查，调用 LocalMatchRecordStore
  // 或：只取消早期 return——更干净的做法是 @Redirect 对 isStatsSyncEnabled 的读取恒 true
  ci.cancel();
  // ... build + save 与原版相同，直接调用 LocalMatchRecordStore.save
}
```

优先 **Redirect** `SREConfig.instance().isStatsSyncEnabled` 在该方法内为 true，改动最小；若 Redirect 不稳则 HEAD cancel + 委托 `LocalMatchRecordStore.recordFromReplay(level)` 把 build 逻辑抽到本 mod（从 SRE 源复制 `build` 私有逻辑需要 `@Invoker`/`@Shadow` 或重复代码——允许小范围复制 build 调用链：若 `build` private，用 mixin 调用原方法中间路径）。

**推荐实现：**  
`@Redirect` 将 `isStatsSyncEnabled` 在 `recordFinishedMatch` 中视为 `true`，且 `isAvailable` 已 true → 原方法完整跑，saveAsync 已被旁路到本地。**不必复制 build。**

- [ ] **Step 5: 测试 + 编译**

Run: `gradlew.bat test --tests com.habitrain.lottery.record.LocalMatchRecordStoreTest compileJava`  
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/habitrain/lottery/record \
  src/main/java/com/habitrain/lottery/mixin/MatchRecord*.java \
  src/test/java/com/habitrain/lottery/record \
  src/main/resources/habitrain_lottery.mixins.json
git commit -m "feat(records): world-local match record store via mixins"
```

---

### Task 5: 阵营卡本地权威

**Files:**
- Create: `src/main/java/com/habitrain/lottery/backpack/LocalBackpackStore.java`
- Create: `src/main/java/com/habitrain/lottery/mixin/BackpackManagerMixin.java`
- Modify: `src/main/resources/habitrain_lottery.mixins.json`

**Interfaces:**
- Produces: `LocalBackpackStore.load(UUID) -> Map<String,Integer>`, `save(UUID, Map)`, `applyAdd(UUID, typeKey, delta)`

JSON:

```json
{ "version": 1, "cards": { "killer": 1, "civilian": 0, "neutral": 0, "neutral_for_killer": 0 } }
```

- [ ] **Step 1: 实现 LocalBackpackStore**

- [ ] **Step 2: BackpackManagerMixin 策略**

读取 `BackpackManager` 源：在 `onJoin` / `addCard` / `getCards` / flush 路径注入。

最小可靠集：

1. **Redirect/Inject `isDatabaseEnabled`**（若存在 private 方法）恒 false 时仍要有内存数据 → 更好：  
   - Inject `onJoin` 末尾：从本地 load 填入 `ENTRIES` 的 state.cards  
   - Inject `addCard` 末尾：`LocalBackpackStore.save` 当前 cards  
   - Inject `activateCard` 成功路径：同样 save  
   - Inject `flushBlocking`：写本地并 return true（避免依赖 MySQL）

用 `@Shadow` 访问 `ENTRIES` / `Entry` 可能困难（private static）。替代：

- `@Inject` `addCard` HEAD 后 `@At("RETURN")` 调 `BackpackManager.getCards(player)` 再 save（公开 API）  
- `onJoin`：在 `ServerPlayConnectionEvents.JOIN` 本 mod 自己注册，延迟 1 tick 后 `getCards` 若全 0 则 load 本地并逐张 `addCard`（可能重复）—— **更干净：Mixin 注入 `reloadFromDatabase` 完成回调**。

**选定实现：**  
Mixin `BackpackManager.addCard` RETURN → persist via getCards。  
Mixin `BackpackManager.activateCard` RETURN true 时 persist。  
本 mod `ServerPlayConnectionEvents.JOIN`：`LocalBackpackStore.load` 后对每种 type `addCard` 差额补齐（loaded - current）。  
Mixin 或替换 `flushBlocking` 写本地。

- [ ] **Step 3: 编译**

Run: `gradlew.bat compileJava`  
Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/lottery/backpack \
  src/main/java/com/habitrain/lottery/mixin/BackpackManagerMixin.java \
  src/main/resources/habitrain_lottery.mixins.json
git commit -m "feat(backpack): world-local faction card authority"
```

---

### Task 6: 地图轮换跳转 + 地图介绍空态

**Files:**
- Create: `src/main/java/com/habitrain/lottery/meta/HabiCoreMenuBridge.java`
- Create: `src/main/java/com/habitrain/lottery/mixin/client/GameMenuEntriesHubMixin.java`
- Create: `src/main/java/com/habitrain/lottery/mixin/client/MapIntroduceEmptyMixin.java`
- Modify: `src/main/resources/habitrain_lottery.client.mixins.json`
- Modify: `src/main/resources/fabric.mod.json` — `"suggests": { "habitrain_core": "*" }`
- Modify (core repo): `D:\Backup\mc mod\哈比列车api\src\main\java\com\habitrain\core\client\gui\config\ConfigRootScreen.java`

**Interfaces:**
- Produces: `HabiCoreMenuBridge.tryOpenVoteConfig(Screen parent): boolean` — true 若成功打开

- [ ] **Step 1: core 增加工厂（若可改 API 工程）**

```java
// ConfigRootScreen.java
public static ConfigRootScreen openVote(Screen parent) {
    ConfigRootScreen screen = new ConfigRootScreen(parent);
    screen.selectedTab = TAB_VOTE;
    return screen;
}
```

`selectedTab` 当前是 private：同文件内可设；或加 package 方法 `openAtTab(parent, TAB_VOTE)`。

改完 core 后：`gradlew.bat clean build` 于 `哈比列车api`，JAR 复制到 `临时/`。

- [ ] **Step 2: HabiCoreMenuBridge（反射，兼容未更新 core）**

```java
public static boolean tryOpenVoteConfig(Screen parent) {
  if (!FabricLoader.getInstance().isModLoaded("habitrain_core")) return false;
  try {
    Class<?> cls = Class.forName("com.habitrain.core.client.gui.config.ConfigRootScreen");
    try {
      Method m = cls.getMethod("openVote", Screen.class);
      Minecraft.getInstance().setScreen((Screen) m.invoke(null, parent));
      return true;
    } catch (NoSuchMethodException ignored) {
      Object screen = cls.getConstructor(Screen.class).newInstance(parent);
      Field f = cls.getDeclaredField("selectedTab");
      f.setAccessible(true);
      f.setInt(screen, 3); // TAB_VOTE
      Minecraft.getInstance().setScreen((Screen) screen);
      return true;
    }
  } catch (Throwable t) {
    return false;
  }
}
```

- [ ] **Step 3: GameMenuEntriesHubMixin**

对 `entries_hub` 使用 `@ModifyVariable` 或 `@Inject` RETURN 前改写 list 中 map_rotation 的 entry。

更稳：`@Inject(method = "entries_hub", at = @At("RETURN"))`，遍历 `ArrayList<MenuEntry>`，若 label 翻译键对应 map_rotation，则 `list.set(i, new MenuEntry(label, btn -> { if (!HabiCoreMenuBridge.tryOpenVoteConfig(parent)) { /* fallback */ minecraft.setScreen(new MapRotationScreen()); player.displayClientMessage(...); } toggleViewMenu.accept(false); }))`。

MenuEntry 是 record：可直接 new。

识别 entry：比较 `label.getString()` 不稳；应用 `Component` 的 contents 或在创建后按 index（map_rotation 是列表第 3 个，0-based index 2：introduction, map_introduction, map_rotation）。**不要用硬编码 index**——用 translatable key：`screen.limited_inventory.menu.map_rotation`。在 1.21 可用 `label.getContents() instanceof TranslatableContents tc && tc.getKey().equals(...)`。

- [ ] **Step 4: MapIntroduceEmptyMixin**

Target: `MapIntroduceScreen` 在收到空 maps 或首次 render 且 loaded 空时，draw centered 提示：

`§c暂无地图介绍数据。请检查世界目录 train_maps/ 与地图配置。`

读 `MapIntroduceScreen` 字段名后注入；若结构不便，可 inject `updateFromPacket` 空列表时 `player.displayClientMessage`。

- [ ] **Step 5: 编译两工程**

```
cd "D:\Backup\mc mod\哈比列车api" && gradlew.bat clean build
cd "D:\Backup\mc mod\哈比列车抽奖补齐" && gradlew.bat clean build
```

Expected: 两 JAR 出现在 `D:\Backup\mc mod\临时\`

- [ ] **Step 6: Commit（可两个 repo 分别 commit）**

```bash
# lottery
git commit -m "feat(meta): map rotation opens habi core vote config; map intro empty state"
# api
git commit -m "feat(config): ConfigRootScreen.openVote factory for lottery bridge"
```

---

### Task 7: README、回归构建、端到端检查清单

**Files:**
- Modify: `README.md`
- Modify: 任意缺口修复

- [ ] **Step 1: README 增加章节**

```markdown
## 大厅元功能（本地化）

本 mod 将 SRE 大厅功能改为 world 本地权威：

| 功能 | 行为 |
|------|------|
| 对局记录 | `{world}/habitrain_lottery/records/`，不依赖 MySQL/stats 开关 |
| 地图介绍 | 仍用 `train_maps/`；无数据时提示 |
| 地图轮换 | 跳转哈比列车核心 → 投票设置（需 habitrain_core） |
| 邮箱 | 本地 `mail/players/`；OP `/hlt mail` 或 Mod Menu「邮箱」发奖 |

奖励类型：抽数、金币、阵营卡（killer/civilian/neutral/neutral_for_killer）。
```

- [ ] **Step 2: 全量构建**

Run: `gradlew.bat clean build`  
Expected: BUILD SUCCESSFUL；`临时/habitrain_lottery-*.jar` 更新

- [ ] **Step 3: 端到端检查表（人工）**

| # | 步骤 | 期望 |
|---|------|------|
| 1 | 新世界进服 | 自动创建 mail/records/backpack 目录 |
| 2 | `/hlt mail` 发 2 抽+100 币+1 civilian | 成功消息 |
| 3 | 打开邮箱领取 | chance+2 coins+100 卡+1 |
| 4 | 重启再 inspect | 数据仍在 |
| 5 | 离线玩家发信后上线 | 邮件可见 |
| 6 | 完成一局 | 对局记录有新条目 |
| 7 | 打开地图介绍 | 有图或明确空提示 |
| 8 | 大厅地图轮换 | 进 core 投票设置 |
| 9 | `/hlt open` 抽奖 | 仍正常 |

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: document hub meta local-bridge features"
```

---

## Spec Coverage Self-Check

| Spec 要求 | Task |
|-----------|------|
| world 本地 mail/records/backpack | 0,1,4,5 |
| Mailbox MySQL 旁路 | 2 |
| 结构化奖励 抽数/金币/阵营卡 | 1,2 |
| 完整发信 GUI + `/hlt mail` + Mod Menu | 3 |
| 离线邮件 | 2,3 |
| 不做物品附件 | 遵守（无任务） |
| MatchRecord 本地 + 去 stats 硬依赖 | 4 |
| 背包本地权威 | 5 |
| 地图轮换 → API 投票 | 6 |
| 无 core fallback | 6 |
| 地图介绍空态 | 6 |
| 构建复制到临时 | 6,7 + Global Constraints |
| design/plan 入库 | 0 |

## Placeholder / Consistency Scan

- 奖励前缀统一 `hltmail:v1:`  
- TAB_VOTE = 3 与 core 源码一致  
- 阵营 key 用 `questKey` 小写  
- 无 TBD 步骤  

---

## Execution Handoff

Plan complete and saved to plan file  
`C:\Users\15134\.claude\plans\playful-launching-eclipse.md`  
（实施 Task 0 时同步到 `docs/superpowers/plans/2026-07-17-hub-meta-features-local-bridge-plan.md`）。

**Two execution options:**

1. **Subagent-Driven (recommended)** — 每个 Task 新开 subagent，任务间审查，迭代快  
2. **Inline Execution** — 本会话用 executing-plans 批量执行并设检查点  

**Which approach?**
