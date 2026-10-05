# 建筑数据包与规则

服务器建筑库由数据包定义。玩家不能上传蓝图。网络请求只包含服务器已知建筑 ID、定位和确认操作。单人玩家和局域网主机可使用本地蓝图文件夹。读取限定为 Minecraft 1.21.1 的 DataVersion 3955，不执行跨版本转换。

## 文件布局

```text
pack.mcmeta
data/yourpack/prefabs/house.json
data/yourpack/blueprints/house.nbt
```

`pack.mcmeta` 内容：

```json
{"pack":{"pack_format":48,"description":"住宅建筑库"}}
```

建筑 ID 为 `yourpack:house`。嵌套定义 `prefabs/town/house.json` 对应 `yourpack:town/house`。将数据包放入世界的 `datapacks/` 后执行 `/prefab reload`。同 ID 定义遵循 Minecraft 数据包优先级。正式版不包含测试建筑。活动任务保留原蓝图和费用。

`house.json` 内容：

```json
{
  "name": "山地住宅",
  "category": "住宅",
  "source": "yourpack:blueprints/house.nbt",
  "ground_y": 2,
  "ignore_air": false,
  "visible": true,
  "unlock": {"type": "advancement", "id": "minecraft:story/mine_stone"},
  "conditions": {"type": "dimension", "id": "minecraft:overworld"},
  "requirements_text": "完成石器时代；仅限主世界",
  "cost": {
    "mode": "combined",
    "items": [{"id": "minecraft:emerald", "count": 2}],
    "xp": 20
  }
}
```

| 字段 | 含义与默认值 |
| --- | --- |
| `name` | 显示名称；默认使用建筑 ID |
| `category` | 分类；默认 `general` |
| `source` | 必填；蓝图资源路径 |
| `ground_y` | 参考层索引；默认 0 |
| `ignore_air` | 是否跳过空气；默认 `false` |
| `visible` | 可见规则；默认通过 |
| `unlock` | 解锁规则；默认通过 |
| `conditions` | 建造条件；默认通过 |
| `requirements_text` | 作者提供的条件说明；默认自动生成 |
| `cost` | 必填；费用定义 |

参考层从统一蓝图的最低层开始计数。位置 A 的世界 Y 对应该层。参考层以下的建筑进入地下。Mod 不自动识别地形。名称、分类和作者提供的条件说明按原文显示。

## 导入和覆盖

- `.nbt` 必须有 `size`、`palette` 或 `palettes`、`blocks` 和可选 `entities`。多套 `palettes` 固定采用第一套，避免随机内容。`structure_void` 跳过；明确记录的空气覆盖原方块。
- `.litematic` 支持版本 5 至 7，保留多区域、负尺寸、方块实体、实体和待执行方块/流体 Tick。统一坐标从所有区域的最小边界开始；区域间未选择的位置保持原样。
- `ignore_air: true` 是作者明确选择的粘贴策略，仅排除源文件的空气位置。默认保留空气语义。不推测源文件没有保存的 Litematica 粘贴选项。
- 相同内容的重叠位置合并；状态或 NBT 不同的重叠区域拒绝导入。未知方块/属性、方块实体类型不匹配、未知实体、损坏文件、越界 Tick 和重复实体 UUID 均拒绝建造，并在库中显示错误。
- 箱子库存、机器配置、告示牌、旗帜、实体装备保留在服务器蓝图中。预览不发送箱子库存或交易数据，仅发送渲染必要信息及实体外观。复杂机器的预览 NBT 可由扩展接口增加。
- 常规库存和装备格式中的物品 ID、数据组件 ID 会检查是否存在；缺失对应模组时拒绝导入。模组自定义的库存字段或组件内部语义仍需要对应适配器校验。

## 成本

`cost` 必须明确配置，免费也要写 `{"mode":"free"}`。

| `mode` | 含义 |
| --- | --- |
| `free` | 免费，忽略其他费用项 |
| `manual` | 仅作者配置的费用 |
| `auto` | 自动建筑材料，加上作者显式写入的费用项 |
| `combined` | 自动建筑材料与手动费用组合 |

`items` 指定物品 ID 和正整数数量。预留和返还物品时保留原数据组件。`xp` 指经验点数。`viscriptshop` 指 ViScriptShop 原生玩家余额。金额必须非负，最多两位小数。`custom` 格式、费用协议和费用返还要求见[费用自定义](COSTS.md)。

创造模式免除所有费用。核心不会调用创造任务的自定义费用提供者。生存模式按定义收费。两种模式都要通过规则和位置权限检查。Mod 自动生成的条件和费用摘要使用玩家语言。

自动计价只计算建筑方块。门和双层植物只收下半部分，床只收脚端，双层台阶收两块。水免费。岩浆先消耗岩浆桶，剩余格数从绑定的超越维度主网络扣取每格 1000 mB 岩浆。手动配置的水桶和岩浆桶仍按物品收费。来源顺序和范围见[资源兼容](RESOURCE-COMPAT.md)。

红石线按红石粉计价。活塞头、移动活塞、气泡柱和火焰没有独立材料费用。蓝图自带库存和实体装备不会额外收费。无法推导物品的模组方块会报错，作者需要使用手动定价。当前实现不支持任意模组配方逆向推导或单块材料映射表。旧报价保留原计费规则，见[自动材料](COSTS.md#自动材料)。

## 规则

`visible`、`unlock`、`conditions` 使用同一种语法。将规则写入建筑 JSON 的对应字段。

| 字段 | 检查结果 |
| --- | --- |
| `visible` | 不通过时隐藏条目，也不发送预览 |
| `unlock` | 不通过时显示锁定条目，禁止选择和建造 |
| `conditions` | 检查当前建造条件；建造时重新检查 |

省略字段表示通过。`requirements_text` 只显示说明，不执行检查。免费建筑仍需通过规则。

### 写入解锁条件

以下片段用于合并或替换建筑 JSON 中的字段。它们不是完整建筑定义。完整定义仍需 `source` 和 `cost`。

不设解锁门槛：

```json
{"unlock": true}
```

暂时禁止解锁时，改为 `"unlock": false`。要求玩家完成原版或数据包进度时，使用进度资源 ID：

```json
{
  "unlock": {"type": "advancement", "id": "minecraft:story/mine_stone"},
  "requirements_text": "完成石器时代。"
}
```

要求完成 FTB 任务时，使用 `ftb_quest`。任务 ID 的获取方法见下方 [FTB 联动](#ftb-联动)。

```json
{
  "unlock": {"type": "ftb_quest", "id": "0123456789ABCDEF"},
  "requirements_text": "完成住宅许可任务。"
}
```

`0123456789ABCDEF` 是占位 ID。必须替换为你的实际任务 ID。

### 组合规则

使用 `all` 要求全部条件通过。下面要求完成石器时代和指定 FTB 任务，并限制在主世界建造：

```json
{
  "unlock": {
    "type": "all",
    "rules": [
      {"type": "advancement", "id": "minecraft:story/mine_stone"},
      {"type": "ftb_quest", "id": "0123456789ABCDEF"}
    ]
  },
  "conditions": {"type": "dimension", "id": "minecraft:overworld"},
  "requirements_text": "完成石器时代和住宅许可任务。仅限主世界。"
}
```

使用 `any` 允许任意一项通过。下面允许完成任务或取得个人许可：

```json
{
  "unlock": {
    "type": "any",
    "rules": [
      {"type": "ftb_quest", "id": "0123456789ABCDEF"},
      {"type": "flag", "scope": "player", "id": "housing_tier_2"}
    ]
  },
  "requirements_text": "完成住宅许可任务，或取得二级住宅许可。"
}
```

使用 `not` 排除一个条件。下面禁止在下界建造：

```json
{
  "conditions": {
    "type": "not",
    "rule": {"type": "dimension", "id": "minecraft:the_nether"}
  }
}
```

### 规则类型

| 规则 | 字段 |
| --- | --- |
| 布尔值 | `true` 或 `false` |
| 数组 / `all` | 全部满足；对象使用 `rules` 数组 |
| `any` | 任意一项满足，使用 `rules` 数组 |
| `not` | 否定 `rule` 子规则 |
| `advancement` | 原版/数据包进度 `id` |
| `ftb_quest` | FTB 任务对象的十六进制 `id` |
| `dimension` | 维度资源 `id` |
| `flag` | 标记 `id`，`scope` 为 `player` 或 `team` |
| `script` | 已注册的规则 `id` |

所需联动或脚本缺失时拒绝对应建筑。`not` 和 `any` 也不能绕过缺失依赖。例如，上面的 `any` 包含 FTB 任务规则，因此仍需安装 FTB Quests。注册 `script` 规则的方法见 [KubeJS 指南](KUBEJS.md#注册规则)。

## FTB 联动

### 依赖与用途

| 模组 | 用途 |
| --- | --- |
| FTB Quests | `ftb_quest` 读取任务完成状态 |
| FTB Teams | 提供当前团队 ID；`flag` 的 `team` 范围使用该 ID |
| FTB Chunks | 自动检查建筑位置的领地编辑权限 |

安装对应模组的依赖，包括 FTB Library。任务解锁直接由 Prefab Deploy 检查，不需要 KubeJS。已验证的联动版本见 [Java API](API.md#已验证的可选版本)。

### 获取任务 ID

1. 管理员打开 FTB 任务书，进入编辑模式。
2. 右键目标任务节点，选择“复制 ID”（Copy ID）。
3. 将复制的十六进制 ID 写入 `unlock.id`，保留字符串引号。
4. 保存建筑 JSON，执行 `/prefab reload`，重新打开建筑库。

需要整项任务完成后解锁时，复制任务节点的 ID。不要复制章节、奖励或任务内子目标的 ID。任务标题和配置文件名也不能代替 ID。ID 不带 `0x` 前缀。“复制 ID”入口见 [FTB Quests 官方说明](https://github.com/FTBTeam/FTB-Quests/blob/main/CHANGELOG.md)。

也可在实例的 `config/ftbquests/quests/chapters/*.snbt` 中查找任务。使用 `quests` 列表内对应任务的 `id`，不要使用文件顶部的章节 `id`。

`ftb_quest` 检查玩家当前 FTB 团队的任务完成状态。它不检查是否领取奖励。任务完成后，重新打开建筑库查看解锁结果。任务状态被重置后，该规则会再次阻止建造。

### 个人许可与团队许可

`flag` 是 Prefab Deploy 保存的解锁标记。它不会自动读取 FTB 任务、GameStages 或商店购买记录。通过 KubeJS 或 Java 接口授予标记。

个人许可只解锁该玩家：

```json
{
  "unlock": {"type": "flag", "scope": "player", "id": "housing_tier_2"},
  "requirements_text": "取得二级住宅许可。"
}
```

团队许可解锁当前 FTB 团队的成员：

```json
{
  "unlock": {"type": "flag", "scope": "team", "id": "housing_tier_2"},
  "requirements_text": "团队取得二级住宅许可。"
}
```

在有真实服务器玩家对象的脚本事件中调用 `PrefabAPI.grantPlayer(player, 'housing_tier_2')` 或 `PrefabAPI.grantTeam(player, 'housing_tier_2')`。标记名必须与 JSON 一致。撤销时调用对应的 `revokePlayer` 或 `revokeTeam`。事件示例见 [KubeJS 指南](KUBEJS.md#解锁标记)。

团队标记绑定 FTB 团队 ID。玩家换队后使用新团队的标记。需要按任务进度解锁时，直接使用 `ftb_quest`。

### FTB Chunks 领地权限

安装 FTB Chunks 后，Mod 自动逐位置检查编辑权限。建筑 JSON 无需添加领地规则。已解锁的建筑仍需通过领地检查。建筑跨入玩家无权编辑的领地时，整次建造会被拒绝。此类验证失败不会扣费。

## ViScriptShop 货币联动

这里的商店模组是 ViScriptShop。`cost.viscriptshop` 收取发起建造的玩家的原生余额。即使建筑通过团队许可解锁，费用也从该玩家扣取，不使用团队钱包。

仅收取 12.50 商店货币：

```json
{"cost": {"mode": "manual", "viscriptshop": 12.50}}
```

收取自动建筑材料，再加 12.50 商店货币和 20 经验点：

```json
{"cost": {"mode": "combined", "viscriptshop": 12.50, "xp": 20}}
```

金额必须非负，最多两位小数。每次建造都会收费。使用 `free` 模式时不收费。扣款和退款细节见 [费用自定义](COSTS.md#viscriptshop-货币)。

该接口支持 ViScriptShop 原生玩家余额。使用 Magic Coins 时，不能启用 ViScriptShop 的货币替换选项 `isReplaceMoneyToMagicCoin`。替代货币需要单独的可恢复费用提供者。

如果需求是“购买一次，之后永久解锁”，使用 `flag` 和购买成功事件。配置方法见 [购买建筑许可](KUBEJS.md#购买建筑许可)。它与每次建造收取货币分开配置。

## FTB 任务与商店货币组合示例

下面的完整定义要求完成住宅许可任务。每次建造收取 2 个绿宝石、20 经验点和 12.50 商店货币。`manual` 不额外收取自动建筑材料。

```json
{
  "name": "许可住宅",
  "category": "住宅",
  "source": "yourpack:blueprints/house.nbt",
  "ground_y": 2,
  "ignore_air": false,
  "visible": true,
  "unlock": {"type": "ftb_quest", "id": "0123456789ABCDEF"},
  "conditions": {"type": "dimension", "id": "minecraft:overworld"},
  "requirements_text": "完成住宅许可任务。仅限主世界。",
  "cost": {
    "mode": "manual",
    "items": [{"id": "minecraft:emerald", "count": 2}],
    "xp": 20,
    "viscriptshop": 12.50
  }
}
```

替换任务 ID 和蓝图路径。根据蓝图设置参考层。需要玩家提供建筑材料时，将模式改为 `combined`。

ViScriptShop 1.2.2.4 提供 FTB Quests 的“VSS虚拟货币”奖励。可在任务编辑器中添加该奖励，并设置金额，例如 25。玩家领取奖励后，余额增加，可支付上面的建造费用。该奖励由 ViScriptShop 提供，见[奖励实现](https://github.com/zhenshiz/ViScriptShop/blob/master/src/main/java/com/viscriptshop/compat/ftbquests/VirtualCurrencyReward.java)。

完成任务与领取奖励是两步。未领取奖励时，建筑可能已解锁，但余额仍不足。普通金币物品不会自动计入 ViScriptShop 余额。

使用生存模式验证以下流程：

1. 未完成任务时，建筑显示为锁定。
2. 完成任务后，重新打开建筑库。建筑解锁。
3. 领取货币奖励，准备物品和经验点。
4. 在有编辑权限的位置建造，核对扣款。
5. 移除所需物品或降低余额，再尝试建造。费用不足时应拒绝建造。
6. 在无编辑权限的领地尝试建造。验证整次建造被拒绝，费用保持原值。

## 联动排查

| 现象 | 检查项 |
| --- | --- |
| 建筑没有显示 | 检查 `visible`、数据包是否启用及导入错误 |
| FTB 任务完成后仍锁定 | 检查实际任务 ID、当前团队和任务完成状态；修正配置后重载，再重新打开建筑库 |
| 领取了任务奖励，但建筑仍锁定 | 检查建筑使用的解锁规则；`ftb_quest` 读取任务完成状态，`flag` 则需要单独授予标记 |
| 团队许可不生效 | 检查 FTB Teams、当前团队、`scope` 和标记名 |
| 写了条件说明，但未阻止建造 | `requirements_text` 只显示文本；同时配置 `unlock` 或 `conditions` |
| 已解锁，但不能建造 | 检查建造条件、费用、蓝图错误和位置权限 |
| 商店货币不可用 | 检查 ViScriptShop 依赖、原生余额模式及服务端日志 |
| 商店购买后没有解锁 | 检查 KubeJS 服务器脚本、购买成功事件和实际商店名称 |
| 背包或网络资源不可用 | 检查兼容模组版本、随身背包和当前绑定网络；具体来源范围见[资源兼容](RESOURCE-COMPAT.md) |
| 资源等待返还 | 检查原存储是否可用且有足够空间；修复后按[管理员说明](ADMIN.md#建造事务和恢复)执行恢复 |

修改建筑 JSON 后执行 `/prefab reload`。修改 KubeJS 脚本后，先重载服务器脚本或重启，再执行 `/prefab reload`。建筑重载命令不会重读脚本文件。

## 开发测试资源

执行 `python tools/create_examples.py` 重建 `src/testFixtures/resources/` 中的测试建筑和测试结构。构建脚本仅在 GameTest 或客户端测试时装入该数据包。正式 JAR 不包含它。

执行 `python tools/package_examples.py` 重建 `examples/performance/` 和性能测试 ZIP。性能建筑包含空气覆盖，费用为免费。只在测试世界装入它们。

中英文文本在 `tools/localization.py` 中维护。执行 `python tools/localization.py` 更新语言资源。只修改生成的 JSON 会被下次生成覆盖。英文错误显示文字在 `ERROR_DISPLAY_EN` 中按现有翻译键覆盖；原始诊断句用于生成键名和匹配规则，不要因润色显示文字而修改。以上脚本仅依赖 Python 标准库。

## 本地蓝图与数据包

本地文件夹位于 `<instance>/prefabdeploy/blueprints/`。子目录中的 `.nbt` 和 `.litematic` 也会读取。重新打开建筑库检查变更。本地蓝图不需要 JSON。默认名称是文件名，参考层为 0，保留空气覆盖，费用模式为 `auto`。

本地建筑 ID 使用 `prefabdeploy_local` 命名空间。ID 根据相对路径计算。这个命名空间保留给本地入口。数据包请使用自己的命名空间。文件内容变化不会改变 ID，移动或改名会改变 ID。

只有单人玩家或局域网主机能读取和建造本地建筑。局域网加入者看不到这些条目。专用服务器仅使用数据包建筑。本地建筑需要长期共享时，将蓝图放入数据包并编写建筑定义。

KubeJS 可修改本地建筑的参考层、费用和规则，见[KubeJS 指南](KUBEJS.md)。需要 `ignore_air` 或固定的可读 ID 时，使用数据包定义。

## 建筑热加载

服务器控制台执行 `prefab reload`。游戏内管理员执行 `/prefab reload`。需要权限等级 2。命令发现新增数据包，并重读建筑定义、蓝图、费用和规则。世界已禁用的数据包不会被自动启用。

完成时显示建筑总数和不可用数量。损坏定义会显示为不可用条目。全局重载失败时检查日志。命令未完成前不能再次启动重载。活动定位和建造保留原快照。新选择使用新定义。

单人玩家或局域网主机也可用该命令刷新本地蓝图。局域网加入者仍不能访问这些建筑。命令不重读服务器 TOML 配置。
