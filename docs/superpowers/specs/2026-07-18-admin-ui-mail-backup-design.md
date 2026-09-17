# 设计规格：管理 UI 滚动/搜索 · 新建备份 · 邮件全员与奖励表单

**日期：** 2026-07-18  
**项目：** `哈比列车抽奖补齐` (`habitrain_lottery`)  
**状态：** 待实施  
**批准人：** Mike（对话确认 §1–§3）

## Context

OP 配置界面与邮件撰写在多人场景下可用性不足：

- **玩家**页列表虽有 `ScrollableButtonList`，但缺少按名搜索。
- **倍率**页字段纵向堆叠，矮窗口裁切后无法滚动查看/编辑全部设置。
- **下载备份**当前把 `PlayerLotteryStore.exportBackupJson` 经网络发到客户端 `config/habitrain_lottery/backups/`，不是服务端世界内快照。
- **邮件撰写**：目标只有在线多选 / 全服在线 / 单离线名；在线列表无滑块会截断；奖励用「类型切换 + 单一数量框」，阵营卡与金币/抽数互相抢同一控件，无法一次自定义多项。

本规格在现有 Fabric 1.21 配置屏与邮件包上增量修复，不重做整套 GUI 框架。

## Goals

1. 玩家列表可搜索过滤。
2. 倍率页可上下滚动看全设置。
3. 玩家页与邮件在线列表在多人时有滑块/滚轮兼容。
4. 「下载备份」改为服务端 **新建备份**：整包复制 `world/habitrain_lottery` → `world/lottery_backup/<时间戳>/`。
5. 邮件可圈选多人 + 批量任意名字（含从未进服），奖励改为固定三项表单（金币/抽数/阵营卡可同时自定义）。

## Non-Goals

- 不做备份恢复/导入 UI（仅创建快照）。
- 不做邮件物品附件。
- 不改 usercache 写入策略；未进服玩家仍用 `OfflinePlayer:` UUID 规则落盘（与现有 `MailComposeC2SPayload.resolveOfflineUuid` 一致）。
- 不保留「下载到客户端 JSON」作为主路径（可删除或降级为无 UI 入口的旧包，避免双行为）。

## 决策摘要（已确认）

| 主题 | 选择 |
|------|------|
| 玩家搜索 | 名称包含匹配（大小写不敏感）；不要求 UUID 搜索 |
| 倍率滚动 | 整页纵向滚动视口 + 滑块 |
| 列表滑块 | 复用/加强 `ScrollableButtonList` |
| 备份内容 | 全量 `habitrain_lottery` 目录树 |
| 备份形态 | `lottery_backup/<yyyyMMdd-HHmmss>/` 时间戳快照，不覆盖旧份 |
| 备份落点 | 仅服务端世界目录；按钮文案「新建备份」；无客户端下载 |
| 邮件目标 | 在线多选 + 全选/清空 + 批量离线名（换行/逗号，任意名） |
| 邮件奖励 | 固定三项表单：金币 N、抽数 M、阵营类型 + 数量 |

## 推荐实现路线

**在现有 Screen / 网络包上增量扩展（推荐）**  
沿用 `LotteryConfigRootScreen`、`MailComposeScreen`、`ScrollableButtonList`、`MailComposeC2SPayload`、`LotteryNetwork` 备份入口。新增轻量 `ScrollablePanel`（或等价）服务倍率页；备份改为服务端目录复制；邮件 payload 扩展离线多目标与奖励字段语义（仍可编码为现有 `RewardEntry` 列表）。

**备选（不采用）**

- 拆独立 Screen / 新 GUI 库：改动面大，与现有 OP 配置风格不一致。
- 备份仅导出经济 JSON：不满足「全量 habitrain_lottery」与皮肤/邮件/背包一并快照的需求。

---

## §1 配置界面：搜索与滚动

### 1.1 玩家页搜索

- 位置：玩家页顶部工具行（「刷新列表」「新建备份」附近）增加 `EditBox` 搜索框。
- 行为：对 `ClientLotteryState.playerList.players` **按 `name` 做包含匹配**（`Locale.ROOT` 小写）；空串显示全部。
- 过滤只影响列表展示与当前选中索引映射；批量「全员±N / =N」仍针对服务端语义（在线全员），**不**因搜索框缩小作用域。
- 过滤结果变化时：若当前选中行仍在结果中则保留；否则选中结果第一项或清空详情区。

### 1.2 倍率页滚动

- `buildRatesTab` 不再把所有字段直接铺在固定 `y` 上导致裁切。
- 引入 **纵向滚动内容区**（新建小组件，例如 `ScrollablePanel`，或在 `LotteryConfigRootScreen` 内嵌同等逻辑）：
  - 视口：`contentY` … `height - footer` 之间。
  - 内容高度按字段行数计算（标签 + 输入框行高，现有约 8 行 × ~36px）。
  - 滚轮在视口内调整 `scrollOffset`；右侧细滑块与 `ScrollableButtonList` 视觉一致（`0xA057C6D6` 风格可复用）。
  - 子 `EditBox` 的实际绘制/点击 Y = 布局 Y − scroll；滚出视口的控件 `visible=false` 或不接收点击。
- `applyRatesFields` 仍从各 `EditBox` 读值；滚动不丢未应用编辑。

### 1.3 列表滑块（玩家 + 邮件在线）

- **配置玩家列表**：继续用 `playerList`（`ScrollableButtonList`）；保证 `listH` 随窗口伸缩，人多时显示滑块 + 滚轮（现有组件已支持，需验证 `mouseScrolled` 在 `LotteryConfigRootScreen` 已转发；若未转发则补上）。
- **邮件在线玩家**：去掉「画到 `height-80` 就 break」的硬截断。改为 `ScrollableButtonList`（或同款窗口化列表）展示在线名，支持多选高亮（选中用 `§a[✓]` 类标签或选中色）。
- 邮件在线区增加 **全选 / 清空** 按钮。

### 1.4 错误处理 / 边界

- 搜索无结果：列表空态文案「无匹配玩家」，详情区提示刷新或改关键字。
- 倍率内容高度 ≤ 视口：不显示滑块，scroll=0。
- 非 OP 只读：搜索与滚动仍可用；写操作按钮保持现有锁定。

---

## §2 新建备份（服务端世界快照）

### 2.1 UI

- 玩家页按钮文案：`下载备份` → **`新建备份`**。
- 点击：OP + 已连接时发 C2S 请求（可复用 `BackupRequestC2S` 语义改为「创建世界备份」，或新包名 `CreateBackupC2S`；**推荐复用请求包、改服务端处理**，减少注册面）。
- 状态栏显示服务端回执，例如：`备份已创建: <world>/lottery_backup/20260718-153022`。

### 2.2 服务端行为

1. 校验 OP（与现网 `isOp` / rates.opPermissionLevel 一致）。
2. 要求 `WorldLotteryPaths.ready()`；源目录 = `WorldLotteryPaths.root()`（即 `world/habitrain_lottery`）。
3. 目标根：`world/lottery_backup/`（与 `habitrain_lottery` **同级**，不在其内部）。
4. 快照目录名：`yyyyMMdd-HHmmss`（服务器本地时区或 UTC 需固定一种——**采用服务器默认时区 `DateTimeFormatter` + `LocalDateTime.now()`**，文件名仅数字与连字符）。
5. 递归复制源树到目标（`Files.walk` + `Files.copy`，创建父目录；跳过若源不存在则创建空快照并仍记成功或返回「源目录不存在」失败——**源不存在 → 失败回执**）。
6. 不删除旧时间戳目录。
7. **不再** `BackupDataS2C` 下发整包 JSON；成功用 `AdminActionResultS2C`（或等价）回传路径字符串。若客户端仍注册 `BackupDataS2C`，可保留解码但 UI 不再依赖。

### 2.3 范围说明

- 复制内容 = 当时磁盘上 `habitrain_lottery` 全树（玩家经济/皮肤解锁、mail、records、backpack 等）。
- 不单独再导一份 `exportBackupJson`；内存未 flush 的脏数据应在复制前 **flush 全部脏玩家**（调用 `PlayerLotteryStore` 现有 flush-all 若有；否则对 cache 中 dirty UUID flush），尽量保证快照一致。

### 2.4 错误处理

| 情况 | 回执 |
|------|------|
| 非 OP | 需要 OP 才能备份 |
| 世界路径未就绪 | 备份失败: 世界未加载 |
| 源目录缺失 | 备份失败: habitrain_lottery 不存在 |
| IO 异常 | 备份失败: \<message\> |
| 成功 | 备份已创建: lottery_backup/\<ts\> |

---

## §3 邮件撰写：目标与奖励

### 3.1 目标模式（保留 3 档，增强语义）

| mode | 标签 | 行为 |
|------|------|------|
| `MODE_ONLINE_LIST` (0) | 目标:在线多选 | 可滚动多选 + 全选/清空；`targets` = 勾选名 |
| `MODE_ALL_ONLINE` (1) | 目标:全服在线 | 忽略列表，服务端对当前在线全员发送 |
| `MODE_OFFLINE_NAME` (2) | 目标:离线/名字 | **批量名字**：`offlineBox` 改为多行（`MultilineTextArea` 或加高 EditBox + 解析）；按换行、逗号、分号、空白拆分；去重；每个名字在线则 `send`，否则 `resolveOfflineUuid` + `sendOffline`（**任意名含未进服**，fallback `OfflinePlayer:` UUID） |

### 3.2 奖励固定三项表单

右侧（或主表单下方）固定控件，**取消**「奖励类型循环按钮 + 单一数量 + 添加奖励列表」的互斥逻辑：

- `金币` 数量框（默认 `0`，整数，可为负若现网允许扣币——**与现 `MailReward`/apply 一致：允许非 0 整数；0 表示不发该项**）
- `抽数` 数量框（同上）
- `阵营卡`：类型循环按钮（`killer` / `civilian` / `neutral` / `neutral_for_killer`）+ 数量框（默认 `0`）

发送时组装 `List<RewardEntry>`：

- 抽数 ≠ 0 → `DRAWS`
- 金币 ≠ 0 → `COINS`
- 阵营数量 ≠ 0 → `FACTION_CARD` + 当前类型

允许一封邮件同时带多项。三项全 0 时允许纯文本邮件（与现网「可无奖励」一致）。

可移除「添加奖励」列表 UI；若保留只读摘要，仅在发送前由三项生成预览即可。

### 3.3 协议

- **优先保持** `MailComposeC2SPayload` 字段形状：`targetMode` + `targets` + `rewards[]`。
- 离线批量：客户端把拆好的名字全部放入 `targets`（注意现 codec 对 targets 有 `min(64, …)` 上限——**若批量可能 >64，将上限提高到合理值如 256**，并在 UI 超限时提示截断或拒绝发送）。
- 服务端 `MODE_OFFLINE_NAME` 循环已支持多 `targets`；确认与客户端批量一致即可。
- `MODE_ONLINE_LIST` 仍仅在线 `getPlayerByName`；找不到计 fail。

### 3.4 错误处理

- 在线多选且未勾选：提示先选择玩家。
- 离线批量且解析后为空：提示输入玩家名。
- 标题空：拒绝发送。
- 服务端回执沿用「成功 ok，失败 fail」计数。

---

## 组件与文件锚点

| 区域 | 主要文件 |
|------|----------|
| 配置根屏 / 玩家 / 倍率 | `client/gui/LotteryConfigRootScreen.java` |
| 可滚动列表 | `client/gui/ScrollableButtonList.java`（+ 可选 `ScrollablePanel.java`） |
| 邮件 UI | `client/gui/MailComposeScreen.java` |
| 备份网络 | `network/LotteryNetwork.java`（`handleBackup`）、`client/LotteryClientNetwork.java` |
| 世界路径 | `storage/WorldLotteryPaths.java`（目标父目录 = world 根） |
| 玩家数据 flush | `storage/PlayerLotteryStore.java` |
| 邮件协议/发送 | `network/MailComposeC2SPayload.java`、`mail/MailService.java` |
| 文案 | `assets/habitrain_lottery/lang/zh_cn.json`（若有键则改；按钮现多为硬编码中文） |

## 数据流

```text
[玩家页 新建备份] --C2S BackupRequest--> [Server handleBackup]
  -> flush dirty players
  -> copy world/habitrain_lottery -> world/lottery_backup/<ts>/
  --S2C AdminActionResult--> [status 文案]

[邮件 发送] --C2S MailCompose--> [handle]
  ONLINE_LIST: targets 在线发送
  ALL_ONLINE: 全在线
  OFFLINE_NAME: 每名在线或 offline UUID 落盘
  rewards: 由三项表单生成的 0..3 条 RewardEntry
```

## 测试要点

1. **搜索**：多名玩家时按子串过滤；清空恢复；选中行在过滤后仍合理。
2. **倍率滚动**：缩小窗口后滚轮/滑块可编辑最底部 OP 等级字段并「应用本页」生效。
3. **玩家列表滑块**：模拟 20+ 行可滚到底。
4. **新建备份**：生成 `lottery_backup/<ts>/` 且含 players/mail 等子树；连点两次产生两个时间戳目录；非 OP 拒绝。
5. **邮件在线**：20+ 在线可滚动勾选；全选/清空。
6. **邮件批量离线**：`Alice,Bob\nNeverJoined` 拆成 3 目标；未进服有 mail JSON。
7. **奖励三项**：仅金币、仅阵营、三者同时、全 0 纯文本，领取后数值/阵营卡正确。
8. **构建**：`./gradlew clean build`，JAR 复制到 `D:\Backup\mc mod\临时\`（仓库 CLAUDE.md 强制规则）。

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| 全量目录复制大、阻塞服主线程 | 快照在 server execute 线程；目录过大可后续改异步，首版同步 + 日志耗时 |
| targets 上限 64 不够 | 提高 codec 上限并 UI 校验 |
| 滚动视口与 1.21 `renderBackground` 二次模糊 | 沿用现有 `suppressNestedBackground` 模式 |
| 未 flush 导致备份旧数据 | 复制前 flush dirty |

## 实施顺序建议

1. `ScrollablePanel` + 倍率页滚动；玩家列表 `mouseScrolled` 转发核查。  
2. 玩家搜索框。  
3. 新建备份服务端复制 + 按钮文案/回执。  
4. 邮件在线滚动多选 + 全选/清空。  
5. 离线批量解析 + targets 上限。  
6. 奖励固定三项表单。  
7. 构建与手动验收清单。
