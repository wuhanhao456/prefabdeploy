# 给 AI 的建筑数据包与魔改指南

本文件供 AI agent 使用。按用户需求创建 Prefab Deploy 建筑数据包，配置建筑、解锁条件和费用。修改整合包规则时，优先使用数据包和 KubeJS 接口。

目标环境为 Minecraft 1.21.1 和 Prefab Deploy 0.1.2。蓝图必须使用 DataVersion 3955。开始任务前读取 [README](README.md) 和下列相关文档。不要猜测字段、事件或接口名称。

## 选择实现方式

| 用户需求 | 实现方式 | 详细说明 |
| --- | --- | --- |
| 添加建筑、设置参考层和空气覆盖 | 建筑数据包 | [数据包指南](docs/DATAPACKS.md) |
| 设置可见范围、解锁需求和部署条件 | 建筑 JSON 中的规则 | [规则类型](docs/DATAPACKS.md#规则) |
| 设置材料、物品、XP 或货币费用 | 建筑 JSON 中的 `cost` | [费用指南](docs/COSTS.md) |
| 修改现有建筑、增加动态条件或解锁标记 | KubeJS 服务器脚本 | [KubeJS 指南](docs/KUBEJS.md) |
| 增加导入格式、费用提供者或机器 NBT 适配 | Java 扩展接口 | [Java API](docs/API.md) |

使用现有接口能完成的需求，直接生成数据包或脚本。需要扩展时，再实现对应的 Java 接口。

## 确定建筑需求

先从用户描述和现有文件中确定以下内容：

- 建筑 ID、显示名称和分类。
- 蓝图来源、尺寸、参考层和空气覆盖方式。
- 谁能看到建筑，以及需要完成哪些解锁需求。
- 部署时需要满足的条件，例如维度或脚本规则。
- 费用模式、物品数量、XP 点数和可选货币。
- 使用单人世界、局域网还是专用服务器。

只有影响结果且无法确定的信息才需要询问。其余选择写入交付说明。保留用户指定的建筑、规则和费用。

用户提供蓝图时，读取并校验文件。用户要求生成建筑时，按明确的设计生成真实结构 NBT。不要用空文件、改名文件或仅有 JSON 的目录代替建筑蓝图。

## 创建建筑数据包

使用自己的命名空间，例如 `yourpack`。资源 ID 使用小写字母、数字、下划线等合法字符。不要使用保留的 `prefabdeploy_local` 命名空间。

生成以下目录。压缩包的根目录必须直接包含 `pack.mcmeta`。

```text
yourpack/
├── pack.mcmeta
└── data/
    └── yourpack/
        ├── prefabs/
        │   └── house.json
        └── blueprints/
            └── house.nbt
```

`pack.mcmeta`：

```json
{"pack":{"pack_format":48,"description":"自定义住宅建筑库"}}
```

`data/yourpack/prefabs/house.json`：

```json
{
  "name": "住宅",
  "category": "住宅",
  "source": "yourpack:blueprints/house.nbt",
  "ground_y": 1,
  "ignore_air": false,
  "visible": true,
  "unlock": {"type": "advancement", "id": "minecraft:story/mine_stone"},
  "conditions": {"type": "dimension", "id": "minecraft:overworld"},
  "requirements_text": "完成石器时代。仅限主世界。",
  "cost": {
    "mode": "manual",
    "items": [{"id": "minecraft:emerald", "count": 4}],
    "xp": 20
  }
}
```

该定义的建筑 ID 是 `yourpack:house`。复制或生成对应的 `house.nbt`。根据实际蓝图设置 `ground_y`，不要直接套用示例值。参考层从最低层的 0 开始计数。参考层以下的建筑进入地下。

读取 `.nbt` 时检查结构格式。读取 `.litematic` 时检查格式版本 5–7。两种格式都必须满足 DataVersion 3955。Create NBT 只有符合原版结构格式时才能使用。本版本不支持 `.schem` 或跨版本转换。

默认保留空气覆盖。只有用户要求跳过蓝图中的空气时才设置 `ignore_air: true`。Litematic 区域之间未选择的位置保持原样。校验模组方块、实体、方块实体和物品组件所需的依赖。

## 配置解锁需求

按用途选择规则字段：

- `visible` 决定是否发送建筑条目和预览。
- `unlock` 决定是否解锁。未解锁的建筑仍可显示。
- `conditions` 在部署时重新检查。

省略规则表示通过。可用规则包括 `advancement`、`ftb_quest`、`dimension`、`flag` 和 `script`。组合规则使用 `all`、`any` 和 `not`。具体字段见[规则类型](docs/DATAPACKS.md#规则)。

FTB 任务 ID 必须来自实际任务配置。脚本规则 ID 必须已经注册。团队标记需要 FTB Teams。缺少依赖时会拒绝对应建筑，不能用 `not` 或 `any` 绕过。

FTB 任务解锁读取当前团队的任务完成状态。个人或团队许可使用本 Mod 的 `flag`，需要接口授予标记。任务 ID、领地权限和货币组合示例见 [FTB 联动](docs/DATAPACKS.md#ftb-联动)。不要把完成任务、领取奖励和授予标记当成同一操作。

ViScriptShop 每次部署收费使用 `cost.viscriptshop`。购买一次后解锁使用购买成功事件和 `flag`，见 [购买建筑许可](docs/KUBEJS.md#购买建筑许可)。该示例按商店名称匹配，需要独立许可商店。不要猜测商品 ID getter。

`requirements_text` 只提供条件说明。它不会执行解锁检查。始终同时配置实际规则。名称、分类、条件说明和脚本返回的普通文本按原文显示。多语言需求必须明确处理这些作者文本。

## 配置消耗

每个建筑都写出 `cost`。按用户需求选择模式：

| 模式 | 用途 |
| --- | --- |
| `free` | 免费部署 |
| `manual` | 只收取配置的费用 |
| `auto` | 自动计算建筑材料，并加入配置的费用 |
| `combined` | 自动材料加手动费用；收费行为与 `auto` 相同 |

物品数量必须是正整数。`xp` 使用经验点数。`viscriptshop` 使用原生玩家余额，金额非负，最多两位小数。使用货币前确认安装对应依赖。

自动材料只计算建筑方块，不额外收取蓝图库存或实体装备。无法推导物品的模组方块需要手动定价。创造模式免除费用，仍检查规则和位置权限。验证收费时使用生存模式。

资源兼容构建按玩家库存、随身精妙背包、当前绑定的超越维度主网络扣取物品。数量可以跨来源合并。自动水方块免费。自动岩浆先消耗岩浆桶，剩余格数从网络扣取每格 1000 mB 岩浆。手动桶费用仍按物品收费。支持版本和来源范围见[资源兼容](docs/RESOURCE-COMPAT.md)。不要在脚本中重复扣取这些资源。

自定义积分或货币使用 `custom` 和已注册的费用提供者。不要在部署事件中另行扣费。提供者必须支持事务幂等、退款和玩家 NBT 原子保存。实现细节见[费用指南](docs/COSTS.md)。

## 使用 KubeJS 魔改

将脚本放入实例的 `kubejs/server_scripts/`。先确认实际建筑 ID。使用 `PrefabEvents.registry` 修改已有定义：

```js
PrefabEvents.registry(event => {
  const id = 'yourpack:house'
  if (!event.getIds().contains(id)) return
  event.configure(id, JSON.stringify({
    unlock: {type: 'flag', scope: 'player', id: 'housing_tier_2'},
    requirements_text: '获得二级住宅许可。',
    cost: {mode: 'combined', items: [{id: 'minecraft:emerald', count: 2}], xp: 10}
  }))
})
```

在具有真实玩家对象的任务或脚本上下文中，调用 `PrefabAPI.grantPlayer(player, 'housing_tier_2')` 授予标记。撤销时调用 `revokePlayer`。团队标记使用对应的团队方法。

动态规则使用 `event.registerRule`。额外部署检查使用 `PrefabEvents.beforeDeploy`。结果通知使用 `PrefabEvents.completed`。可修改的示例和事件参数见[KubeJS 指南](docs/KUBEJS.md)。

`configure` 支持名称、分类、参考层、规则和费用。修改 `source` 或 `ignore_air` 时编辑数据包，再重载。`configure` 更新顶层字段，提供 `cost` 等对象时写出完整的目标对象。

本地建筑 ID 来自相对文件路径，可在 GUI 条目提示中读取。修改文件内容不会改变 ID。改名或移动会改变 ID。共享给服务器玩家的建筑应使用数据包。

## 验证与交付

1. 解析全部 JSON，检查字段类型、资源 ID 和源文件路径。
2. 核对蓝图版本、尺寸、参考层、空气覆盖和所需模组。
3. 将数据包放入测试世界的 `datapacks/`。管理员执行 `/prefab reload`。
4. 检查建筑库和服务器日志。验证预览、旋转、地下部分和实际部署。
5. 验证未解锁、已解锁及条件不满足的情况。使用生存模式核对费用。
6. 检查费用不足时是否拒绝部署。涉及自定义费用时验证退款和重启恢复。
7. 交付数据包或脚本，并写明安装路径、依赖、建筑 ID、解锁需求、费用和重载步骤。

重载需要权限等级 2。世界禁用的数据包不会自动启用。新选择使用新定义。活动定位和部署保留原快照。

修改 KubeJS 脚本后，按所用 KubeJS 版本的流程重载服务器脚本或重启。随后执行 `/prefab reload` 重新应用建筑注册事件。不要把建筑重载命令当作脚本文件重载命令。

只能根据实际验证报告结果。无法运行游戏时，明确列出已完成的静态检查和仍待游戏验证的项目。文档使用短句、具体动词和一致术语。

## 需要扩展代码时

通过 `PrefabApi.IMPORTERS`、`RULES`、`COSTS` 和 `NBT_ADAPTERS` 扩展对应功能。先阅读 [Java API](docs/API.md)。保留本地建筑权限、蓝图版本限制和活动任务快照。不要让脚本或扩展绕过位置保护。

文件解析在后台线程执行。世界修改和目录发布在服务器线程执行。涉及费用的扩展必须遵守事务要求。修改源码后，按[验证记录](docs/TESTING-0.1.2.md#复现)执行相关构建和回归。
