# 设计规格：激活原版大厅元功能（本地化桥接）

**日期：** 2026-07-17  
**项目：** `哈比列车抽奖补齐` (`habitrain_lottery`)  
**状态：** 已实施

## Context

SRE 大厅提供对局记录、地图介绍、地图轮换、邮箱管理；在无 MySQL / stats 关闭时功能失效。抽奖补齐已将抽数/金币/皮肤改为 world JSON 权威。本规格用同一模式本地化邮件/战绩/阵营卡，并将地图轮换跳转到哈比 API 投票设置；邮箱提供完整 OP 发信 GUI。

## 决策

- 路线：补齐 mod 本地化桥接（mixin）
- 存储：`{world}/habitrain_lottery/{mail,records,backpack}/`
- 地图轮换 → `habitrain_core` 投票设置（无 core 时 fallback）
- 发信 GUI：`/hlt mail` + Mod Menu「邮箱」
- 首版不做物品附件；支持离线邮件
- 阵营卡始终本地权威

## 实现锚点

见 `docs/superpowers/plans/2026-07-17-hub-meta-features-local-bridge-plan.md` 与源码包：

- `com.habitrain.lottery.mail.*`
- `com.habitrain.lottery.record.LocalMatchRecordStore`
- `com.habitrain.lottery.backpack.LocalBackpackStore`
- `com.habitrain.lottery.meta.HabiCoreMenuBridge`
- 相关 mixin 于 `mixin/` 与 `mixin/client/`
