# 设计规格：称号（NameTag）管理系统 — 本地化桥接 + Mod Menu

**日期：** 2026-07-18  
**项目：** `哈比列车抽奖补齐` (`habitrain_lottery`)  
**状态：** 待实施  
**批准人：** Mike（对话确认 §1–§3）

## Context

SRE 原版已有「名片/称号」系统：

- CCA：`net.exmo.sre.nametag.NameTagInventoryComponent`  
  - `nameTags: List<String>`（拥有的称号，字符串为显示文本，可含 `§` 颜色码 / 翻译 key）  
  - `CurrentNameTag`（当前佩戴）  
- 显示：`PlayerPrefixMixin` 在 `getDisplayName` 前插入 `generate()` 前缀（名牌/显示名）  
- 玩家佩戴：客户端 `UpdateNameTagSelectedPayload`（须已拥有）  
- OP 命令：`/nametag:add|remove|set|get|list|clear`  
- 持久化原路径：玩家 NBT + 可选 MySQL（`MysqlPlayerDataStore` key `nametags`）

补齐 mod 已将抽奖/皮肤/邮件/阵营卡改为 `world/habitrain_lottery/` 权威，并用「新建备份」整包复制该目录。称号目前**未**纳入本地权威与 Mod Menu。

本规格：在**不替换原版佩戴 UI** 的前提下，用本地世界 JSON 权威 + Mod Menu 管理（模板库 + 玩家 CRUD），并自动进入备份范围。

## Goals

1. Mod Menu（现有配置根屏）增加「称号」Tab：模板库 + 玩家授予/收回/设佩戴/清空。  
2. 数据存于 `world/habitrain_lottery/titles/`，与抽奖等同目录树。  
3. 「新建备份」无需改逻辑即可备份称号（全树复制已覆盖）。  
4. 玩家继续用原版界面在已拥有称号中佩戴；显示仍走原版前缀。  
5. 本地 JSON 覆盖 MySQL 对 nametag 的拉取覆盖（与皮肤/背包策略一致）。

## Non-Goals

- 不重做玩家佩戴 GUI。  
- 不重做聊天/名牌渲染管线（沿用 `PlayerPrefixMixin` + `generate()`）。  
- 不实现跨服 MySQL 同步称号。  
- 不做称号解锁条件/商城购买（首版仅 OP 授予）。  
- 不改 `/nametag:*` 命令注册（可选后续让命令也写本地；首版以 Mod Menu + 本地桥接为主，命令若仍写 CCA，应由 mixin/钩子回写本地）。

## 决策摘要（已确认）

| 主题 | 选择 |
|------|------|
| 显示范围 | 名牌 + Tab + 聊天（原版 displayName 前缀路径） |
| 与原版关系 | **本地化桥接** `NameTagInventoryComponent` |
| 玩家佩戴 | 原版 UI / `UpdateNameTagSelectedPayload` |
| 管理入口 | Mod Menu → 配置根屏新 Tab「称号」 |
| 管理范围 | **模板库 + 玩家 CRUD** |
| 存储 | `world/habitrain_lottery/titles/` |
| 备份 | 落在现有 `lottery_backup/<ts>/` 全树复制内 |

## 推荐路线

**A. 本地权威桥接原版（采用）** — 见 Goals。  

不采用：仅 GUI 包命令（备份不牢）；完全自建显示层（双前缀风险）。

---

## §1 存储与备份

### 1.1 目录

```
world/habitrain_lottery/
  titles/
    catalog.json              # 全局模板库
    players/
      <uuid>.json             # 每玩家拥有 + 当前佩戴
```

路径助手：在 `MetaFeaturePaths` 或新建 `TitlePaths` 中提供 `titlesDir()` / `catalogFile()` / `playerFile(UUID)`，并在 `ensureDirs` 时创建。

### 1.2 catalog.json

```json
{
  "version": 1,
  "titles": [
    {
      "id": "owner",
      "display": "§6[服主]",
      "enabled": true
    }
  ]
}
```

- `id`：稳定键，管理 UI 用；**授予玩家时写入 CCA 的是 `display` 字符串**（与原版 `nameTags` 语义一致：列表元素即显示/翻译 key 文本）。  
- `display`：原版 `generate()` 使用 `Component.translatable(CurrentNameTag)` — **注意**：原版把字符串当 **translation key**。若自定义 `§6[服主]` 非合法 key，客户端可能显示 raw key 或异常。  

**兼容策略（必须实现其一并写死）：**

- **首选（与现网命令一致）：** 存储/授予的字符串与 `/nametag:add` 相同 — `ComponentArgument` 解析后的 **`getString()`** 结果。原版 `generate()` 用 `Component.translatable(CurrentNameTag)`。若实测彩色字不显示，则在**本 mod 侧**对 `generate` 或前缀注入做小 mixin：当字符串含 `§` 或非 `namespace:path` 形态时改用 `Component.literal` 解析 § 码；否则保持 `translatable`。  
- 模板 `id` 仅用于管理，**不**写入 `nameTags`（避免玩家列表出现 id 而非显示文案）。

### 1.3 players/\<uuid\>.json

```json
{
  "version": 1,
  "owned": ["§6[服主]", "§b[赞助]"],
  "current": "§6[服主]",
  "updatedAt": 0
}
```

- `owned`：与 CCA `nameTags` 对齐的字符串列表。  
- `current`：与 `CurrentNameTag` 对齐；空串表示未佩戴。  
- 权威：磁盘；内存缓存可选。

### 1.4 备份

现有 `LotteryBackupService.copyTree(WorldLotteryPaths.root(), dest)` 已递归复制整个 `habitrain_lottery`，**无需改备份代码**即可包含 `titles/`。验收时确认快照内存在 `titles/`。

复制前已有 `PlayerLotteryStore.flushAll()`；称号应在 flush 钩子中一并 `TitleStore.flushAll()`（或进备份前显式 flush 称号脏数据）。

---

## §2 Mod Menu / 配置 UI

### 2.1 入口

- 保持 `ModMenuIntegration` → `LotteryConfigRootScreen`。  
- `TABS` 增加 **「称号」**（建议插在「邮箱」与「JSON」之间，或「玩家」后；最终顺序：`奖池, 倍率, 发次, 画面, 玩家, 皮肤, 邮箱, 称号, JSON`）。  
- OP 可写；非 OP 只读浏览（与现网 `applyOpWidgetLocks` 一致）。

### 2.2 布局（单 Tab 两栏）

**左：模板库**

- `ScrollableButtonList` 列出 catalog（显示 `id` + 截断 `display`）。  
- 控件：+模板 / -模板；编辑 `id`、`display`（EditBox，支持 `§`）；启用开关可选。  
- 应用/保存：写入内存 catalog + 请求服务端保存（见网络）。

**右：玩家称号**

- 复用玩家列表数据源（`ClientLotteryState.playerList`）或本 Tab 专用刷新；支持搜索/滚动（可复用玩家页模式）。  
- 选中玩家后显示：已拥有列表（可滚动）、当前佩戴高亮。  
- 操作：  
  - **授予模板**：将选中模板的 `display` 加入 owned（服务端去重）。  
  - **授予自定义**：输入框文本加入 owned。  
  - **收回**：从 owned 删除；若为 current 则清空 current。  
  - **设为佩戴**：`current = 选中 owned 项`（须已拥有）。  
  - **清空**：owned 清空 + current 空。  

### 2.3 网络（建议）

新增 OP 门禁包（命名示例，实施时可并入 `LotteryNetwork`）：

| 方向 | 用途 |
|------|------|
| C2S `TitleCatalogSave` | 保存整份 catalog JSON |
| C2S `TitlePlayerModify` | mode: grant_display / revoke / set_current / clear；target uuid；string payload |
| C2S `TitleSnapshotRequest` | 请求 catalog + 可选某玩家或列表摘要 |
| S2C `TitleSnapshot` | catalog + 管理用玩家称号摘要（或按需单玩家） |
| S2C `AdminActionResult` | 复用现有状态回执 |

进配置 Tab 时 OP 自动请求快照。保存到服务器按钮可：当前 Tab 为称号时提交 catalog + 提示玩家修改已即时下发（玩家修改建议即时 C2S，不必等「保存到服务器」——与玩家抽数修改一致）。

**推荐行为：**

- 模板库：改完点「应用本页」写客户端内存，「保存到服务器」持久化 catalog。  
- 玩家授予/收回/佩戴/清空：**即时 C2S**，服务端改 CCA + 写本地 JSON + 回执。

---

## §3 运行时桥接

### 3.1 加载（进服）

1. `WorldLotteryPaths.ready()` 后确保 `titles/` 目录。  
2. 玩家加入：`LocalTitleStore.load(uuid)` → 得到 owned/current。  
3. 写入 CCA：  
   - 清空后 `addNameTag` 各 owned，或直接替换 list + set current（优先用组件公开 API：`clear`/`addNameTag`/`setCurrentNameTag`；若 clear 过猛可用反射/mixin 批量替换后 `sync()`）。  
4. **禁止**随后 MySQL `applyNetworkNametagData` 覆盖本地：mixin `NameTagInventoryComponent.syncFromLinkedServer` / `applyNetworkNametagData` / 或 `initializeNetworkSync` 在本地权威开启时 no-op（与 `SrePlayerSkinsMysqlMixin` 同类）。

### 3.2 保存（变更）

任一路径修改 owned/current 后：

1. 更新 CCA 并 `sync()`。  
2. `LocalTitleStore.save(uuid, owned, current)`。  

覆盖路径：

- Mod Menu C2S 处理  
- 玩家 `UpdateNameTagSelectedPayload` 成功后（mixin RETURN 或包装）  
- `/nametag:*` 命令（mixin `addNameTag`/`removeNameTag`/`setCurrentNameTag`/`clear` RETURN）— **推荐统一在 CCA 写方法 RETURN 上 persist**，一处覆盖全部入口。

### 3.3 显示

不改 `PlayerPrefixMixin` 除非 §1.2 的 literal/§ 兼容 mixin 需要。  
Tab 列表名是否带前缀取决于原版/其他 mixin；本规格不额外接 Tab 专用 API，以 `getDisplayName` 路径为准（Mike 已选名牌+Tab+聊天，与现网 displayName 挂钩）。

### 3.4 离线授予

Mod Menu 对离线玩家：只写 `titles/players/<uuid>.json`；其进服时再推 CCA。在线则立即推 CCA。

---

## 组件锚点

| 区域 | 位置 |
|------|------|
| 路径 | `storage/TitlePaths.java` 或扩展 `MetaFeaturePaths` |
| 存储 | `title/LocalTitleStore.java` + catalog 模型 |
| 服务 | `title/TitleService.java`（grant/revoke/set/clear/loadToPlayer） |
| Mixin | `mixin/NameTagInventoryPersistMixin`（变更回写）；`mixin/NameTagMysqlBypassMixin`（禁 MySQL 覆盖） |
| 网络 | `LotteryNetwork` 或 `title` 包下 payload |
| UI | `LotteryConfigRootScreen` 新 Tab；可选小组件 |
| 备份 flush | `LotteryBackupService.createTimestampedBackup` 前 `TitleStore.flushAll` |
| 语言 | 按钮硬编码中文即可（与现 UI 一致） |

## 数据流

```text
[Mod Menu 称号 Tab]
  catalog 保存 --C2S--> TitleService.saveCatalog --> titles/catalog.json
  授予/收回/佩戴 --C2S--> TitleService.modify(player|offline)
       --> LocalTitleStore.save
       --> (online) NameTagInventoryComponent + sync()

[玩家原版佩戴 UI] --UpdateNameTagSelectedPayload--> CCA setCurrent
       --> mixin persist --> LocalTitleStore.save

[进服] LocalTitleStore.load --> CCA replace --> (skip MySQL pull)

[新建备份] flush titles + copyTree(habitrain_lottery) 含 titles/
```

## 错误处理

| 情况 | 行为 |
|------|------|
| 非 OP 修改 | 拒绝 + 回执 |
| 授予空 display | 拒绝 |
| 设佩戴但不在 owned | 拒绝 |
| catalog id 重复 | 拒绝或覆盖策略：**拒绝并提示** |
| 文件损坏 | 记日志，玩家视为空 owned |
| 世界未就绪 | 操作失败回执 |

## 测试要点

1. 模板增删改，重启世界后 catalog 仍在。  
2. 在线授予 → 原版佩戴 UI 可见 → 佩戴后名牌前缀变化。  
3. 收回当前佩戴 → current 清空。  
4. 离线授予 → 进服后拥有。  
5. 断 MySQL / 开 MySQL：进服不被远端空数据刷掉本地称号。  
6. 新建备份快照内含 `titles/catalog.json` 与 `titles/players/*.json`。  
7. 非 OP 无法修改。  
8. `./gradlew clean build`，JAR 复制到 `D:\Backup\mc mod\临时\`。

## 风险

| 风险 | 缓解 |
|------|------|
| `translatable` 对 `§` 文本不友好 | §1.2 literal 兼容 mixin |
| CCA clear 与游戏中 spectator 前缀 | 只替换 nameTags/current，不破坏 generate 的 spectator 逻辑 |
| 与 MySQL 竞态 | 禁用 nametag MySQL apply；本地 last-write |
| Tab 栏过多挤布局 | 缩小 tab 字宽 / 保持现有均分 |

## 实施顺序建议

1. `TitlePaths` + `LocalTitleStore` + 单测（读写/去重）。  
2. `TitleService` + 进服加载 + CCA 写方法 persist mixin + MySQL bypass。  
3. 网络包 + OP 处理。  
4. 配置屏「称号」Tab UI。  
5. 备份前 flush 称号。  
6. § 显示兼容（若需要）。  
7. 全量构建与进服验收。
