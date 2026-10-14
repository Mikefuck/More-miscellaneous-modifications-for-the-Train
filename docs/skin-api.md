# 独立皮肤 API v3

本 API 负责皮肤定义、物品绑定、客户端目录、品质、解锁和装备。它不包含随机奖励、奖池、货币兑换或抽奖入口。

## 注册入口

扩展模组在 `fabric.mod.json` 中声明 `habitrain_lottery_skins` 入口点，并实现 `SkinRegistrar`：

```json
{
  "entrypoints": {
    "habitrain_lottery_skins": ["com.example.ExampleSkins"]
  },
  "depends": {
    "habitrain_lottery": ">=1.1.27"
  },
  "custom": {
    "habitrain_lottery:skin_api": {"version": 3}
  }
}
```

注册器在服务端和客户端各运行一次。注册失败会回滚本次新增、替换和删除的声明，包括 `unregister` / `removeProvider` 及其关联的文字、枪声声明；重放失败时保留此前有效的定义与特效。

## 定义

```java
SkinDefinition definition = SkinDefinition.builder(
        "knife", "crystal_blade", 0xFF50C878)
    .model("example", "item/skins/knife/crystal_blade")
    .quality(SkinQuality.PURPLE)
    .build();

HabiSkinApi.register(definition);
```

支持的类型是 `knife`、`revolver`、`bat`、`grenade`、`hat` 和 `back`（背部，1.1.51 起）；`gun` 只作为 `revolver` 的输入别名。ID 使用小写字母、数字、下划线、点和短横线，长度为 1 到 48。

模型命名空间必须显式指定。翻译键优先使用 `skin.habitrain_lottery.<type>.<id>`，扩展可以提供名称和描述。没有模型时衣柜显示缺失资源并禁止装备。

品质为白、蓝、紫、金、红五档，独立于颜色、模型和其他扩展数据。旧定义未指定品质时默认为白色。

## 物品与组件

`SkinItems.bindItem(ResourceLocation item, String type)` 绑定衣柜预览和世界持握的底物，例如 `SkinItems.bindItem(ResourceLocation.parse("example:blade"), "knife")`。装备后的物品写入 `habitrain_lottery:skin` 数据组件，内容是规范化的 `type/id`。服务器会在背包变化、登录和重生时校正组件；丢出的物品保留其外观，新的持有者会按解锁状态更新。

扩展可以使用 `SkinEffects` 注册待机动画、投掷物继承和命中特效，详见 [skin-effects-api.md](skin-effects-api.md)。
扩展还可使用 `SkinSounds` 声明可开关的自定义枪声，并读取射手同步偏好；独立预览上下文也已开放，详见 [skin-sounds-api.md](skin-sounds-api.md)。
扩展可通过 `SkinCardText.register(type, id, text)` 为指定皮肤的衣柜卡片右下角添加自定义文字，支持翻译和颜色，详见 [skin-card-text-api.md](skin-card-text-api.md)（1.1.52 起）。

## 玩家状态

### 背部皮肤（1.1.51 起）

背部皮肤沿用 `SkinRegistrar`、`SkinDefinition`、`HabiSkinApi` 和 `HabiSkinPlayerApi`，类型为 `back`。扩展依赖 `habitrain_lottery >=1.1.51`；皮肤 API 版本仍为 v3。

```java
// 在 registerSkins() 中注册；模型与贴图由扩展提供。
HabiSkinApi.register(SkinDefinition.builder("back", "travel_pack", SkinQuality.PURPLE)
        .model("example", "item/skins/back/travel_pack").build());

// 服务端主线程：解锁后才能装备；状态仍保存在玩家世界 JSON。
HabiSkinPlayerApi.unlock(player, "back", "travel_pack");
HabiSkinPlayerApi.equip(player, "back", "travel_pack");
HabiSkinPlayerApi.clearEquipped(player.getUUID(), "back");
```

名称翻译键为 `skin.habitrain_lottery.back.travel_pack`。衣柜新增“背部”分类，进入时从背面预览，支持拖动旋转、2D/3D 切换、装备和恢复默认。未解锁或缺少基础模型的条目不可装备。

背部外观不需要持有物品或占用胸甲槽。渲染层跟随玩家躯干，在躯干中心后方绘制**基础模型**，采用 `display.fixed` 变换（不使用 `_in_hand`）：锚点在身体局部坐标 `(0, 6/16, 3/16)`，模型 Y 轴朝上、Z 轴朝向身体前方。扩展可在 `fixed` 中调整尺寸、旋转和偏移；`SkinEffects.registerAnimation("back", id, ...)` 的 `FIXED` 动画同样生效。基础模型缺失时跳过绘制。隐身和旁观者不显示背部外观。

衣柜图标使用既有 `habitrain_lottery:skin` 物品组件；背部实际佩戴状态由独立 `back_skin_v1` 服务端通道同步给本人和追踪该玩家的客户端。在切换、锁定、登录、重生或进入观察范围时同步；预览未拥有的皮肤和默认外观只影响衣柜绘制。胸甲、鞘翅、披风仍按原有渲染逻辑显示。默认外观表示不佩戴背部皮肤。

背部皮肤也可通过 `HabiMailApi.skin("back", id)` 发放，或由现有商店、任务和箱子奖励配置引用 `back/id`。

### 所有皮肤的玩家状态

玩家侧使用 `HabiSkinPlayerApi`：

- `unlock` / `lock`：修改拥有状态；
- `equip` / `clearEquipped`：修改当前装备；
- `isRegistered`、`isUnlocked`、`unlockedAll`、`equippedAll`：读取状态。

所有修改必须在服务端主线程执行，并在 JSON 写入成功后才发送同步包。默认外观永远可用，不能被锁定。

## 邮件附件

`HabiMailApi.skin(type, id)` 和 `MailReward.skin(type, id)` 创建皮肤附件。领取时服务端重新验证注册目录；未注册的附件不会被静默删除，邮件保留待恢复状态。皮肤、绿苹果、角色卡使用同一封邮件的持久化奖励意图，各资产分别提交回执，重试继续未完成的步骤。

## 存档和能力声明

玩家皮肤状态位于 `<world>/habitrain_lottery/players/<uuid>.json`。扩展应声明 `habitrain_lottery_skin_api` 能力或依赖 `habitrain_lottery >=1.1.27`；版本常量为 `HabiSkinApi.API_VERSION == 3`。v3 移除了旧的随机奖池字段和注册方法，皮肤目录只描述外观、品质和模型。
