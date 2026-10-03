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
| `conditions` | 部署条件；默认通过 |
| `requirements_text` | 作者提供的条件说明；默认自动生成 |
| `cost` | 必填；费用定义 |

参考层从统一蓝图的最低层开始计数。位置 A 的世界 Y 对应该层。参考层以下的建筑进入地下。Mod 不自动识别地形。名称、分类和作者提供的条件说明按原文显示。

## 导入和覆盖

- `.nbt` 必须有 `size`、`palette` 或 `palettes`、`blocks` 和可选 `entities`。多套 `palettes` 固定采用第一套，避免随机内容。`structure_void` 跳过；明确记录的空气覆盖原方块。
- `.litematic` 支持版本 5 至 7，保留多区域、负尺寸、方块实体、实体和待执行方块/流体 Tick。统一坐标从所有区域的最小边界开始；区域间未选择的位置保持原样。
- `ignore_air: true` 是作者明确选择的粘贴策略，仅排除源文件的空气位置。默认保留空气语义。不推测源文件没有保存的 Litematica 粘贴选项。
- 相同内容的重叠位置合并；状态或 NBT 不同的重叠区域拒绝导入。未知方块/属性、方块实体类型不匹配、未知实体、损坏文件、越界 Tick 和重复实体 UUID 均拒绝部署，并在库中显示错误。
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

`items` 指定物品 ID 和正整数数量。预留物品保留数据组件。退款返还相同的物品组件。`xp` 指经验点数。`viscriptshop` 指 ViScriptShop 内置货币。金额必须非负，最多两位小数。`custom` 格式、费用协议和退款要求见[费用自定义](COSTS.md)。

创造模式免除所有费用。核心不会调用创造任务的自定义费用提供者。生存模式按定义收费。两种模式都要通过规则和位置权限检查。Mod 自动生成的条件和费用摘要使用玩家语言。

自动计价只计算建筑方块。门和双层植物只收下半部分，床只收脚端，双层台阶收两块；水/岩浆按桶、红石线按红石粉计价。活塞头、移动活塞、气泡柱和火焰没有独立材料费用。蓝图自带库存和实体装备不会额外收费。无法推导物品的模组方块会报错，作者需要使用手动定价。首版没有任意模组配方逆向推导或单块材料映射表。

## 规则

`visible`、`unlock`、`conditions` 使用同一种语法。`visible` 不通过时，该建筑定义和预览都不发送给玩家；`unlock` 不通过仍可看到锁定条目；`conditions` 在部署时重新检查。省略规则表示通过。

```json
{"type":"all","rules":[
  {"type":"ftb_quest","id":"0123456789ABCDEF"},
  {"type":"flag","scope":"team","id":"housing_tier_2"},
  {"type":"dimension","id":"minecraft:overworld"}
]}
```

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

所需联动或脚本缺失时关闭对应建筑，`not` 和 `any` 也不能绕过缺失依赖。FTB Chunks 安装后自动逐位置检查编辑权限，无需在定义中再写领地规则。可用脚本实现额外地形、坐标或整合包限制。

## 开发测试资源

执行 `python tools/create_examples.py` 重建 `src/testFixtures/resources/` 中的测试建筑和测试结构。构建脚本仅在 GameTest 或客户端测试时装入该数据包。正式 JAR 不包含它。

执行 `python tools/package_examples.py` 重建 `examples/performance/` 和性能测试 ZIP。性能建筑包含空气覆盖，费用为免费。只在测试世界装入它们。

中英文文本在 `tools/localization.py` 中维护。执行 `python tools/localization.py` 更新语言资源。以上脚本仅依赖 Python 标准库。

## 本地蓝图与数据包

本地文件夹位于 `<instance>/prefabdeploy/blueprints/`。子目录中的 `.nbt` 和 `.litematic` 也会读取。重新打开建筑库检查变更。本地蓝图不需要 JSON。默认名称是文件名，参考层为 0，保留空气覆盖，费用模式为 `auto`。

本地建筑 ID 使用 `prefabdeploy_local` 命名空间。ID 根据相对路径计算。这个命名空间保留给本地入口。数据包请使用自己的命名空间。文件内容变化不会改变 ID，移动或改名会改变 ID。

只有单人玩家或局域网主机能读取和部署本地建筑。局域网加入者看不到这些条目。专用服务器仅使用数据包建筑。本地建筑需要长期共享时，将蓝图放入数据包并编写建筑定义。

KubeJS 可修改本地建筑的参考层、费用和规则，见[KubeJS 指南](KUBEJS.md)。需要 `ignore_air` 或固定的可读 ID 时，使用数据包定义。

## 建筑热加载

服务器控制台执行 `prefab reload`。游戏内管理员执行 `/prefab reload`。需要权限等级 2。命令发现新增数据包，并重读建筑定义、蓝图、费用和规则。世界已禁用的数据包不会被自动启用。

完成时显示建筑总数和不可用数量。损坏定义会显示为不可用条目。全局重载失败时检查日志。命令未完成前不能再次启动重载。活动定位和部署保留原快照。新选择使用新定义。

单人玩家或局域网主机也可用该命令刷新本地蓝图。局域网加入者仍不能访问这些建筑。命令不重读服务器 TOML 配置。
