# 哈比列车抽奖补齐 (habitrain_lottery)

Fabric 1.21.1 管理/补齐模组：提供独立皮肤组件、把抽奖相关玩家数据权威存到 **world 根目录**，并通过 Mod Menu（仅 OP）配置奖池、倍率、发次与画面主题。

## 1.1.15 独立皮肤衣柜

- 直接接管上游菜单、皮肤快捷键及服务端打开皮肤页面的指令，原入口统一打开新衣柜。
- 六个分类保留武器、帽子与称号；皮肤卡片、模型预览、名称/ID 搜索、拥有状态筛选与排序。
- 滚轮或 Page Up / Page Down 翻页，Ctrl+F 搜索，Esc 或原皮肤快捷键返回。
- 装备状态以服务器回执为准；支持缺少资源、同步超时提示与恢复默认。帽子分类保留本地显示范围设置。
- 客户端与服务端一同升级至 1.1.15。

## 1.1.14 独立皮肤组件 / API v2

- 其他 Fabric 模组可通过 `habitrain_lottery_skins` 入口点和 `HabiSkinApi` 注册皮肤。
- 支持扩展模组自己的模型命名空间，以及可选的类型池/全随机池运行时注入。
- API 奖池条目不会写入世界配置，移除扩展模组后不会留下失效项。
- 完整接入示例、资源路径和兼容规则见 [`docs/skin-api.md`](docs/skin-api.md)。

## 1.1.6 玩家数据 API

- 其他 Fabric 模组可通过 `com.habitrain.lottery.api.player` 下的 `HabiLotteryApi` / `HabiCardApi` / `HabiSkinPlayerApi` / `HabiTitleApi` / `HabiMailApi` 读写单个玩家的金币、抽数、皮肤解锁与装备、阵营卡/自选卡/突破上限卡、称号，并投递带奖励的邮件。
- 写入遵循“权威 world JSON 落盘 + 失败回滚”，在线玩家自动同步对应资产；皮肤使用本模组独立协议；只允许在服务端主线程调用；世界未就绪时返回 `NOT_READY` 而不抛异常。
- 完整方法表、数值边界与示例见 [`docs/player-api.md`](docs/player-api.md)。

## 1.0.6 皮肤连抽

- 抽奖页面提供单抽、十连抽、五十连抽；服务端每次最多处理 50 抽。
- 沿用原有逐抽费用、概率、奖励与抽数不足时按可负担次数抽取的规则。
- 连抽结果保留上游揭示动画，每页 5 个结果，支持前后翻页；翻页不重复抽奖或扣费。
- 客户端和服务端均需更新本模组至 1.0.6，以支持新按钮及 50 条结果包。无需修改上游模组。

## 运行依赖

- Minecraft 1.21.1 / Fabric Loader ≥0.18.1 / Fabric API
- **starrailexpress**（SRE 4.3.0，抽奖界面与非皮肤功能）
- **habitrain_core**
- **modmenu**（可选，仅客户端；专用服务器不需要）

本模组已移除全部内置皮肤及旧替换组件。皮肤由 API v2 扩展提供，不加载旧 skins.json，也不向 SRE 皮肤系统注册。上游皮肤菜单、快捷键与打开指令均进入独立衣柜，也可使用 `/hlt skins`；邮件第 4 页支持填写皮肤附件。

## 快速开始（只装 JAR）

1. 把 `habitrain_lottery-*.jar` 放进 `mods/`（连同依赖）
2. 进世界即可——**无需手动复制配置**
3. 首次进入时，模组从 JAR 内默认配置自动 seed 到 world
4. 旧世界（配置版本 &lt; 4）会自动备份到 `backup_vN/` 并迁移到最新默认奖池

## World 存档

```
{world}/habitrain_lottery/
  config/
    pools.json
    rates.json
    grants.json
    theme.json
    skins_quality.json
    meta.json                 # config_version、migratedAt
    backup_v1/                # 迁移前旧配置备份（如有）
    backup_vN/                # 其它历史版本备份
  players/<uuid>.json
  history/<uuid>.jsonl
```

- **JAR-only auto-seed**：新世界缺文件时从 `data/habitrain_lottery/defaults/` 写入
- **version migrate**：`meta.json` 的 `config_version` &lt; 当前版本（**4**）时，先备份再迁移（强制刷新 pools；必要时 `coinPerDraw`→160；补 `duplicateCoinFlat`；v4 强制刷新 `grants.json` 为阵营胜利发次表）

## 对局发次（胜利）

仅发给结算中判定为**获胜**的玩家（读 `SREGameRoundEndComponent.didWin`）：

| 阵营 | 事件 id | 抽数 |
|------|---------|------|
| 乘客（无辜/好人） | `win_passenger` | **1** |
| 杀手 | `win_killer` | **2** |
| 中立（含自定义胜者/独立胜） | `win_neutral` | **5** |

另有参与奖：`sre_participate` / `blackout_participate` 各 1 抽（可在 `grants.json` 关闭）。

## 抽次价格

**160 金币 = 1 抽**（`rates.json` → `coinPerDraw: 160`）

## 奖池设计（类型池 v3）

| 池 | 内容 | 概率 |
|----|------|------|
| 刀池 | 扩展注册的刀皮肤 | 皮肤 **30%** / 金币 **70%** |
| 枪池 | 扩展注册的枪皮肤（配置键 `gun/...`） | 30% / 70% |
| 棍池 | 扩展注册的棍子皮肤 | 30% / 70% |
| 手雷池 | 扩展注册的手雷皮肤 | 30% / 70% |
| 完全随机 | 扩展注册的全部皮肤 | 皮肤 **50%** / 金币 **50%** |

- 默认奖励列表为空，无有效奖励的池自动禁用；安装扩展或配置有效奖励后才可抽取。表中概率为预置结构。

- 皮肤**可重复**；重复时固定返还 **60 金币**（`rates.json` → `duplicateCoinFlat: 60`）
- 每个池有独立封面：`pool_bg0.png` … `pool_bg4.png`

## 如何改配置

1. 编辑 world 下文件：`{world}/habitrain_lottery/config/*.json`  
   或用 Mod Menu（仅 OP）在线改奖池 / 倍率 / 发次 / 画面
2. 执行 **`/hlt reload`**（或 `/habitrain_lottery reload`）使改动生效
3. 也可从导出包覆盖：把 `临时/habitrain_lottery_config/` 里的 JSON 复制进 `config/` 后 reload

参考导出包内 `SETUP.txt`。

## Mod Menu

打开后可编辑：

1. **奖池** — SRE 兼容 JSON（`Pools` / `QualityListGroup` / 概率和≈1）
2. **倍率** — 抽次消耗、重复转币、模式倍率、OP 权限等级
3. **发次** — 事件表（参与发次 + 阵营胜利发次：乘客 1 / 杀手 2 / 中立 5）
4. **画面** — 品质背景等 ResourceLocation
5. **皮肤** — 注册数量只读

**仅服务器 OP**（默认 permission level 2，可在 `rates.json` 的 `opPermissionLevel` 修改，范围 1–4）可保存/重载。专用服上门控开启时还需 MenuGate 授权。非 OP 只读。

## 打开抽奖界面

SRE 原局内菜单的抽卡入口在大厅被注释掉了。本 mod 提供：

1. **方块：`habitrain_lottery:gacha_terminal`（抽奖终端）**  
   - 创造模式：功能方块 / OP 方块标签页  
   - **右键**打开 SRE 皮肤抽奖页（`LootInfoScreen`）  
   - 奖池数据来自服务端 ConfigSnapshot（JOIN / 重载已同步），打开界面不再额外请求 `LootPoolsInfoCheck`  
2. **命令（所有玩家）**  
   - `/hlt open` 或 `/habitrain_lottery open`（旁观或死亡时拒绝打开）

## 命令（OP）

- `/hlt open` — 打开抽奖界面（无需 OP）
- `/hlt reload` 或 `/habitrain_lottery reload`
- `/hlt grant <player> <amount>`
- `/hlt inspect <player>`
- `/hlt migrate <player>`
- `/hlt skins` — 打开独立衣柜；搜索、装备、恢复默认
- `/hlt skins unlock <players> <type> <skin>` — 为在线玩家解锁指定皮肤
- `/hlt skins lock <players> <type> <skin>` — 撤销指定皮肤解锁；若正在装备该皮肤，恢复默认外观

例如 `/hlt skins unlock Mike knife example_crystal`；需先安装注册了该 ID 的扩展。
`players` 支持在线玩家名与 `@a`、`@p` 等玩家选择器；类型和皮肤 ID 支持 Tab 补全，
`gun` 与 `revolver` 通用。默认皮肤始终可用，不能撤销；未注册的皮肤 ID 会被拒绝。
重复执行同一指令是安全的，不返还金币、不自动装备新解锁皮肤。
两条指令均沿用 OP 等级和专用服 MenuGate 权限，可从控制台执行；
也可用 `/habitrain_lottery` 代替 `/hlt`。每名玩家独立保存，失败会单独提示并回滚该玩家修改；
保存成功后同步皮肤列表及背包外观，重进服务器后继续生效。

## 玩家皮肤/经济存档（权威）

玩家抽数、金币、解锁皮肤、装备皮肤只存在：

```
{world}/habitrain_lottery/players/<uuid>.json
```

- 进服加载权威 JSON，使用本模组网络与物品组件同步皮肤（文件不存在=空皮肤库）。
- **忽略** star 列车（SRE）MySQL / 网络皮肤同步
- **不再**从 SRE 自动迁移旧数据到 world（避免删了文件夹又被 SQL 写回）
- 清空皮肤：删除对应 `players/<uuid>.json`（或整个 `players/`），重启后进服即可

## 与 SRE 的关系（两扇门）

本 mod 对 SRE 有两扇独立的门，不要混为一谈：

1. **MySQL 玩家同步 mixin — 始终关闭**  
   无论 world JSON 是否已接管，SRE 的 MySQL / 网络皮肤同步都被本 mod mixin 强制关掉。
2. **world JSON 经济接管 — 仅在 `SERVER_STARTED` 之后**  
   `PlayerLotteryStore.isTakeoverActive()` 为 true 后，`PlayerEconomyManager` 读写才以 world JSON 为准。  
   接管未激活时，逻辑服务端的经济**写**会被取消（不再静默改 SRE 内存）；读仍可 fall-through。客户端线程 / LocalPlayer 不受影响。

其它：

- 抽奖 UI/算法仍用 noellesroles `LotteryManager` + Loot 界面
- 本 mod 把 world 的 `pools.json` 同步到 SRE 的 `lottery_skin_data/lottery_pool.json` 后 `reload()`

## 大厅元功能（本地化）

本 mod 将 SRE 大厅相关功能改为 world 本地权威，不依赖 MySQL：

| 功能 | 行为 |
|------|------|
| 对局记录 | `{world}/habitrain_lottery/records/`，结束对局自动落盘；大厅「战绩」可读 |
| 地图介绍 | 仍用 `train_maps/`；无数据时聊天提示 |
| 地图轮换 | 请用 **哈比列车核心** Mod Menu → **地图设置**（需 OP / `habitrain_core`）。本 mod 不再提供大厅跳转 fallback |
| 邮箱 | `{world}/habitrain_lottery/mail/players/`；OP `/hlt mail` 或 Mod Menu「邮箱」撰写 |

**邮件奖励类型：** 抽数、金币、四种阵营卡（`killer` / `civilian` / `neutral` / `neutral_for_killer`）、独立自选卡（`SELF_SELECT_CARD`）以及突破上限卡（`LIMIT_BREAK_CARD`）。在线、离线和全服收件模式均支持三类卡牌附件。  
**卡牌本地权威：** `{world}/habitrain_lottery/backpack/players/`；四种阵营卡保存在 `cards`，自选卡与突破上限卡分别保存在独立的 `selfSelectCards` / `limitBreakCards`。

**自选卡与阵营卡：** 背包提供独立入口，自选角色每次消耗 1 张自选卡，不消耗任何阵营卡；阵营卡只用于对应阵营的随机职业；突破上限卡每次消耗 1 张，使今日阵营卡可用次数 +1。阵营卡与自选卡分别计算每日使用次数（各 4 次），同一玩家下一局只能预约一个卡牌效果。Mod Menu 玩家资产支持分别查询、增减和设定各类余额。每日登录仍只发四种阵营卡各 1 张，不自动发自选卡。

### 相关命令

- `/hlt mail` — OP 打开发信界面
- `/hlt mailbox` — 打开自己的本地邮箱（无需 OP；审核 B-26 补记）
- `/hlt coin <player> <amount>` — OP 调整金币
- `/hlt grant <player> <amount>` — OP 调整抽数（原有）

## 网络与权限

- **远程抽奖 UI 是故意的**：`/hlt open` 与抽奖终端允许远程打开界面。实际抽奖 C2S 拒绝旁观/死亡，冷却 400ms，且只允许当前配置里 `Enable=true` 的奖池。
- **管理写路径**：配置保存/重载、发信 C2S、`/hlt reload`、`/hlt mail`、`/hlt grant`（以及 coin / inspect / migrate 等管理命令）在专用服上需要 OP **且**通过核心 MenuGate；控制台仍是 OP-only 的紧急入口。
- **配置快照**：非 OP（或门控未授权的 OP）只收到公开快照（已启用奖池 + 抽奖倍率字段），不含 `opPermissionLevel` 与发次表。
- **Mod Menu**：`fabric.mod.json` 中为 `suggests`，专用服务器不需要安装。

## 构建

```bat
gradlew.bat clean build
```

产物：`build/libs/habitrain_lottery-1.1.12.jar`，并自动复制到 `../临时/`。

## 依赖版本（审核 B-02）

- `fabric.mod.json` 的 `depends` 已钉死版本：`starrailexpress` 为 `~4.3.0`、
  `habitrain_core` 为 `>=2.0.19`。旧写法是 `"*"`，于是「核心改了/删了类而下游不知道」
  这种漂移无法被发现。
- `habitrain_core >= 2.0.19` 是硬需求：本模组不再越层引用核心实现层
  （`game.sre.EliminatedRestAreaService`），改为调用核心公开层
  `api.MatchRestStateApi`（休息区）与 `api.role.v2.RoleVisibilityApi`（角色可见性）。
  （这两个公开 API 自 core 2.0.12 起提供，2.0.19 为当前构建与验证基线。）
  绑定更老的核心时抽奖 / 邮件 / 用卡门禁会 **fail-closed**（保守拒绝）并打出 ERROR 日志，
  而不是像旧版那样静默放行。
- 服务端菜单门控同理：核心门控桥接未装配时按「已阻断」处理（`CoreSpi.isMenuGateInstalled()`）。
