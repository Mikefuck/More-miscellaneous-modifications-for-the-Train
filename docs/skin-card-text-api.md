# 皮肤卡片右下角文字 API v1（1.1.52 起）

`com.habitrain.lottery.api.skin.SkinCardText` 为皮肤衣柜**左侧可点击的皮肤卡片右下角**添加一行自定义文字。通过皮肤类型和 ID 精确绑定，只显示在对应卡片上。未声明的卡片保持原样。

## 接入示例

扩展模组依赖 `habitrain_lottery >=1.1.52`，使用既有 `habitrain_lottery_skins` 入口点。在 `registerSkins()` 中声明文字：

```java
package com.example;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinCardText;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.api.skin.SkinRegistrar;
import net.minecraft.network.chat.Component;

public final class ExampleSkins implements SkinRegistrar {
    @Override
    public void registerSkins() {
        HabiSkinApi.register(SkinDefinition.builder("knife", "crystal_blade", SkinQuality.PURPLE)
                .model("example", "item/skins/knife/crystal_blade").build());

        // 普通文本，只显示在 knife/crystal_blade 的卡片右下角。
        SkinCardText.register("knife", "crystal_blade", "活动限定");

        // 也可标记其他模组的皮肤，无须重新注册该皮肤。
        // 对应皮肤尚未注册时也能声明，不依赖模组入口点的执行顺序。
        SkinCardText.register("knife", "ct_knife",
                Component.translatable("example.skin_label.ct_knife").withColor(0xE9BD76));
    }
}
```

`fabric.mod.json`：

```json
{
  "entrypoints": {
    "habitrain_lottery_skins": ["com.example.ExampleSkins"]
  },
  "depends": {
    "habitrain_lottery": ">=1.1.52"
  },
  "custom": {
    "habitrain_lottery:skin_api": {"version": 3}
  }
}
```

翻译放在扩展自身的语言文件中，例如 `assets/example/lang/zh_cn.json`：

```json
{
  "example.skin_label.ct_knife": "CT 专属"
}
```

上例中的模型资源、`crystal_blade` 定义由扩展提供；`ct_knife` 为对应皮肤 ID 示例，应替换为目标皮肤的真实 ID。一段相同的文字要显示在多款皮肤上时，逐个调用 `register` 即可。

## 方法与规则

| 方法 | 行为 |
| --- | --- |
| `register(type, id, String)` | 声明普通文字。新声明返回 `true`，相同声明重放返回 `false`。 |
| `register(type, id, Component)` | 声明支持翻译和样式的文字；可用 `withColor(0xRRGGBB)` 设置颜色。 |
| `find(type, id)` | 返回 `Optional<Component>`；调用方修改返回文字不会改变已保存的文字。 |
| `unregister(type, id)` | 移除文字，返回是否存在；不移除皮肤、不改变解锁或装备状态。 |

- 类型支持 `knife`、`revolver`、`bat`、`grenade`、`hat`、`back`；`gun` 是 `revolver` 的别名。类型和 ID 与皮肤目录使用相同的大小写、空白规范化。
- 每款皮肤只有一份文字声明。重复声明不同内容会抛出 `IllegalStateException`，不会覆盖先注册的内容。需要替换时，先 `unregister`，再 `register`。
- 文字须为非空单行，不支持换行。默认外观和称号卡片不属于这个皮肤 API。
- 卡片右下角按 75% 字体比例绘制并右对齐，保留左侧拥有/装备状态的空间；超长文字用省略号截短。悬停提示和旁白包含完整文字，翻译和颜色在截短时保留。
- 打开的衣柜在下一次绘制时读取文字变化；新增、移除和替换均无须重开页面。
- 注册器失败时，其文字声明随皮肤目录一起回滚；皮肤被 `HabiSkinApi.unregister` 移除时也清理对应文字。
- 这是两端本地注册的展示元数据，不写入玩家存档、不通过网络同步。客户端必须安装声明文字的扩展；仅在服务器调用不会让远端客户端出现文字。推荐通过上述皮肤入口点统一注册，客户端专用扩展也可在 `ClientModInitializer` 中调用。

`SkinCardText.API_VERSION == 1`；已有 `HabiSkinApi.API_VERSION` 仍为 3，旧扩展无需修改。
