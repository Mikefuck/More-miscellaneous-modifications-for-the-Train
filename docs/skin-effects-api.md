# 哈比列车皮肤特效 API v1（抽奖补齐 1.1.18）

皮肤 API v2（`docs/skin-api.md`）负责**外观**：模型、贴图、奖池、解锁、装备。
本文件描述的**皮肤特效 API v1** 负责**行为**：模型动效、投掷物拖尾、命中/爆炸特效。

- 门面类：`com.habitrain.lottery.api.skin.SkinEffects`（`API_VERSION = 1`）
- 入口点：**同一个** `habitrain_lottery_skins`（实现 `SkinRegistrar#registerSkins()`），无需新增入口点
- 硬门禁 id：`habitrain_lottery_skin_effects`（可写 `"depends": {"habitrain_lottery_skin_effects": "*"}`）
- 元数据声明（可选，建议）：`"custom": {"habitrain_lottery:skin_effects": {"version": 1}}`，
  版本不符时抽奖补齐会输出 WARN
- **皮肤 API v2 保持完全兼容**：`SkinEffects.API_VERSION` 与 `HabiSkinApi.API_VERSION` 相互独立，
  旧 v2 扩展不注册特效即可，行为与 1.1.17 完全一致

> 一句话：**先在 `registerSkins()` 里 `HabiSkinApi.register(SkinDefinition...)`，再用
> `SkinEffects.registerAnimation/registerTrail/registerImpact(...)` 给同一个 `type/id` 挂行为。**

---

## 1. 三个钩子

| 方法 | 运行侧 | 触发时机 | 典型用途 |
| --- | --- | --- | --- |
| `registerAnimation(type, id, SkinAnimation)` | 客户端 | 该皮肤的模型被渲染的每一帧 | 自转 / 悬浮 / 呼吸缩放 |
| `registerTrail(type, id, SkinTrailHandler)` | 客户端 | 每个客户端 tick，对每个“视觉上带着该皮肤”的飞行投掷物 | 飞行拖尾粒子 |
| `registerImpact(type, id, SkinImpactHandler)` | 服务端 | 该投掷物命中/爆炸时 | 爆炸特效、音效、后续延时动作 |

外加一个可选修饰：`restrictToItems(type, id, ResourceLocation...)`，把皮肤限制在指定物品上。

```java
public final class ExampleSkins implements SkinRegistrar {
    @Override public void registerSkins() {
        // ① 外观（皮肤 API v2，行为不变）
        HabiSkinApi.register(SkinDefinition.builder("grenade", "example_black_hole", 0xFFAA55FF)
                .model("skin_example", "item/skins/grenade/example_black_hole")
                .includeInDefaultPools()
                .build());

        // ② 只允许普通手雷使用这个皮肤（grenade 类型默认还覆盖粘性雷/滞时雷）
        SkinEffects.restrictToItems("grenade", "example_black_hole",
                ResourceLocation.parse("trainmurdermystery:grenade"));

        // ③ 模型动效（客户端）
        SkinEffects.registerAnimation("grenade", "example_black_hole",
                SkinAnimation.builder()
                        .spin(SkinAnimation.SpinAxis.Y, 3.0F)        // 3 度/tick
                        .thrownSpinMultiplier(6.0F)                  // 飞行时转速 ×6
                        .wobble(7.0F, 90.0F)                         // 7 度、90 tick 周期的俯仰摆动
                        .bob(0.6F, 80.0F)                            // 0.6 模型单位（=0.0375 格）上下浮动
                        .pulse(0.06F, 45.0F)                         // 缩放 ±6%
                        .build());

        // ④ 飞行拖尾（客户端）
        SkinEffects.registerTrail("grenade", "example_black_hole",
                (level, projectile, position, partialTick) -> ExampleTrail.spawn(level, position, partialTick));

        // ⑤ 命中特效（服务端）
        SkinEffects.registerImpact("grenade", "example_black_hole", context -> {
            context.level().sendParticles(ExampleParticles.BLACK_HOLE, 
                    context.position().x, context.position().y, context.position().z, 1, 0, 0, 0, 0);
            context.schedule(6, () -> { /* 6 tick 后的第二段表现 */ });
        });
    }
}
```

### 1.1 `SkinAnimation`

```java
public record SkinAnimation(
        SpinAxis spinAxis,             // X / Y / Z，默认 Y
        float spinDegreesPerTick,      // 匀速自转，负值反向；|值| ≤ 180
        float thrownSpinMultiplier,    // 飞行（GROUND）上下文的转速倍率，0~16
        float wobbleDegrees,           // 绕 X 的正弦俯仰，|值| ≤ 45
        float wobblePeriodTicks,       // > 0，≤ 2400
        float bobUnits,                // 竖直正弦浮动，单位=模型单位（16 = 1 格），|值| ≤ 8
        float bobPeriodTicks,
        float pulseAmount,             // 缩放 = 1 ± amount，范围 [-0.9, 4]
        float pulsePeriodTicks,
        Set<Slot> slots)               // GUI / HELD / THROWN / FIXED / HEAD，默认全部
```

* `Slot.THROWN` 就是 `ItemDisplayContext.GROUND`，也就是 `ThrownItemRenderer` 渲染飞行投掷物时用的上下文；
  `Slot.HELD` 覆盖第一/第三人称双手；`Slot.GUI` 覆盖背包、快捷栏与衣柜平面预览。
* 只写 `.spin(...)` 时其余振幅为 0，即“只转不抖”。
* `SkinAnimation.none()` / 所有振幅为 0 时 `isAnimated() == false`，注册它是合法的空操作。

#### 动效为什么不会“接不上”

动效不是关键帧，而是**单一单调时钟（墙钟 tick，50/s）上的连续周期函数**：

* 匀速自转（对 360° 取模，长时间运行不失精度）；
* `sin` 摆动 / 浮动 / 缩放，在 `t = 0` 处都经过中性值。

因此：暂停、开背包、资源重载、切维度、重生、换视角都不会出现跳变或错位——没有关键帧就没有接缝。
旋转插入在基础显示变换**之后**（即模型中心，和 `ItemRenderer` 的 `translate(-0.5,-0.5,-0.5)` 同一坐标系），
所以模型是“原地自转”而不是绕角落公转；竖直浮动插入在基础变换**之前**，因此是在父坐标系里平移。

### 1.2 `SkinTrailHandler`

```java
void onTrail(Level level, Entity projectile, Vec3 position, float partialTick);
```

* `position` 已按 `partialTick` 插值，可直接用于粒子定位。
* 参数类型刻意用 `Level`（而非 `ClientLevel`）：接口在两端都会被加载，**签名里不要出现客户端专有类型**，
  请这样写：

```java
SkinEffects.registerTrail("grenade", "example_black_hole",
        (level, projectile, position, partial) -> ExampleTrail.spawn(level, position, partial));
// ExampleTrail.spawn 放在只会在客户端加载的类里，内部再做 (ClientLevel) level 判断
```

* 没有任何扩展注册拖尾时，客户端连实体扫描都不做（一次 `SkinEffects.hasTrails()` 检查）。

### 1.3 `SkinImpactHandler` 与 `SkinImpactContext`

```java
void onImpact(SkinImpactContext context);

record SkinImpactContext(String type, String id, ServerLevel level, Vec3 position,
        ItemStack stack, UUID owner, long gameTime, Scheduler scheduler)
void schedule(int delayTicks, Runnable action)   // 服务端 tick 队列，延时执行
```

* **只跑服务端主线程一次**，位置就是爆炸位置。
* `context.schedule(...)` 是抽奖补齐提供的延时队列（停服时清空并记 debug 日志）：
  分段表现（先塌缩、后冲击波、再第二声）不需要扩展自己养 ticker。
* 处理器里抛异常会被抽奖补齐捕获并记 ERROR（同一 `type/id` + 钩子只记一次，避免刷屏），
  不会打断爆炸判定或杀掉服务端。

---

## 2. 内建触发点（抽奖补齐负责接线）

| 环节 | 行为 |
| --- | --- |
| 投掷物继承皮肤 | SRE 手雷投掷物（`NoHeavyWaterInfluencedThrowableItemProjectile`，物品类型为 `grenade`）在进入追踪时，若自身堆叠还没有皮肤组件，则套用**投掷者当前装备的** `grenade` 皮肤 |
| 飞行外观 | 投掷物实体同步的堆叠带上 `habitrain_lottery:skin` 组件，于是客户端在原版投掷物渲染器里就画出该皮肤的模型与动效 |
| 拖尾 | 客户端每个 tick 扫描正在渲染的实体，对带皮肤且有拖尾处理器的调用 `SkinTrailHandler` |
| 命中 | 普通手雷 `GrenadeEntity#onHit` 入口派发 `SkinImpactHandler` |
| 视觉接管 | 该手雷**确实带着**注册了撞击特效的皮肤时，抑制原版爆炸粒子（大爆炸 / 烟雾 / 物品碎片），由扩展独占视觉 |

### 2.1 明确的边界

* **不改玩法**：伤害、击杀判定、击杀收益、冷却、爆炸音效全部保持原版逻辑；
  特效 API 只接管粒子表现。
* **视觉接管只覆盖普通手雷**（`trainmurdermystery:grenade`）：粘性雷与滞时雷的爆炸表现保持原版。
  它们仍然会派发撞击事件（扩展可以自行筛选实体），但抽奖补齐不会抑制它们的原版粒子。
* **箱子/商店/邮件**里的皮肤堆叠由既有逻辑处理，特效只是让这些堆叠“动起来、有表现”。
* 投掷物只有在**视觉上确实带着该皮肤**时才会触发特效，避免“模型是原版、爆炸是黑洞”的割裂。

### 2.2 `restrictToItems` 为什么存在

一个皮肤**类型**可以覆盖多个物品：`habitrain_lottery:skin_items/grenade` 同时包含
普通雷、粘性雷、滞时雷。若扩展只想让普通雷用这个皮肤，就用：

```java
SkinEffects.restrictToItems("grenade", "example_black_hole",
        ResourceLocation.parse("trainmurdermystery:grenade"));
```

受限后：装备该皮肤时，只有列出的物品会被写入皮肤组件；列表之外的同类物品（曾被写入过的）
会在下一次背包校正里被**移除**组件。传空参数即清除限制（默认状态）。未安装的物品 id 无害，永远匹配不上。

---

## 3. 诊断与兼容

| 现象 | 原因 / 处理 |
| --- | --- |
| 日志 `Skin effect registered for X but no such skin is registered (yet)` | 特效先于皮肤注册（或 id 拼错）。**先注册 `SkinDefinition`**；若皮肤由别的扩展提供，同一数组里先后不定时会看到这条提示，属正常 |
| 日志 `... targets skin effects API vN but this build provides vM` | `custom."habitrain_lottery:skin_effects".version` 写错 |
| 扩展注册失败 | 注册仍是**按扩展事务化**的：`registerSkins()` 抛异常时，本次已注册的皮肤**与特效**一起回滚，其余扩展照常加载；修好后 `/hlt skins reregister` 重跑 |
| 动效看不到 | ① 皮肤模型本身没烘焙（衣柜显示「缺少资源」）；② `slots` 把要看的上下文排除了；③ 振幅全为 0 |
| 拖尾看不到 | 没注册拖尾处理器（`hasTrails()` 为 false）或物品限制把该物品排除了 |
| 爆炸还是原版粒子 | 该堆叠没有皮肤组件（非装备者投出、被别的模组生成）或没注册撞击处理器 |

复用指令：`/hlt skins reregister`（重跑全部入口点）、`/hlt reload`（重载奖池与资源）。

---

## 4. 游戏内验收清单（特效 API）

1. 装备皮肤后：背包/快捷栏/手持的普通手雷**缓慢自转 + 轻微浮动/呼吸**，画面无跳变；
2. 切到衣柜 3D 预览、开关界面、重载资源包（F3+T）后动效**不跳帧、不错位**；
3. 丢出普通手雷：飞行中转速明显加快，并留下拖尾粒子；
4. 命中爆炸：原版烟雾/碎片粒子消失，扩展的黑洞表现出现；伤害、击杀播报、击杀收益不变；
5. 粘性雷、滞时雷：外观若被限制则保持原版，爆炸表现保持原版；
6. 其他玩家视角观察投掷物与爆炸：表现一致（拖尾由各自客户端本地生成，属预期差异）；
7. 卸下/锁定皮肤后：手雷回到原版外观，飞行与爆炸都没有特效残留；
8. 移除扩展模组后重启：手雷完全回到原版，日志无报错。

---

## 5. 相关文档与源码

- `docs/skin-api.md` —— 皮肤 API v2（外观、奖池、解锁、邮件）
- `docs/player-api.md` —— 玩家资产 / 抽奖 / 邮件 API
- 源码：`src/main/java/com/habitrain/lottery/api/skin/`
  - `SkinEffects.java`、`SkinAnimation.java`、`SkinImpactContext.java`、`SkinImpactHandler.java`、`SkinTrailHandler.java`
- 接线实现：`bridge/SkinEffectRuntime.java`（投掷物继承皮肤、撞击派发、延时队列）、
  `mixin/GrenadeImpactEffectMixin.java`（命中派发 + 原版爆炸粒子抑制）、
  `client/SkinAnimationModels.java`（模型动效包装）、`client/SkinTrailTicker.java`（拖尾驱动）
