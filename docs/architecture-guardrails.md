# 架构护栏与工程铁律 (Architecture Guardrails)

本文档沉淀自 `habitrain_lottery` 深度代码审查，适用于本仓库及下游附属模组开发。

## 1. Mixin 受管包零普通类铁律

- **规则**：凡在 `habitrain_lottery.mixins.json` 或 `habitrain_lottery.client.mixins.json` 中声明为 `"package"` 的包（及其所有子包），在运行期完全由 SpongePowered `MixinTransformer` 接管。**严禁**在该包及其子包内放置任何非 `@Mixin` 普通工具类、常量类、数据类或 `IMixinConfigPlugin`（Plugin 应独立置于 `com.habitrain.lottery.mixinplugin`）。
- **原因**：JUnit 单测运行在标准 `AppClassLoader` 下不会挂载 `MixinTransformer`，导致单元测试全绿而在真实客户端/服务端触发类加载瞬间抛出 `org.spongepowered.asm.mixin.transformer.throwables.IllegalClassLoadError` 直接崩服。
- **自动化守卫**：CI 与本地 `check` 通过 `MixinPackagePurityTest` 扫描编译产物，确保受管 Mixin 包内每一个顶层类均带有 `@Mixin` 注解。

## 2. 无 DB 多文件 JSON 账本“先预扣立单、后幂等投递”两阶段范式

在无关系型数据库 ACID 事务支撑的多 JSON 文件存储体系中，凡涉及“扣减资产 A 换取资产 B”的业务（如每日商店购买、箱子开启）：

1. **前置纯校验**：在落盘任何意图文件或修改内存前，先完成所有无副作用的不变式校验（`!isFlushDeferred()`、各目标 Store 非 `corrupt`、全维度对局门禁 `ServerMatchGate.hasActiveMatch`、玩家状态门禁 `PlayerStateGate.spectatorRestOrDead`、商品/皮肤/称号存在性及余额/配额不溢出）。
2. **第一阶段（主账本原子预扣 + 意图单落盘）**：在主资产账本同一次原子写入内完成货币/钥匙/箱子扣减与限购计数记录，并绑定唯一业务单号；若意图单或主账本任一落盘失败，立即补偿清理未生效意图单。
3. **第二阶段（幂等收据投递）**：通过带有界收据去重的投递器向外部 Store（背包、皮肤、称号、邮箱）发放商品；已完成投递并确认收据落盘后清理临时意图文件。
4. **重连恢复门禁对齐**：玩家重连触发的 `recoverPending` 必须遵守与实时领奖一致的对局门禁（对局进行中延后发放实时角色卡，避免重连刷卡入局），且恢复邮件附件后必须同步标记邮箱 `claimed = true`。

## 3. 双端同 JVM（单机 IntegratedServer）静态状态隔离

- 客户端单人游戏与局域网联机下，Render 线程与 IntegratedServer 线程共享同一 `ClassLoader` 的静态字段。
- 客户端网络层（`ClientPlayConnectionEvents.DISCONNECT`）严禁直接重置服务端正在 `SERVER_STOPPING` 收尾阶段读取的权威目录或账本静态单例；客户端 UI 状态应存于独立的 Client State 快照对象中，并在退服时清理（同时对 `ClientLevel` 等重型对象只允许使用 `WeakReference` 或显式 `DISCONNECT` 失效）。

## 4. 构建与进程清理规范

- 一切 Gradle 构建、测试与冒烟命令必须使用项目自带 Wrapper 并附带 `--no-daemon`（如 `.\gradlew.bat build --no-daemon`）。
- 禁止盲目删除 `fabric-loom` 下的 `.lock` 0 字节文件锁，仅清理已确认 PID 死亡的 `.pid` 记录文件。
