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

注册器在服务端和客户端各运行一次。注册失败会回滚该注册器本次写入的定义，不会留下半注册目录。

## 定义

```java
SkinDefinition definition = SkinDefinition.builder(
        "knife", "crystal_blade", 0xFF50C878)
    .model("example", "item/skins/knife/crystal_blade")
    .quality(SkinQuality.PURPLE)
    .build();

HabiSkinApi.register(definition);
```

支持的类型是 `knife`、`revolver`、`bat`、`grenade` 和 `hat`；`gun` 只作为 `revolver` 的输入别名。ID 使用小写字母、数字、下划线、点和短横线，长度为 1 到 48。

模型命名空间必须显式指定。翻译键优先使用 `skin.habitrain_lottery.<type>.<id>`，扩展可以提供名称和描述。没有模型时衣柜显示缺失资源并禁止装备。

品质为白、蓝、紫、金、红五档，独立于颜色、模型和其他扩展数据。旧定义未指定品质时默认为白色。

## 物品与组件

`SkinItems.bindItem(type, item)` 绑定衣柜预览和世界持握的底物。装备后的物品写入 `habitrain_lottery:skin` 数据组件，内容是规范化的 `type/id`。服务器会在背包变化、登录和重生时校正组件；丢出的物品保留其外观，新的持有者会按解锁状态更新。

扩展可以使用 `SkinEffects` 注册待机动画、投掷物继承和命中特效，详见 [skin-effects-api.md](skin-effects-api.md)。

## 玩家状态

玩家侧使用 `HabiSkinPlayerApi`：

- `unlock` / `lock`：修改拥有状态；
- `equip` / `clearEquipped`：修改当前装备；
- `isRegistered`、`isUnlocked`、`unlockedAll`、`equippedAll`：读取状态。

所有修改必须在服务端主线程执行，并在 JSON 写入成功后才发送同步包。默认外观永远可用，不能被锁定。

## 邮件附件

`HabiMailApi.skin(type, id)` 和 `MailReward.skin(type, id)` 创建皮肤附件。领取时服务端重新验证注册目录；未注册的附件不会发放，邮件保留待领取状态。皮肤附件和绿苹果、角色卡附件使用同一封邮件的回滚事务。

## 存档和能力声明

玩家皮肤状态位于 `<world>/habitrain_lottery/players/<uuid>.json`。扩展应声明 `habitrain_lottery_skin_api` 能力或依赖 `habitrain_lottery >=1.1.27`；版本常量为 `HabiSkinApi.API_VERSION == 3`。v3 移除了旧的随机奖池字段和注册方法，皮肤目录只描述外观、品质和模型。
