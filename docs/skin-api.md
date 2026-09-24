# 哈比列车独立皮肤 API v2（1.1.26，含品质属性）

> **1.1.18 起的姊妹 API**：模型动效、投掷物拖尾、命中/爆炸特效见
> [`skin-effects-api.md`](skin-effects-api.md)（皮肤特效 API v1）。
> 本文件描述的 v2 外观 API 未做任何破坏性改动，旧扩展无需重新编译。

Minecraft 1.21.1 / Fabric / Java 21。皮肤注册、解锁、装备、网络、物品组件和模型渲染均由本模组提供，不读取 DLC 的皮肤注册表、皮肤 CCA、SKIN 组件或 GeneralModelLoadingPlugin。抽奖、职业卡和对局功能仍保留 DLC/core 依赖，所以本模组整体仍需要这些前置。

本模组不附带任何皮肤。旧 skins.json / skins_list.txt 和 itemskins、starrailexpress 皮肤资源均已移出项目；旧配置 skins.json 不再加载。安装扩展后才出现皮肤。

> 从 1.1.0（API v1）升级的扩展请先读 [`skin-api-migration-1.1.0-to-1.1.17.md`](skin-api-migration-1.1.0-to-1.1.17.md)。

## 扩展注册

客户端和服务端安装相同皮肤扩展。在扩展 fabric.mod.json 中声明：

```json
{
  "entrypoints": {"habitrain_lottery_skins": ["com.example.ExampleSkins"]},
  "depends": {"habitrain_lottery": ">=1.1.26"},
  "custom": {"habitrain_lottery:skin_api": {"version": 2}}
}
```

`custom."habitrain_lottery:skin_api".version` 是可选但**建议声明**的：本模组读取它并在与自身 `API_VERSION` 不一致时输出 WARN，让版本错配一眼可见。也可以用硬门禁 `depends: {"habitrain_lottery_skin_api": "*"}`（本模组 `provides` 该 id）。

```java
import com.habitrain.lottery.api.skin.*;
import net.minecraft.resources.ResourceLocation;

public final class ExampleSkins implements SkinRegistrar {
    @Override public void registerSkins() {
        HabiSkinApi.register(SkinDefinition.builder("knife", "example_crystal", 0xFF55CCFF)
                .quality(SkinQuality.RED)                      // 由扩展模组决定品质
                .model("example", "item/skins/knife/crystal")   // 始终显式指定自己的命名空间
                .includeInDefaultPools()
                .build());
        // 可选：绑定任意物品，不要求物品继承 DLC 的 SkinableItem。
        // 绑定同时决定该类型的预览底物（优先级高于数据包标签）。
        SkinItems.bindItem(ResourceLocation.parse("minecraft:iron_sword"), "knife");
    }
}
```

- 类型：knife、revolver（gun 别名）、bat、grenade、hat；hat 是物品外观分类，不接管 DLC 玩家头饰渲染。
- ID：`[a-z0-9_.-]`，1–48 位；注册、查找、邮件附件共用同一套规范化（先 trim + 转小写，再校验），因此 `builder("knife","Crystal_Blade")` 存为 `crystal_blade`，同一字面量在邮件附件里也不会报错。default / coin 保留。建议前缀包含扩展名，避免冲突。
- 重复注册相同定义返回 `false`；冲突定义抛 `IllegalStateException`。目录最多 4096 项。
- **模型命名空间必须显式指定。** 不指定 `model` 时解析为 `habitrain_lottery:item/skins/<type>/<id>`，本模组不附带该资源，皮肤会在衣柜里显示为「缺少资源」且不可装备（这是 v1 → v2 的行为变化，见迁移说明）。为兼容尚未重新编译的 v1 扩展，客户端在找不到该模型时会再尝试 v1 的 `starrailexpress:<type>/<id>`。
- 模型位于 `assets/example/models/item/skins/knife/crystal.json`；手持变体为 `crystal_in_hand.json`。
- **缺失模型回退**：手持变体缺失 → 回退基础模型；基础模型也缺失（扩展被裁掉资源、路径写错、模型在资源重载后才注册）→ 回退原物品显示，衣柜把该皮肤标为「缺少资源」并禁止装备，绝不渲染紫黑缺失贴图，也不会崩溃。判定依据是「取不到已烘焙模型，或模型粒子图标为 `minecraft:missingno`」。
- 模型、材质完全由扩展提供，注册表不会生成美术资源。资源加载后若某个皮肤的基础模型无法烘焙，日志会输出一条 WARN 指出具体的 `type/id` 与模型 id。
- 翻译键优先 `skin.habitrain_lottery.<type>.<id>`，其次兼容上游旧键 `screen.sre.skins.<type>.<id>.name` / `.desc`，都没有时显示原始 ID。
- `includeInDefaultPools()` 注入同类型与 all 奖池第 0 品质档；`addToPool(type, qualityBand)` 可指定档位，**可对同一奖池多次调用以进入多个档位**（`.addToPool("knife", 0).addToPool("knife", 5)`）。去重按目标档位判定，未配置对应档位时不注入。
- `HabiSkinApi.getSkins(type)` / `types()` / `fromEntry("type/id")` 可查询当前目录。
- registrar 的注册是**按扩展事务化**的：若某个入口点在注册到一半时抛异常，它本次已注册的条目会被整体回滚，日志会写明回滚数量，其余扩展照常加载。修好扩展后可执行 `/hlt skins reregister` 重新运行全部入口点（无需重启）；若仍有扩展失败，命令会报错并列出被回滚的扩展名。

## 皮肤品质（1.1.26 起）

`SkinQuality` 提供五档品质：`WHITE`（白）、`BLUE`（蓝）、`PURPLE`（紫）、`GOLD`（金）、`RED`（红）。
扩展在注册时调用 `.quality(SkinQuality.RED)`，或直接使用
`SkinDefinition.builder("knife", "example_crystal", SkinQuality.RED)`。
两端应注册同一品质；衣柜与背包使用**服务端快照里的品质**显示卡片底色、详情与提示文字。

- `definition.quality()`、`HabiSkinApi.quality(type, id)` 查询品质；后者对未知皮肤返回空 Optional。
- `quality.id()` 是稳定标识 `white/blue/purple/gold/red`，`color()` 为不透明 ARGB 显示色，`translationKey()` 为品质语言键。
- 旧的 `builder(type, id, int color)` 和五参数 `SkinDefinition` 构造器继续可用，未指定品质默认 `WHITE`。旧颜色保留为扩展主题色，不再决定品质底色。
- 品质与 `LotteryPlacement.qualityBand` 独立：设置红色品质不会修改抽奖概率、奖池档位、重复奖励或特效颜色。
- 无需改玩家存档：品质来自注册目录。已拥有皮肤会在下一次页面刷新时显示新的品质；缺失扩展的历史背包条目回退白色。
- 这是 API v2 的增量扩展，`API_VERSION` 仍为 `2`。调用品质 API 的扩展须声明 `habitrain_lottery >=1.1.26`；客户端和服务端须一起升级至支持品质的新版本（请求/快照频道升级为 `skin_request_v2` / `skin_snapshot_v2` 与 `warehouse_request_v2` / `warehouse_snapshot_v2`，混装旧版时页面报告服务不可用，避免错读数据包）。

## 物品绑定

也可以用数据包标签 `data/habitrain_lottery/tags/item/skin_items/<type>.json` 绑定物品：

```json
{"replace": false, "values": ["minecraft:iron_sword", {"id": "example:blade", "required": false}]}
```

本模组提供刀、枪、棒、手雷的可选 DLC 物品 ID 标签（trainmurdermystery / wathe / starrailexpress 命名空间），不引用这些物品的 Java 类型。一个物品只应属于一种皮肤类型。API 绑定优先于标签，且**同时决定识别与预览底物**。

`SkinItems.defaultItem(type)` 的优先级：`bindItem` 注册的第一个仍存在的物品 → 该类型标签中第一个已安装物品（`trainmurdermystery:knife` 等）→ 原版替代物品（铁剑 / 弩 / 木棍 / 雪球 / 皮革头盔），永不返回空气。`defaultStack(type)` 与 `styled(type, id)` 在此基础上构建预览堆叠。

**帽子（hat）的底物**是 `habitrain_lottery:skin_items/hat` 标签中的第一个物品，标签为空时回退 `minecraft:leather_helmet`。本模组**不再**识别 `hats:<皮肤id>` 这类"每个皮肤一个物品"的旧写法；要为帽子指定底物，请用 `SkinItems.bindItem(...)` 或在上面的标签里登记。

装备写入本模组的 `habitrain_lottery:skin` 数据组件，值为 `type/id`，由原版物品同步和保存。服务器侧权威修正按玩家背包内容变化触发（不再无条件每 tick 全量扫描），支持新获得物品、重新登录和重生；丢出的物品保留最后外观，其他玩家拾取后按新拥有者装备更新。

## 玩家操作与邮件

- 上游皮肤菜单、快捷键和 `OpenSkinScreenPaylod` 统一进入独立衣柜；在 `Minecraft.setScreen` 接管旧页面，保留原返回页面，不初始化旧控件。
- `/hlt skins`：所有玩家也可直接打开衣柜；按名称/ID 搜索、拥有筛选、排序、预览、翻页、装备和恢复默认。
- 衣柜右侧预览默认使用本地玩家的第三人称模型（按住左键拖动旋转视角），下方 `3D/2D` 按钮可切回扁平皮肤图标；`3D` 为默认值，称号页只提供 2D。
- 「默认外观」显示该类型真实物品的图标与模型（`SkinItems.defaultStack`），不再使用纸张占位；`SkinItems.styled(type, id)` 返回真实物品加皮肤组件的堆叠，即其他玩家看到的样子。
- 帽子（`hat`）预览戴在模型头部（`player.setItemSlot(HEAD, ...)`），并临时隐藏上游头饰层，避免与正在预览的帽子重叠；预览结束后玩家装备与实体朝向都会原样恢复。
- 衣柜的刷新请求使用 `Request("", "refresh")`，服务器只发 `Snapshot(open=false)`；离开页面后到达的刷新结果不会重新弹窗。旧 `Request("", "")` 保留打开语义。
- `/hlt skins unlock <players> <type> <id>` / `lock`：管理员发放或撤销。
- `/hlt skins reregister`：管理员重新运行所有皮肤入口点并如实报告结果（失败时列出被回滚的扩展）。
- `HabiSkinPlayerApi.unlock/equip/lock`：服务端主线程调用，先落盘后同步，未知皮肤不能解锁或装备。
- `HabiMailApi.skin(type, id)` / `MailReward.skin(type, id)`：构建结构化皮肤邮件附件；type/id 走与注册相同的规范化。
- OP 邮件撰写页第 4 页输入 `type/id`。领取解锁，重复领取不发重复金币。
- 邮件领取仍使用原有多资产事务；皮肤也包含在失败回滚快照中。扩展缺失时拒绝领取，邮件保留待领取状态。

```java
var draft = HabiMailApi.draft("系统", "皮肤奖励", "请查收",
        java.util.List.of(HabiMailApi.skin("knife", "example_crystal")));
HabiMailApi.send(playerUuid, "player", draft);
```

## 奖池与升级

默认奖池保留结构、名称、概率，内置奖励列表为空，未安装皮肤扩展时运行时禁用空池。皮肤扩展可以注入，或在奖池配置中填写新目录的 `type/id`（枪兼容 `gun/id`）。金币条目为 `coin`。

旧世界奖池按新目录过滤：未知皮肤不发放；整个池无有效奖励则禁用。仍有有效奖励的池中，空品质档以 coin 补齐，保留概率及客户端结果索引；管理员可按需要调整概率。重复皮肤继续使用原有重复金币配置。服务器解析后的奖池直接同步到客户端，客户端不按自身目录重新排列。

旧玩家 JSON 不批量删除：未知皮肤的历史拥有记录保留，但不会列入新衣柜，也不能装备；原有金币、抽数、邮件、职业卡和对局资料保留。旧皮肤扩展应重新编译至 API v2 并提供模型，不能再依赖向 SRE 注册产生的副作用。

## 验证

`./gradlew build` 包含测试与 `verifyIndependentSkins` 检查，阻止独立目录重新引入上游 Java import，或再次打包旧资源目录。

品质显示可用 `./gradlew.ps1 -I tools/quality-smoke.gradle runClient --gradle-user-home ../.gradle-user-home` 在真实 Minecraft GUI 中检查（先构建相邻的 `皮肤mod`）。该测试使用独立目录 `build/quality-client-run`，验证五档服务端品质、七款红色皮肤、普通与 320×240 GUI 布局，截图输出到其中的 `screenshots/`。测试目录和测试注册不进入发布 JAR。

游戏内验收：双方安装同一测试皮肤扩展，解锁 → 装备 → 第三人称/他人观察 → 丢弃拾取 → 重生 → 重登 → 重复抽奖 → 邮件领取 → 撤销 → 移除扩展后重启。缺失模型回退（衣柜应显示「缺少资源」且不可装备）、锁定皮肤拒绝、空奖池不扣抽数也应检查。
