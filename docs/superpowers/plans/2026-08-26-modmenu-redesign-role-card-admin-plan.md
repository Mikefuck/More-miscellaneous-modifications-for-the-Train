# 哈比列车抽奖补齐 Mod Menu 全量重设计与角色卡管理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 Mod Menu 重建为 A 版运营控制台，并为单个在线或离线玩家加入安全、可验证的四类角色卡资产管理。

**Architecture:** 保留 Mod Menu 公开入口和既有配置服务，以纯 Java 的角色卡管理策略、服务端管理服务和响应式布局模型隔离可测试逻辑。新的控制台 Screen 统一导航、状态和操作栏，九类功能通过独立 section 控制器挂载；玩家资产 section 使用玩家列表、基础资产与角色卡详情子页。

**Tech Stack:** Java 21、Minecraft 1.21.1 Mojang mappings、Fabric API、Fabric Networking API、Mod Menu 11.0.3、JUnit Jupiter 5.10.2、Gradle Fabric Loom。

**Spec:** `docs/superpowers/specs/2026-08-26-modmenu-redesign-role-card-admin-design.md`

## Global Constraints

- 只修改 `D:\Backup\mc mod\哈比列车抽奖补齐`；`D:\Backup\mc mod\backup` 绝对禁止访问。
- 不修改只读上游 `D:\Backup\mc mod\哈比列车dlc`；只可参考其公开 `BackpackManager` 行为。
- 保留现有九类功能、配置 JSON 格式、服务端存储位置和权限行为。
- 不加入任何全体玩家批量修改角色卡入口或协议模式。
- 用户可见文字必须进入 `zh_cn.json` 与 `en_us.json` 翻译资源。
- 所有生产行为先由失败测试定义；GUI 绘制通过纯布局模型测试、编译和人工源码检查补足。
- 完成后必须运行 `./gradlew build`，将 remapped JAR 复制到 `D:\Backup\mc mod\临时` 并比较 SHA-256。

---

### Task 1: 角色卡管理模型、输入边界与离线存储修改

**Files:**
- Create: `src/main/java/com/habitrain/lottery/backpack/PlayerCardAdminModels.java`
- Create: `src/main/java/com/habitrain/lottery/backpack/PlayerCardMutationPolicy.java`
- Modify: `src/main/java/com/habitrain/lottery/backpack/LocalBackpackStore.java`
- Create: `src/test/java/com/habitrain/lottery/backpack/PlayerCardMutationPolicyTest.java`
- Create: `src/test/java/com/habitrain/lottery/backpack/LocalBackpackStoreAdminTest.java`

**Interfaces:**
- Produces: `PlayerCardAdminModels.CardStoreStatus { ONLINE_LIVE, STORED, MISSING, CORRUPT }`。
- Produces: `PlayerCardAdminModels.CardOperation { ADD, SET }`。
- Produces: `PlayerCardAdminModels.CardSnapshot(CardStoreStatus status, Map<String,Integer> cards)`。
- Produces: `PlayerCardMutationPolicy.parseType(String)`、`validate(operation, value)` 和 `targetCount(current, operation, value)`。
- Produces: `LocalBackpackStore.mutate(UUID, FactionCardType, CardOperation, int)`，返回包含成功、消息、状态和新数量的结果。

- [ ] **Step 1: 写角色卡类型与边界失败测试**

```java
@Test
void acceptsOnlyFourEditableFactionCards() {
    assertEquals(FactionCardType.CIVILIAN, PlayerCardMutationPolicy.parseType("civilian"));
    assertEquals(FactionCardType.NEUTRAL, PlayerCardMutationPolicy.parseType("neutral"));
    assertEquals(FactionCardType.NEUTRAL_FOR_KILLER,
            PlayerCardMutationPolicy.parseType("neutral_for_killer"));
    assertEquals(FactionCardType.KILLER, PlayerCardMutationPolicy.parseType("killer"));
    assertNull(PlayerCardMutationPolicy.parseType("none"));
    assertNull(PlayerCardMutationPolicy.parseType("unknown"));
}

@Test
void clampsAddAndSetToProtocolBounds() {
    assertEquals(8, PlayerCardMutationPolicy.targetCount(10, CardOperation.ADD, -2));
    assertEquals(0, PlayerCardMutationPolicy.targetCount(1, CardOperation.ADD, -1000));
    assertThrows(IllegalArgumentException.class,
            () -> PlayerCardMutationPolicy.validate(CardOperation.ADD, 1001));
    assertThrows(IllegalArgumentException.class,
            () -> PlayerCardMutationPolicy.validate(CardOperation.SET, 100001));
}
```

- [ ] **Step 2: 运行定向测试并确认因类不存在而失败**

Run: `./gradlew test --tests "com.habitrain.lottery.backpack.PlayerCardMutationPolicyTest"`  
Expected: FAIL，缺少 `PlayerCardMutationPolicy` 或 `PlayerCardAdminModels`。

- [ ] **Step 3: 实现最小模型与策略**

```java
public static void validate(CardOperation operation, int value) {
    if (operation == CardOperation.ADD && (value < -1000 || value > 1000 || value == 0)) {
        throw new IllegalArgumentException("角色卡增减值必须在 -1000..1000 且不能为 0");
    }
    if (operation == CardOperation.SET && (value < 0 || value > 100000)) {
        throw new IllegalArgumentException("角色卡目标数量必须在 0..100000");
    }
}
```

- [ ] **Step 4: 运行策略测试并确认通过**

Run: `./gradlew test --tests "com.habitrain.lottery.backpack.PlayerCardMutationPolicyTest"`  
Expected: PASS。

- [ ] **Step 5: 写离线存储失败测试**

```java
@Test
void setOnMissingCreatesCompleteCardRecord() {
    WorldLotteryPaths.initForTests(temp);
    UUID id = UUID.randomUUID();
    var result = LocalBackpackStore.mutate(id, FactionCardType.KILLER, CardOperation.SET, 7);
    assertTrue(result.ok());
    assertEquals(7, LocalBackpackStore.load(id).get("killer"));
    assertTrue(LocalBackpackStore.load(id).containsKey("civilian"));
}

@Test
void corruptBackpackIsNeverOverwritten() throws Exception {
    WorldLotteryPaths.initForTests(temp);
    UUID id = UUID.randomUUID();
    Path file = MetaFeaturePaths.backpackPlayer(id);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "{");
    var result = LocalBackpackStore.mutate(id, FactionCardType.KILLER, CardOperation.SET, 7);
    assertFalse(result.ok());
    assertEquals(CardStoreStatus.CORRUPT, result.status());
}
```

- [ ] **Step 6: 运行离线存储测试并确认因 mutate 缺失而失败**

Run: `./gradlew test --tests "com.habitrain.lottery.backpack.LocalBackpackStoreAdminTest"`  
Expected: FAIL，缺少 `LocalBackpackStore.mutate`。

- [ ] **Step 7: 实现离线原子修改并运行 Task 1 全部测试**

`mutate` 必须使用 `loadResult` 区分 `MISSING`、`STORED` 和 `CORRUPT`；复制 Map 后只修改规范 `questKey`，最后调用现有 `save`。  
Run: `./gradlew test --tests "com.habitrain.lottery.backpack.*"`  
Expected: PASS。

### Task 2: 玩家快照和服务端在线/离线角色卡管理服务

**Files:**
- Modify: `src/main/java/com/habitrain/lottery/network/PlayerAdminModels.java`
- Create: `src/main/java/com/habitrain/lottery/backpack/PlayerCardAdminService.java`
- Create: `src/test/java/com/habitrain/lottery/network/PlayerAdminModelsCardJsonTest.java`
- Create: `src/test/java/com/habitrain/lottery/backpack/PlayerCardAdminServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `CardStoreStatus`、`CardOperation`、mutation policy 和离线存储 mutation。
- Produces: `PlayerRow.cardStatus` 与 `PlayerRow.cards`。
- Produces: `PlayerCardAdminService.snapshot(UUID, ServerPlayer)` 和 `mutate(MinecraftServer, UUID, FactionCardType, CardOperation, int)`。
- Produces: 可注入的 `OnlineCardAccess` 窄接口，隔离 `BackpackManager` 静态 API 以便普通 JUnit 测试。

- [ ] **Step 1: 写玩家快照 JSON 往返失败测试**

```java
@Test
void playerRowJsonCarriesFourCardsAndStoreStatus() {
    PlayerRow row = new PlayerRow();
    row.cardStatus = "STORED";
    row.cards.put("civilian", 1);
    row.cards.put("neutral", 2);
    row.cards.put("neutral_for_killer", 3);
    row.cards.put("killer", 4);
    PlayerRow copy = new Gson().fromJson(new Gson().toJson(row), PlayerRow.class);
    assertEquals("STORED", copy.cardStatus);
    assertEquals(4, copy.cards.get("killer"));
}
```

- [ ] **Step 2: 运行快照测试并确认字段缺失导致失败**

Run: `./gradlew test --tests "com.habitrain.lottery.network.PlayerAdminModelsCardJsonTest"`  
Expected: FAIL，`PlayerRow` 没有角色卡字段。

- [ ] **Step 3: 扩展 DTO 并让快照测试通过**

为 `PlayerRow` 初始化非空 `LinkedHashMap<String,Integer>`，默认状态为 `MISSING`；保持原构造器兼容。

- [ ] **Step 4: 写服务路由失败测试**

```java
@Test
void onlineMutationUsesLiveAccessAndResends() {
    FakeOnlineCardAccess live = new FakeOnlineCardAccess(2);
    var result = service.mutate(id, true, live, FactionCardType.KILLER, CardOperation.SET, 5);
    assertTrue(result.ok());
    assertEquals(3, live.lastDelta);
    assertTrue(live.resent);
}
```

- [ ] **Step 5: 运行服务测试并确认因服务缺失而失败**

Run: `./gradlew test --tests "com.habitrain.lottery.backpack.PlayerCardAdminServiceTest"`  
Expected: FAIL，缺少服务或在线适配接口。

- [ ] **Step 6: 实现服务并通过 Task 2 测试**

在线路径必须以 `getCardCount` 计算 delta，调用 `addCard`，持久化失败则返回失败，成功后 `resend`；离线路径委托 Task 1。  
Run: `./gradlew test --tests "com.habitrain.lottery.network.PlayerAdminModelsCardJsonTest" --tests "com.habitrain.lottery.backpack.PlayerCardAdminServiceTest"`  
Expected: PASS。

### Task 3: 角色卡网络协议、服务端权限与客户端缓存

**Files:**
- Modify: `src/main/java/com/habitrain/lottery/network/LotteryNetwork.java`
- Modify: `src/main/java/com/habitrain/lottery/client/LotteryClientNetwork.java`
- Create: `src/test/java/com/habitrain/lottery/network/PlayerCardModifyPayloadTest.java`

**Interfaces:**
- Consumes: Task 2 的 `PlayerCardAdminService` 与扩展 `PlayerRow`。
- Produces: `PlayerCardModifyC2S(String targetUuid, String questKey, String operation, int value)`。
- Produces: `LotteryClientNetwork.clientModifyPlayerCard(...)`。
- Produces: 玩家列表构建完成后调用角色卡服务补全每行卡片状态。

- [ ] **Step 1: 写 payload 编解码和边界策略失败测试**

```java
@Test
void payloadModelKeepsTargetTypeOperationAndValue() {
    var payload = new PlayerCardModifyC2S(UUID.randomUUID().toString(), "killer", "SET", 8);
    assertEquals("killer", payload.questKey());
    assertEquals("SET", payload.operation());
    assertEquals(8, payload.value());
}
```

- [ ] **Step 2: 运行测试并确认 payload 缺失导致失败**

Run: `./gradlew test --tests "com.habitrain.lottery.network.PlayerCardModifyPayloadTest"`  
Expected: FAIL，缺少 `PlayerCardModifyC2S`。

- [ ] **Step 3: 注册 payload 与客户端发送 API**

在 play C2S payload registry 和 receiver 中注册独立 `player_card_mod`，字段长度限制为 UUID 64、questKey 32、operation 8；客户端只发送原始请求，不直接修改缓存数字。

- [ ] **Step 4: 实现服务端处理链**

处理顺序固定为限流、OP、访问门控、UUID、操作、卡类型、数值、服务器线程 mutation。成功响应设置 `refreshPlayers=true`，失败返回明确消息且不改状态。

- [ ] **Step 5: 扩展玩家快照并运行网络相关测试**

在线行使用 live snapshot；离线行使用 store snapshot；单个角色卡读取失败只标记该行，不中断整个列表。  
Run: `./gradlew test --tests "com.habitrain.lottery.network.*" --tests "com.habitrain.lottery.backpack.PlayerCardAdminServiceTest"`  
Expected: PASS。

### Task 4: 响应式控制台布局模型与翻译资源

**Files:**
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/ConfigConsoleLayout.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/ConfigSectionId.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/PlayerAssetsViewState.java`
- Create: `src/test/java/com/habitrain/lottery/client/gui/config/ConfigConsoleLayoutTest.java`
- Create: `src/test/java/com/habitrain/lottery/client/gui/config/PlayerAssetsViewStateTest.java`
- Modify: `src/main/resources/assets/habitrain_lottery/lang/zh_cn.json`
- Modify: `src/main/resources/assets/habitrain_lottery/lang/en_us.json`

**Interfaces:**
- Produces: `ConfigConsoleLayout.calculate(width, height)`，返回 header、navigation、content、footer、wide/compact/narrow mode 和玩家列表/详情边界。
- Produces: `ConfigSectionId` 九个导航项、分组和翻译键。
- Produces: `PlayerAssetsViewState`，以 UUID 保存玩家选择，并保存基础资产/角色卡子页与窄屏列表/详情状态。

- [ ] **Step 1: 写布局失败测试**

```java
@ParameterizedTest
@CsvSource({"854,480", "640,360", "420,240", "320,180"})
void calculatedRegionsStayInsideScreen(int width, int height) {
    var layout = ConfigConsoleLayout.calculate(width, height);
    assertTrue(layout.content().width() >= 0);
    assertTrue(layout.footer().bottom() <= height);
    assertFalse(layout.navigation().intersects(layout.content()));
}
```

- [ ] **Step 2: 运行布局测试并确认类不存在而失败**

Run: `./gradlew test --tests "com.habitrain.lottery.client.gui.config.ConfigConsoleLayoutTest"`  
Expected: FAIL。

- [ ] **Step 3: 实现布局 token、断点和玩家资产状态**

宽屏显示固定 rail 和玩家双栏；compact 缩窄 rail；narrow 使用顶部导航和玩家列表/详情单页。所有矩形由安全边距、header/footer 高度和 gap 推导。

- [ ] **Step 4: 运行布局与状态测试并确认通过**

Run: `./gradlew test --tests "com.habitrain.lottery.client.gui.config.*"`  
Expected: PASS。

- [ ] **Step 5: 添加中英文翻译并验证 JSON**

加入控制台标题、导航分组、九页标题/说明、连接状态、保存动作、玩家资产、四类卡片、缺失/损坏、权限和校验错误键。  
Run: `./gradlew processResources`  
Expected: PASS，两个语言 JSON 均可解析。

### Task 5: A 版控制台外壳与独立 section 生命周期

**Files:**
- Modify: `src/main/java/com/habitrain/lottery/client/gui/LotteryConfigRootScreen.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/ConfigSection.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/ConfigSectionContext.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/ConfigNavigationRail.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/ConfigStatusBar.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/ConfigActionBar.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/sections/PoolsConfigSection.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/sections/RatesConfigSection.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/sections/GrantsConfigSection.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/sections/ThemeConfigSection.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/sections/TitlesConfigSection.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/sections/SkinsConfigSection.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/sections/MailConfigSection.java`
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/sections/JsonConfigSection.java`

**Interfaces:**
- Consumes: Task 4 的布局和导航模型。
- Produces: `ConfigSection` 生命周期 `init(context)`、`tick()`、`render(...)`、`mouseScrolled(...)`、`apply()`、`hasSaveAction()`、`dispose()`。
- Produces: 根 Screen 只管理 shell、当前 section、快照刷新、焦点和父页面返回。

- [ ] **Step 1: 为 section 切换状态写失败测试**

使用纯 `ConfigSectionId`/session state 测试切换后仍保留选中 section、搜索与 UUID；生产 GUI 类本身不在普通 JUnit 中启动 Minecraft。

- [ ] **Step 2: 实现控制台 chrome 和导航**

顶部渲染标题、面包屑和状态徽章；左侧按三组绘制九项导航；窄屏使用顶部入口。导航控件必须有 translatable 文本、Tooltip/narration 和一致 focus 状态。

- [ ] **Step 3: 迁移八个非玩家 section**

从旧单体类逐个迁移现有 pools/rates/grants/theme/titles/skins/mail/json 行为；每次只迁移一个 section，编译后再继续，保持原模型、网络 API 和保存语义。

- [ ] **Step 4: 实现统一 action/status bar**

配置页显示刷新、应用、保存、重载、返回；即时操作页只显示适用动作。非 OP、离线、未授权、脏状态、请求中和错误由集中状态源派生。

- [ ] **Step 5: 编译客户端源并修复映射差异**

Run: `./gradlew compileJava`  
Expected: PASS，无重复控件渲染路径或服务端源集加载 client 类。

### Task 6: 玩家资产 section 与角色卡编辑交互

**Files:**
- Create: `src/main/java/com/habitrain/lottery/client/gui/config/sections/PlayerAssetsSection.java`
- Modify: `src/main/java/com/habitrain/lottery/client/gui/LotteryConfigRootScreen.java`
- Modify: `src/main/java/com/habitrain/lottery/client/gui/ScrollableButtonList.java`
- Create: `src/test/java/com/habitrain/lottery/client/gui/config/PlayerAssetFilterTest.java`

**Interfaces:**
- Consumes: Task 3 的扩展玩家快照和 `clientModifyPlayerCard`。
- Consumes: Task 4 的玩家资产布局与 view state。
- Produces: 名称或 UUID 搜索、UUID 稳定选择、基础资产/角色卡子页、四行卡片编辑和窄屏列表/详情导航。

- [ ] **Step 1: 写名称/UUID 搜索与选择保持失败测试**

```java
@Test
void filterMatchesNameOrUuidAndKeepsSelectionByUuid() {
    var filtered = PlayerAssetFilter.filter(rows, "1111");
    assertEquals("11111111-1111-1111-1111-111111111111", filtered.getFirst().uuid);
    assertEquals(0, PlayerAssetFilter.indexOfUuid(filtered, selectedUuid));
}
```

- [ ] **Step 2: 运行测试并确认 filter 类不存在而失败**

Run: `./gradlew test --tests "com.habitrain.lottery.client.gui.config.PlayerAssetFilterTest"`  
Expected: FAIL。

- [ ] **Step 3: 实现玩家列表和详情子页**

列表显示在线文字/符号、抽数和硬币；角色卡子页固定四行，显示 status、数量、步进框、减少、增加和设为。无全员卡片控件。

- [ ] **Step 4: 实现待确认和错误状态**

发送角色卡请求后禁用该玩家的卡片按钮；不乐观修改数量。收到 `AdminActionResultS2C` 后刷新，失败保留输入。`CORRUPT` 禁用并解释，`MISSING` 允许第一次设定。

- [ ] **Step 5: 验证鼠标、键盘和滚动路由**

列表只在命中区域滚动；窄屏 Back 返回列表；Tab/Shift+Tab 顺序与视觉一致；切换搜索、玩家或 section 后恢复搜索、UUID 和列表滚动。

- [ ] **Step 6: 运行 GUI 纯逻辑测试和编译**

Run: `./gradlew test --tests "com.habitrain.lottery.client.gui.config.*"`  
Run: `./gradlew compileJava`  
Expected: 两者 PASS。

### Task 7: 全量回归、资源检查、构建与 JAR 交付

**Files:**
- Verify: `src/main/java/com/habitrain/lottery/backpack/PlayerCardAdminService.java`
- Verify: `src/main/java/com/habitrain/lottery/network/LotteryNetwork.java`
- Verify: `src/main/java/com/habitrain/lottery/client/gui/LotteryConfigRootScreen.java`
- Verify: `src/main/resources/assets/habitrain_lottery/lang/zh_cn.json`
- Verify: `src/main/resources/assets/habitrain_lottery/lang/en_us.json`
- Deliver: `D:\Backup\mc mod\临时\habitrain_lottery-1.0.1.jar`

**Interfaces:**
- Consumes: Tasks 1–6 的全部实现。
- Produces: 可加载的 remapped Fabric JAR 和 SHA-256 一致的临时目录交付件。

- [ ] **Step 1: 运行角色卡和界面定向测试**

Run: `./gradlew test --tests "com.habitrain.lottery.backpack.*" --tests "com.habitrain.lottery.network.*" --tests "com.habitrain.lottery.client.gui.config.*"`  
Expected: 0 failures。

- [ ] **Step 2: 运行完整测试**

Run: `./gradlew test`  
Expected: 0 failures。

- [ ] **Step 3: 运行正式构建**

Run: `./gradlew build`  
Expected: `BUILD SUCCESSFUL`，生成 remapped JAR，并由项目 `assemble` 依赖复制到临时目录。

- [ ] **Step 4: 检查 JAR 内容**

检查 `fabric.mod.json` 的 `modmenu` entrypoint、角色卡 payload/服务类、A 版控制台类和 `zh_cn.json`/`en_us.json` 均存在；确认没有把测试类打进主 JAR。

- [ ] **Step 5: 比较 SHA-256**

用 `Get-FileHash -Algorithm SHA256` 比较 `build/libs` 中 remapped JAR 与 `D:\Backup\mc mod\临时` 同名交付 JAR。  
Expected: 两个哈希完全一致。

- [ ] **Step 6: 按验证清单完成静态 QA 并报告边界**

核对 854×480、1280×720、宽屏布局模型，翻译键、焦点/滚动代码路径、保存/返回语义、OP/未授权/损坏状态和无全员改卡入口。明确报告尚未完成的实际客户端视觉、键鼠、多人和 dedicated server 验证。
