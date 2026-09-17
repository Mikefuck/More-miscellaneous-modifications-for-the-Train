# 哈比列车皮肤 API v1

`habitrain_lottery` 1.1.0 起提供公共皮肤注册 API。扩展模组可以注册刀、枪、棍、手雷和帽子皮肤；武器皮肤可以使用扩展模组自己的模型命名空间，并选择是否加入抽奖池。

## 运行要求

- Minecraft 1.21.1、Fabric Loader、Java 21。
- 扩展模组的客户端和服务端必须安装相同版本；客户端需要注册表信息和模型资源，服务端需要同序注册表和抽奖配置。
- 扩展模组应在 `fabric.mod.json` 中依赖 `habitrain_lottery >= 1.1.0`。
- 推荐使用专用入口点 `habitrain_lottery_skins`。API 会按提供者 mod id 排序后调用入口点，以保持客户端和服务端的 SRE 整数皮肤 ID 一致。

如果扩展项目通过本地 JAR 编译，可以把本模组 JAR 放进其 `libs/`，并加入：

```groovy
modImplementation files("libs/habitrain_lottery-1.1.0.jar")
```

## 最小接入

扩展模组的 `fabric.mod.json`：

```json
{
  "entrypoints": {
    "main": ["com.example.ExampleMod"],
    "habitrain_lottery_skins": ["com.example.ExampleSkinRegistrar"]
  },
  "depends": {
    "minecraft": "~1.21.1",
    "fabricloader": ">=0.18.1",
    "habitrain_lottery": ">=1.1.0"
  }
}
```

注册类：

```java
package com.example;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinRegistrar;
import net.minecraft.resources.ResourceLocation;

public final class ExampleSkinRegistrar implements SkinRegistrar {
    @Override
    public void registerSkins() {
        HabiSkinApi.register(SkinDefinition.builder(
                        "knife",
                        "example_crystal_blade",
                        0xFF55CCFF)
                .model(ResourceLocation.fromNamespaceAndPath(
                        "example_mod",
                        "item/skins/knife/crystal_blade"))
                .includeInDefaultPools()
                .build());
    }
}
```

注册发生在 SRE 收集皮肤模型之前。也可以从普通 `ModInitializer` 直接调用 `HabiSkinApi.register`，但专用入口点更容易保证两端顺序一致。

## 模型与语言资源

上例需要在扩展模组 JAR 中提供：

```text
assets/example_mod/models/item/skins/knife/crystal_blade.json
assets/example_mod/models/item/skins/knife/crystal_blade_in_hand.json
assets/example_mod/textures/item/skins/knife/crystal_blade.png
assets/example_mod/lang/zh_cn.json
assets/example_mod/lang/en_us.json
```

`model(...)` 指向基础模型 ID。API 会为手持状态自动在路径末尾追加 `_in_hand`。模型 JSON 中的纹理引用可以继续使用扩展模组自己的命名空间。

语言键沿用 SRE 的全局键格式：

```json
{
  "screen.sre.skins.knife.example_crystal_blade.name": "水晶刃",
  "screen.sre.skins.knife.example_crystal_blade.desc": "由示例模组提供的皮肤"
}
```

如果不调用 `model(...)`，API 会回退到 SRE 原生模型位置：

```text
starrailexpress:item/skins/<type>/<skin_id>
starrailexpress:item/skins/<type>/<skin_id>_in_hand
```

## 类型和 ID 规则

支持的类型：

- `knife`
- `revolver`（注册时也接受 `gun`，内部统一为 `revolver`）
- `bat`
- `grenade`
- `hat`

皮肤 ID 会转换为小写，且只能匹配 `[a-z0-9_.-]+`。`default` 和 `coin` 为保留 ID。SRE 的皮肤 ID 在同一类型中是全局的，建议用自己的 mod id 作为前缀，例如 `example_crystal_blade`。重复提交完全相同的定义是幂等操作；同一 `type/id` 的不同定义会在加载阶段报冲突。

帽子沿用上游的 `sre-skin`/Hats 物品解析规则：注册 `hat` 元数据后，扩展模组仍需提供对应的 `hats:<skin_id>` 物品。上面的自定义模型命名空间桥接用于四种武器皮肤。

## 加入抽奖池

`.includeInDefaultPools()` 会把武器皮肤放入：

- 所有与皮肤类型匹配的奖池的第 0 品质组；
- 所有 `PoolType = "all"` 奖池的第 0 品质组。

枪皮肤会自动使用 SRE 所需的 `gun/<id>` 奖池条目。更细的控制可以使用：

```java
.addToPool("knife", 1)
.addToPool("all", 2)
```

第二个参数是从 0 开始的 `QualityListGroup` 下标。若世界配置中没有对应品质组，该项会跳过。帽子不属于 SRE 武器抽奖，不能声明奖池位置。

API 对奖池的修改只存在于服务端和客户端的运行时副本：

- 不改写世界的 `pools.json`；
- 不会被 Mod Menu 保存进配置；
- 卸载扩展模组后不会留下无效皮肤条目；
- `/hlt reload`、Mod Menu 保存和客户端快照同步后会重新注入。

## 查询与重新注册

```java
HabiSkinApi.find("knife", "example_crystal_blade");
HabiSkinApi.registrations();
HabiSkinApi.size();
HabiSkinApi.API_VERSION;
```

`/hlt skins reregister` 会重放内置皮肤和所有 API 皮肤，不会丢失扩展模组的模型或奖池元数据。
