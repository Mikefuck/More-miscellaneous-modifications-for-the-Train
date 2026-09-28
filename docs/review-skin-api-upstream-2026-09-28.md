# 外部皮肤 API、界面入口与上游技能适配审核

后续修复：F-01 至 F-04 已在皮肤包 1.0.44 处理；详见 [修复与验证报告](../../皮肤mod/docs/review-1.0.44.md)。本文保留 1.0.43 审核基线。

审核日期：2026-09-28。基线：抽奖补齐工作区 `1.1.43`、皮肤包 `1.0.43`、Minecraft / Fabric `1.21.1`、上游发布依赖 `star_rail_express-4.3.0.jar`。

本次只审核，未修改游戏逻辑。现有工作区改动保留；结论针对本次读取和构建的工作区状态。

## 结论

外部皮肤的注册、衣柜目录、仓库资产和奖池选择器已经贯通。Mod Menu 由「哈比列车抽奖补齐」提供统一入口，扩展不会自动获得自己的配置页。玩家缩放有正常的渲染矩阵继承，但帽子尚未完整适配上游的观察者可见性、外观替换与显示配置。

| 检查项 | 源码审核结论 | 条件 / 边界 |
| --- | --- | --- |
| 自动识别外部皮肤 | 支持 | 扩展实现 `SkinRegistrar` 并声明 `habitrain_lottery_skins` 入口；两端安装并注册一致的内容 |
| Mod Menu 入口 | 支持统一入口 | 抽奖补齐配置 → 皮肤 → 打开皮肤衣柜；皮肤页显示注册数量，没有逐项皮肤定义编辑器 |
| 扩展自己的 Mod Menu 配置按钮 | 不会自动生成 | 当前皮肤包没有 `modmenu` entrypoint；需要扩展自行提供，或继续使用抽奖补齐入口 |
| 衣柜 | 支持 | 服务端同步所有已注册条目；未解锁条目也能浏览，缺少本地定义 / 模型时禁止装备 |
| 仓库 / 资产背包 | 支持 | 显示已解锁皮肤、数量、品质和装备状态；仅注册但未拥有的条目不会进入资产列表 |
| 原版物品背包外观 | 支持绑定的底物 | API 绑定或 `skin_items/<type>` 标签识别物品，服务端写入组件，客户端替换模型；不会自动创建底物 |
| Mod Menu 奖池编辑器 | 支持选择外部皮肤 | 新增 / 替换选择器遍历 API 目录；注册不会自动赋予奖池权重或发放皮肤 |
| 普通隐身下的虚拟帽子 | 有检查 | `player.isInvisible()` 为 true 时跳过帽子层；不包含全部观察者专属规则 |
| 真实头部槽里的帽子与隐身 | 有缺口 | 动态弹簧帽拒绝渲染后回退到静态模型，不等于隐藏 |
| 玩家变大 / 变小 | 有适配基础 | 第三人称帽子、手持模型继承玩家矩阵；衣柜按玩家缩放反向补偿尺寸，尚未进行游戏内尺寸切换验证 |
| jeb / 易容 / 窃皮等帽子跟随 | 未完整适配 | 当前帽子查询固定使用实体本人 UUID |

## 发现的问题（按优先级）

### F-01 / P1：帽子不跟随 jeb 与其他身份外观替换，也未处理固定伪装隐藏

位置：`皮肤mod/src/main/java/com/mike/habitrainskins/client/hat/HatPlayerLayer.java:50`；弹簧物理的虚拟帽子判定同样位于 `client/spring/SpringHatPhysics.java:141`。

帽子层直接调用 `HatClientState.entryOf(player.getUUID())`。上游已经通过 `HatEquipmentApi.resolveDisplayedOwnerUuid` 和 `OnResolveDisplayedSkinOwner` 提供当前显示外观拥有者解析，包含 jeb 洗牌、双重人格、变形者、嬉命人易容、窃皮者和阿蒙夺舍。jeb 客户端目标每 5 秒更新，当前帽子查询完全没有接入这条解析链。

结果：玩家 A 显示为 B 的身体外观时，仍显示 A 的帽子；帽子成为识别 A 的标记。上游还会在 `DISGUISE`、疯魔 / 固定角色皮肤、特定难民旁观状态下隐藏帽子，当前独立帽子层也未执行这些条件。

复现步骤：A、B 装备不同帽子，对 A 应用 jeb 或其他显示外观替换，观察身体变化后帽子是否仍停留在 A 的选择。

修复方向：在桥接层统一解析「是否隐藏、显示拥有者、独立目录中的帽子选择」，再查询独立的 `HatClientState`。只调用上游 `getDisplayedHatSkinName` 还不够，因为它读取的是上游帽子缓存，并非本项目的独立存档缓存。渲染和弹簧物理应使用相同的显示选择，避免切换成他人的弹簧帽后没有对应物理状态。

上游依据：`哈比列车dlc/20260805/StarRailExpress-master/src/main/java/io/wifi/starrailexpress/hat/HatEquipmentApi.java:64`、`:154`、`:185`、`:200`。

### F-02 / P1：观察者专属隐身没有进入虚拟帽子的可见性判断

位置：`皮肤mod/src/main/java/com/mike/habitrainskins/client/hat/HatPlayerLayer.java:51`。

帽子层只检查 `isInvisible()`，没有判断玩家对当前观察者的 `isInvisibleTo(viewer)`。具体例子是上游 `AmonHideTargetMixin`：阿蒙附身准备时将目标对本机阿蒙的 `isInvisibleTo` 改为 true，不要求目标全局 `isInvisible` 为 true。这种情况下帽子层仍具备绘制条件，存在身体隐藏而帽子露出的风险。

另外，上游允许部分观察者看见隐身玩家，当前全局 `isInvisible()` 检查会对这些观察者也隐藏虚拟帽子；因此它无法表达上游按观察者区分的完整显示策略。

复现步骤：目标装备虚拟帽子，阿蒙开始附身；从阿蒙和普通观察者两种视角分别查看目标。

修复方向：以当前观察者和上游最终可见性为基础，明确完全隐藏 / 可见 / 半透明时的帽子行为，并与第一人称、GUI 预览分别处理。该问题由源码判定，尚未进行多人客户端复现。

上游依据：`哈比列车dlc/20260805/StarRailExpress-master/src/main/java/org/agmas/noellesroles/mixin/client/general/AmonHideTargetMixin.java:38`。

### F-03 / P1：真实佩戴弹簧帽的隐身分支回退到了可见静态模型

位置：`皮肤mod/src/main/java/com/mike/habitrainskins/client/spring/SpringHatRenderer.java:89`；`mixin/client/CustomHeadLayerMixin.java:59`。

`tryRender` 对隐身实体返回 false。调用方在 false 后执行 `original.call(...)`，继续让原版画头部物品，而不是停止绘制。已检查本地 Minecraft 映射 JAR：`CustomHeadLayer` 没有 `isInvisible` / `isInvisibleTo` 判断；不能把回退理解为「原版会替我们隐藏」。真实戴在 HEAD 槽的路障帽也通过原版头部物品路径绘制，没有独立可见性拦截。

结果：衣柜装备的虚拟帽子与真实佩戴底物的隐身表现不一致，真实头部槽可能留下静态帽子并暴露位置。

复现步骤：分别使用衣柜虚拟装备和真实 HEAD 槽佩戴弹簧帽 / 路障帽，再触发普通隐身，比较其他观察者看到的结果。

修复方向：区分「本皮肤应隐藏，已处理」与「不属于本皮肤，回退原版」两个结果。隐藏时吞掉目标 renderItem 调用，同时保留外层 push / pop 配对。真实佩戴和虚拟帽子应共用可见性决策。该问题由调用链和本地类检查确认，尚未实际启动客户端复现。

### F-04 / P2：衣柜帽子显示模式对独立虚拟帽子不生效

位置：`哈比列车抽奖补齐/src/main/java/com/habitrain/lottery/client/WardrobeExtras.java:37`、`:42`；`皮肤mod/src/main/java/com/mike/habitrainskins/client/hat/HatPlayerLayer.java:50`。

衣柜按钮会切换并保存 `SREClientConfig.hideAllHats` / `showOwnHatOnly`；独立帽子层没有读取这两个设置。因此按钮文字和配置值已经改变，但弹簧帽、路障虚拟帽子仍继续显示。

同一遗漏影响预览：`PlayerPreview.java:57` 临时设置 `hideAllHats = true` 以抑制已装备帽子，独立帽子层忽略它。在非帽子预览等没有触发物理帽子去重的场景中，仍可能叠加玩家当前帽子。

修复方向：帽子层在读取显示装备之前应用这两个配置，并为衣柜预览提供明确的抑制范围。

## 已支持链路的具体依据

- 初始化：`HabiLotteryMod.java:50` → `SkinContentBootstrap.registerAll()`；外部入口发现和调用位于 `SkinContentBootstrap.java:58`、`:72`。失败注册器会回滚其皮肤 / 特效并记录日志。
- 类型：`SkinDefinition.java:18` 固定为 `knife`、`revolver`、`bat`、`grenade`、`hat`；`gun` 是 `revolver` 的别名。API 不支持任意新增类别或直接把玩家整身皮肤作为这种物品皮肤注册。
- Mod Menu：`ModMenuIntegration.java:9` 返回 `LotteryConfigRootScreen`；`:761` 的皮肤标签打开衣柜。
- 衣柜：`SkinNetwork.java:84` 开始的服务端发送逻辑遍历注册目录；`SkinWardrobeScreen.java:191` 遍历同步目录并核对本地模型，`:249` 控制装备按钮。
- 仓库：`WarehouseNetwork.java:101` 遍历玩家已解锁记录并从 API 取颜色 / 品质；`WarehouseScreen.java:493` 可从皮肤资产进入衣柜。
- 奖池：`CrateRewardEditor.java:235` 遍历 API 目录填充新增 / 替换选择器。
- 原版背包：`InventorySkinApplier.java:27` 逐底物识别和校正组件，`IndependentSkinRendererMixin.java:17` 在原版物品渲染入口替换模型。
- 缩放：帽子在 `HatPlayerLayer.java:68` 使用父模型头部矩阵；弹簧几何在 `SpringHatRenderer.java:118` 用包含实体缩放的矩阵逆变换世界位移；`PlayerPreview.java:59` 起按 `getScale()` 调整衣柜画面尺寸。现有实现没有把第三人称皮肤硬锁成世界固定尺寸。

## 集成边界与待验证项

- 两端必须具备一致注册。服务端只注册而客户端缺扩展时，衣柜能收到条目但没有本地模型，禁止装备；客户端单独注册不能增加服务端权威衣柜目录。
- 当前扩展注册后不会自动成为每个奖池的奖励，也不会自动解锁给所有玩家，需要奖池配置或玩家解锁 API。
- 通用 API 负责目录、状态、物品组件与模型；不提供独立的通用虚拟帽子玩家层。当前虚拟帽子同步 / 世界渲染由 `皮肤mod` 提供。其他扩展只注册 `hat`，不能把「目录里能看见」等同于「在没有此皮肤包的整合包里也能虚拟佩戴」；需要相应世界渲染提供者。
- 手持皮肤仍经过上游 `AllowItemShowInHand` 和物品获取路径。上游主动返回空物品的场景会先阻止正常物品渲染，但这不是对所有普通隐身角色统一隐藏。魔法棒 / 爪刃自定义渲染器的 `isInvisible()` → false 同样表示回退，不能单独作为「静态物品也隐藏」的证据。
- 皮肤包构建使用其固定 `habitrain_lottery-1.1.27.jar`，抽奖补齐另按当前 `1.1.43` 构建。两项构建通过不等于这对当前版本的完整客户端联机兼容性验证。
- 现有测试没有覆盖上述帽子身份跟随、显示模式和技能可见性。未执行真实客户端、多人观察者或变大 / 变小切换画面验证。

## 本次验证

顺序执行，未并发运行 Gradle：

```powershell
# 哈比列车抽奖补齐
.\gradlew.bat build --offline --rerun-tasks --gradle-user-home 'D:\Backup\mc mod\.gradle-user-home' --console=plain

# 皮肤mod
.\gradlew.bat -I tools\verify-release-mixins.gradle build --offline --rerun-tasks --gradle-user-home 'D:\Backup\mc mod\.gradle-user-home' --console=plain
```

- 抽奖补齐：305 项测试，0 失败、0 错误、0 跳过；独立皮肤边界检查通过。
- 皮肤包：58 项测试，0 失败、0 错误、0 跳过；资源自检通过，发布 Mixin 的 9 个注入点检查通过。
- 两个项目使用的上游发布 JAR SHA-256 一致：`081D4598A675BE74A1351AD4C68602C1F80936F32F87352EEE52C418E6906B21`。该 JAR 包含上游帽子显示拥有者 / jeb / 伪装相关类和引用，缺口不是由缺少这些上游类造成的。
- 构建产物已由各项目的 `copyReleaseJar` 复制到 `临时`，并验证和 `build/libs` 文件哈希一致：
  - `habitrain_lottery-1.1.43.jar`：`DF3A5ABF4A6908A15C91F50EBB31EC1EE24AC6FC4028A22B11E5C5A50AE4506E`
  - `habitrain_skins-1.0.43.jar`：`C2EA3815E1EA275C8BF21866090FD7B5977272E82B36EE36073346F537F9866D`

建议下一步先修 F-01 至 F-03 的身份 / 可见性，再修 F-04 的配置一致性，然后用双观察者客户端覆盖：普通隐身、阿蒙附身、jeb 洗牌、易容 / 窃皮、固定伪装、虚拟 / 真实帽子、上游大 / 小体型与衣柜预览。
