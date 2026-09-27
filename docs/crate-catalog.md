# 箱子与钥匙清单

`com.habitrain.lottery.crate.CrateCatalog` 是服务端和客户端同步的箱子清单。开箱服务、仓库、管理员命令和邮件都从这里读取。下面六箱是内置模板；管理员可以在 Mod Menu 的“开箱管理 → 箱子资料与奖励”中新建或复制箱子，无需修改代码。自定义箱子使用稳定的 `[a-z0-9_.-]{1,48}` ID，箱子和钥匙分别派生为 `habitrain_lottery:crate_<id>` 与 `habitrain_lottery:key_<id>`。

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

邮件撰写界面显示未归档的箱子和钥匙；已发邮件仍能领取归档箱附件。领取时，附件写入玩家账户仓库的系统道具余额；在线、离线、全员发信共用同一套编码和领取事务。停用箱保留玩家余额，但拒绝新的开箱。

## 开箱管理配置

“开箱奖励”页上方是一条可翻页/滚动的奖励配置，显示物品或皮肤外观、名称和基础概率（包括权重为零的停用条目）。点击条目后在下方编辑；“添加奖励”可搜索物品或皮肤目录并按类型、品质筛选。“抽取规则”设置每箱次数、单箱重复和未拥有优先。紧凑窗口用上下按钮或滚轮访问完整设置。

新建箱子和新世界的内置箱从停用的空奖池开始；只把主动添加的内容写入清单，打开页面不会补入默认物品。配置完成后在“资料”中启用并保存。1.1.39 升级时，旧自动品质池固定为当前清单。页面中的物品指现有奖励系统支持的绿苹果及角色卡，皮肤目录来自已安装的皮肤扩展。

服务器把开箱管理保存到 `world/habitrain_lottery/config/crates.json`。当前 schema 为 5；玩家已有箱钥 ID 不变。普通编辑保存不会覆盖服务端产出计数或待提交开箱记录。

```json
{
  "schemaVersion": 5,
  "revision": 7,
  "crates": {
    "festival_2026": {
      "enabled": true,
      "archived": false,
      "name": "庆典补给箱",
      "description": "可开出庆典奖励",
      "tier": "gold",
      "icon": "minecraft:chest",
      "keyName": "庆典钥匙",
      "keyIcon": "minecraft:gold_nugget",
      "appearancePreset": "gilded",
      "badge": "star",
      "customPool": true,
      "duplicateProtection": true,
      "rewardMode": "unified_pool",
      "skinDrawCount": 1,
      "rollCount": 3,
      "minimumSkinCount": 0,
      "allowSameSkinInOneOpen": false,
      "outputLimits": {
        "white": { "period": "weekly", "limit": 2000 },
        "blue": { "period": "weekly", "limit": 200 },
        "purple": { "period": "monthly", "limit": 80 },
        "gold": { "period": "weekly", "limit": 12 },
        "red": { "period": "monthly", "limit": 5 }
      },
      "skinWeights": {
        "knife/emerald_claw": 100,
        "hat/traffic_cone": 25
      },
      "extraRewards": [
        { "type": "green_apples", "amount": 80, "chance": 0.25, "weight": 30, "maxPerOpen": 1 }
      ]
    }
  },
  "outputWeeklyUsed": { "festival_2026": { "gold": 3, "white": 80 } },
  "outputMonthlyUsed": { "festival_2026": { "gold": 9, "white": 160 } },
  "skinProduced": { "knife/emerald_claw": 12 }
}
```

`skinWeights` 使用 `type/id` 作为键，权重是非负整数。管理员在同一个“开箱奖励”页设置皮肤权重、绿苹果和角色卡；可按类型、品质和名称筛选，所有修改一次保存。界面显示的是基础概率；本箱物资配额、未拥有优先和单次不重复都会改变实际概率。权重为 0 的奖励不会掉落；已卸载扩展的配置条目保留原权重但暂停掉落。`customPool` 固定为 `true`，以手工配置的清单为准。`skinProduced` 保留历史累计，旧 `skinLifetimeCaps` 已不参与当前发放规则。

`unified_pool` 是唯一可保存的规则：每箱抽取 `rollCount` 次，默认一次，每抽皮肤、苹果和卡片一起按权重竞争，`minimumSkinCount` 固定为零。概率为此项权重除以全部有效奖励权重。只保留一个权重为 10 的皮肤时，它的基础单抽概率为 100%；“只抽此奖励（100%）”会将其他条目权重设为零、抽取次数设为一。每次开箱消耗一箱一钥匙。

schema 3 及更早的 `skin_plus_bonus` 配置升级时保留皮肤权重，将物品 `chance` 的百分数四舍五入为权重（如 `0.5` → `50`，正概率最低为 1，零概率仍为零），改为每箱一抽。旧 `skinDrawCount=0` 的皮肤保持停用。已有统一池保留权重及抽取次数，取消皮肤保底。奖励数量、持有资产和产出记录保留。`chance`、`skinDrawCount` 字段仅保留用于读取旧配置，升级后不参与正常抽奖。

管理员从 Mod Menu 的“开箱管理”分区进入箱子设置；“物资产出配额”控制本箱五种品质的数量上限。单人世界房主无需开启作弊即可保存；联机客人需要 OP 与 Mod Menu 授权。保存使用配置修订号拒绝过期草稿；冲突时保留输入，点击“重新载入服务端设置”并确认后才能丢弃草稿。`pendingOpens` 属于服务端恢复日志，不应人工改写。

## 1.1.40 物资产出配额

- 先选择箱子，再进入“物资产出配额”。每种品质独立选择按周或按月；`limit` 为实际数量，`-1` 不限、`0` 停产，最大 `1000000000`。同一箱所有玩家共享，各箱之间不互相扣减。
- 皮肤按提供者注册的品质，每件计 1；绿苹果和角色卡沿用公开奖池的白色品质，每个苹果/每张卡计 1。一次产出 80 个苹果计 80，不计为一次开箱或一个礼包。
- 已满额品质从该箱当前候选中排除，其他品质继续按剩余权重抽取。单份奖励不可拆分：剩余 40 时不会发放 80 个苹果。若无法完成本箱全部抽取次数，本次失败且不消耗箱钥、不发放部分奖励、不记产出。
- 进度为已产出加待完成事务预占；同时显示已产出、上限、剩余、预占。无限额模式仍累计产出。页面每 5 秒请求服务端用量，未保存输入不会因刷新丢失；不足高度时可翻页/滚轮查看五种品质。
- 周和月都持续计数，选择只决定执行哪个上限；更改上限、切换周/月、停用后重启用、服务器重启均不会清空当期产出。周在 UTC 周一 00:00、月在 UTC 每月 1 日 00:00 重置，即北京时间 08:00。月额度可任意设置，与周额度没有四倍关系。
- 旧 schema 迁移时，旧全服周品质上限复制为每个箱子的初始周额度；旧月全服限制、开箱次数上限不再执行。旧全服用量/开箱次数无法准确分配成每箱每品质物资数量，因此新计数从升级后开始；旧记录仍保留，不伪造拆分。既有待完成开箱按已记录的皮肤品质及实际物品数量恢复预占与结算。
- 新建箱子默认五种品质不限额；复制箱子仅复制配置，使用新箱 ID 的独立计数。客户端与服务端应同时更新到 1.1.40，旧版编辑器不能写入新配置。


## 1.1.38 开箱预览一致性

公开目录的每个箱子新增 `rewards` 候选数组（kind / id / amount / quality），由服务端与实际开箱共用的皮肤筛选逻辑生成，并按抽取模式选择额外奖励。不会同步管理配置、玩家余额、事务数据或产出记录。

开箱页面不再按箱子品质在客户端重新猜测奖池，也不再添加虚构特殊卡。预览展示当前奖池候选范围；不是个人剩余额度或最终中奖概率的承诺。权重为零、缺少提供者的皮肤与当前抽取模式不会抽到的额外奖励不进入候选。旧自动品质池缺少对应品质时保持空池，不再静默回退到全部品质。

联机应同步更新客户端与服务端到 1.1.38；旧服务端目录缺少候选字段时新版页面保持空预览，不猜测内容。
