# 皮肤枪声 API v1（1.1.49 起）

枪声偏好属于**射手**，按玩家 UUID 和 `type/id` 保存到世界玩家 JSON 的 `disabledSkinSounds`。
默认开启；关闭后，所有客户端都保留这位射手的原版枪声。偏好与拥有、装备状态独立，允许先浏览未拥有的皮肤并预设开关。

## 扩展注册和播放

在已有 `habitrain_lottery_skins` 注册器中，先注册皮肤，再声明它提供自定义武器声音：

```java
HabiSkinApi.register(SkinDefinition.builder("revolver", "example", 0xFFFFFFFF)
        .model("examplemod", "item/skins/revolver/example").build());
SkinSounds.register("revolver", "example");
```

`SkinSounds.supports(type, id)` 查询能力，支持 `gun` 别名。注册可重放；注册器失败会连同能力声明一起回滚。
衣柜仅为声明了此能力的皮肤显示“枪声：开/关”按钮。

客户端替换枪声前使用射手**实际持有**的堆叠：

```java
if (SkinSounds.isEnabled(shooter.getMainHandItem())) {
    // 替换原枪声；关闭时让原声正常通过。换弹等额外武器音效也遵守同一开关。
}
```

动画启动不依赖这个开关。服务端把关闭状态写入独立的 `habitrain_lottery:skin_custom_sound` 布尔组件，
通过原版背包及装备同步传递给所有观察者；开启状态省略组件。旧堆叠没有组件时默认开启。
装备、换枪、拾取、重生和登录仍沿用现有背包校正，每个新持有者采用自己的偏好。

## 服务端玩家 API

```java
boolean enabled = HabiSkinPlayerApi.isCustomSoundEnabled(uuid, "revolver", "example");
HabiAssetResult result = HabiSkinPlayerApi.setCustomSoundEnabled(uuid, "revolver", "example", false);
// 也支持 ServerPlayer 重载。
```

必须在服务端线程调用。写盘成功后才更新在线背包并发送确认；写盘失败或当前处于延迟写入事务时不改变原状态。
未知皮肤、未声明声音能力、损坏存档和未准备好的世界会返回失败结果。

## 客户端管理 API

`com.habitrain.lottery.api.skin.client.SkinSoundPreferences` 只可在物理客户端访问：

- `isEnabled(type, id)`：本机玩家最近一次服务端确认的射手偏好。
- `requestEnabled(type, id, enabled)`：发送修改请求；返回 `true` 只代表发出了请求，不能当作保存成功。

`skin_sound_request_v1` / `skin_sound_snapshot_v1` 为独立通道，不改变原衣柜目录包的格式。
管理面板等待快照确认、在保存中禁用重复点击，并在超时后刷新实际状态。
快速连续切换时，限频期间的最新请求会延后提交，确认只在实际保存后发送。
播放其他玩家的声音时应读取 `SkinSounds.isEnabled(stack)`，不要使用本机玩家的管理偏好。

## 预览上下文 API

`com.habitrain.lottery.api.skin.client.SkinPreviewContext.currentFor(entity)` 在衣柜 3D 模型渲染期间返回
`Preview`，其他渲染返回 `null`。它提供屏幕 `Session`、选中的 `entry`、选择变更 `revision` 和 `startedAt`。
一个 `Session` 属于一次屏幕生命周期；切换皮肤重新计时，普通游戏 tick 不重新计时。
扩展应据此维护独立预览动画，避免临时持握污染真实持枪状态，或被真实背包的清理逻辑反复重置。

扩展依赖 `habitrain_lottery >=1.1.49`，不需要修改皮肤定义 v3 或特效 v1 的版本常量。
