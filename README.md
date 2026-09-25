# 哈比列车抽奖补齐 (habitrain_lottery)

Fabric 1.21.1 账户功能模组。当前版本提供独立皮肤目录与皮肤特效 API、绿苹果账户货币、角色卡、称号、邮件、每日任务、登录月历和账户仓库。

## 功能边界

- 绿苹果是本模组自己的账户货币，只通过每日登录任务、邮件、管理员命令和公开玩家 API 增减。登录后不再自动发放任何奖励，奖励需在每日任务终端手动领取。
- 上游的**账户金币**在本模组加载后只减不增：胜负结算、升级、巫师 / 亡灵之主 / 操纵师事件与旧抽奖折算等所有游戏内发放都被关闭，仅保留维修模式职业解锁的扣费；余额仍以网站端数据库为权威源。
- 角色卡、称号、皮肤解锁与装备、特殊系统道具都保存到世界目录，不依赖上游数据库。
- 皮肤由扩展模组通过 `habitrain_lottery_skins` 入口点注册；本模组不内置皮肤内容。
- 邮件支持绿苹果、角色卡、自选卡、突破上限卡和皮肤附件，并对领取过程做原子回滚。
- 每日任务由本模组内置一条「每日登录」（登录即达标，160 绿苹果）并由其他模组注册更多任务，任务进度和奖励回调在服务端执行。
- 仓库只展示账户奖励，不读取玩家背包、末影箱或世界容器。

旧金币、抽数、兑换、奖池和抽奖终端已从本模组移除。旧玩家 JSON 中的这些字段会在服务器启动时删除，余额不会转换成绿苹果；旧邮件中的相应奖励会被忽略，其他附件仍可领取。

登录奖励在 1.1.29 改版：旧的「每日登录白送 4 张阵营卡」和「连续登录自动发绿苹果」都已取消，统一为每日任务终端上的内置任务「每日登录」（160 绿苹果，登录后手动领取）。每日阵营卡的 4 次/天使用上限与突破上限卡机制保持不变。

## 安装与构建

运行环境：Minecraft 1.21.1、Fabric Loader、Java 21、Fabric API、StarRailExpress 4.3.x 和 `habitrain_core >=2.0.19`。

```text
mods/
  habitrain_lottery-1.1.29.jar
  star_rail_express-4.3.0.jar
  habitrain_core-2.0.19.jar
```

```powershell
./gradlew build
```

`assemble` 会把最终 jar 复制到项目旁的 `临时` 目录。`check` 还会验证独立皮肤目录没有重新依赖上游实现或旧资源。

## 世界数据

```text
<world>/habitrain_lottery/
  players/<uuid>.json
  mail/players/<uuid>.json
  backpack/players/<uuid>.json
  records/
  titles/
```

玩家主文件只保存绿苹果、皮肤状态、登录状态、任务进度、去重键和其他仍在使用的账户资产。写入失败会恢复内存快照并返回失败结果。

## 管理命令

命令根为 `/hlt`，`/habitrain_lottery` 是同义别名。

- `/hlt green_apples <player> <amount>`：按增量调整绿苹果。
- `/hlt system_item <player> <namespace:item> <amount>`：发放特殊系统道具。
- `/hlt inspect <player>`：查看绿苹果、皮肤解锁数量和当前装备。
- `/hlt skins`：打开皮肤衣柜。
- `/hlt skins unlock|lock <players> <type> <id>`：管理皮肤解锁状态。
- `/hlt skins reregister`：重新运行皮肤扩展注册器。
- `/hlt mail`：打开管理员邮件撰写页。
- `/hlt mailbox`：打开玩家邮箱。

修改账户、邮件和皮肤的命令需要 OP；专用服务器还需要核心的 MenuGate 授权。

## API 文档

- [玩家资产 API](docs/player-api.md)
- [皮肤 API v3](docs/skin-api.md)
- [皮肤特效 API v1](docs/skin-effects-api.md)
- [每日任务 API v1](docs/daily-task-board.md)
- [账户仓库与特殊道具](docs/account-warehouse.md)

皮肤 API 只负责注册、显示、解锁和装备，不提供任何随机奖励入口。绿苹果 API 的完整方法名是 `getGreenApples`、`setGreenApples`、`addGreenApples` 和 `grantGreenApples`。
