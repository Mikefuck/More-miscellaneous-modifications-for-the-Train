# 箱子与钥匙清单

`com.habitrain.lottery.crate.CrateCatalog` 是箱子系统的唯一清单。开箱服务、仓库注册、管理员命令和列车邮件都从这里读取；新增箱子时只需在清单中增加一项，并提供对应的皮肤品质配置。

| ID | 箱子账户道具 | 专用钥匙账户道具 |
| --- | --- | --- |
| `woodland` | `habitrain_lottery:crate_woodland` | `habitrain_lottery:key_woodland` |
| `cobalt` | `habitrain_lottery:crate_cobalt` | `habitrain_lottery:key_cobalt` |
| `amethyst` | `habitrain_lottery:crate_amethyst` | `habitrain_lottery:key_amethyst` |
| `gilded` | `habitrain_lottery:crate_gilded` | `habitrain_lottery:key_gilded` |
| `crimson` | `habitrain_lottery:crate_crimson` | `habitrain_lottery:key_crimson` |
| `prismatic` | `habitrain_lottery:crate_prismatic` | `habitrain_lottery:key_prismatic` |

## 邮件发放

公共邮件 API：

```java
HabiMailApi.crate("cobalt", 2);
HabiMailApi.key("cobalt", 2);
```

邮件撰写界面会直接显示清单中的箱子和钥匙。领取邮件时，附件会写入玩家账户仓库的系统道具余额；在线、离线、全员发信共用同一套编码和领取事务。

## 开箱管理配置

服务器把开箱管理保存到 `world/habitrain_lottery/config/crates.json`。旧版只包含
`weeklyCaps`/`monthlyCaps` 的存档仍然有效，服务启动时会自动补齐默认箱子池。

```json
{
  "schemaVersion": 2,
  "weeklyCaps": { "white": 2000, "blue": 1000 },
  "monthlyCaps": { "white": 8000, "blue": 4000 },
  "pools": {
    "woodland": {
      "enabled": true,
      "customPool": true,
      "duplicateProtection": true,
      "skinWeights": {
        "knife/emerald_claw": 100,
        "hat/traffic_cone": 25
      }
    }
  }
}
```

`skinWeights` 使用 `type/id` 作为键，权重是非负整数；实际概率等于该皮肤权重除以当前箱子有效权重总和。权重为 0 的皮肤不会掉落。启用自定义池时必须至少有一个正权重皮肤，未知或已卸载的皮肤会被保留为 0 权重。关闭 `customPool` 会恢复该箱子的默认品质池：普通箱按 `primary` 品质，棱彩箱混合全部品质。

管理员可从 Mod Menu 的“开箱管理”分区进入逐箱编辑器，调整启停、皮肤搜索与品质筛选、权重、重复保护以及全服周/月品质配额。保存仍由服务器 OP 和 Mod Menu 授权共同校验。
