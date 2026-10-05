# Prefab Deploy 扩展接口

公开 Java 接口位于 `io.github.prefabdeploy.api`。可选联动通过独立桥接加载，没有安装对应模组时核心功能仍可运行。所有会改世界或玩家状态的扩展都在服务器线程调用。

当前版本为 0.1.3，保留现有公开接口签名。下方可选联动版本的验证记录见[0.1.1 验证](TESTING-0.1.1.md)，当前版本的验证范围见[0.1.3 验证](TESTING-0.1.3.md)。资源兼容的版本和恢复流程见[资源兼容](RESOURCE-COMPAT.md)。

## 导入器和坐标适配

向 `PrefabApi.IMPORTERS` 注册 `BlueprintImporter`，以 `accepts(extension)` 选择格式，`read(CompoundTag, positionLimit)` 返回统一 `Blueprint`。解析位于资源重载后台线程，不得访问世界。返回明确的 `Voxel` 列表以保留覆盖语义，未选择的位置不应补为空气。异常使定义不可建造。

`Blueprint` 包含尺寸、方块状态/方块实体 NBT、带局部连续坐标的实体和计划 Tick。`GridTransform` 把局部边界坐标转换为世界坐标，区分方块索引 `cell` 与连续位置 `point`；在负轴方向使用必要的一格偏移。调用 `NbtTransforms.pos(transform, pos)` 处理方块位置。

向 `PrefabApi.NBT_ADAPTERS` 注册 `NbtTransformAdapter`：

```java
public final class MachineCoordinates implements NbtTransformAdapter {
    public boolean accepts(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("yourmod");
    }
    public void transform(BlockState state, CompoundTag nbt, GridTransform transform) {
        // Transform your mod's stored links, controller coordinates and orientation here.
        // Generic x/y/z and block state rotation have already been applied.
    }
    public void transformEntity(CompoundTag nbt, GridTransform transform) {
        // Optional: inspect nbt.id and transform mod-specific entity links.
        // Called for each entity and passenger after generic coordinate transforms.
    }
    public CompoundTag previewData(BlockState state, CompoundTag source) {
        // Optional whitelist of extra render-only NBT; runs on a background worker.
        return new CompoundTag();
    }
}
```

适配器必须自行识别专有字段，不要重复转换通用 Pos、Rotation、Motion、Tile/Sleeping、Leash 或 UUID 字段。所有源实体身份先生成新 UUID；已知的内部 UUID 引用一并重映射。无法通用恢复的外部网络需要对应模组自己的事务处理。

## 规则和费用

`PlacementRule.check(ServerPlayer, Prefab, BlockPos anchor)` 返回空字符串表示通过，否则返回面向玩家的失败原因。注册到 `PrefabApi.RULES`，在定义中使用 `{"type":"script","id":"yourmod:rule"}`。位置权限始终由核心完整检查；规则不能绕过保护。

`CostProvider` 提供 `quote`、`reserve`、`commit` 和 `refund`。报价是固定的 NBT 快照，事务 ID 是幂等键。当前协议要求 `atomicWithPlayerSave()` 返回 `true`，因为扣款和凭据必须全部位于该玩家自己的 NBT 中，由核心一起原子保存。不要在回调里自行异步保存玩家。

资源兼容保留 `CostProvider` 的公开签名和约束。`MaterialPayments` 与 `ResourceSources` 是内部实现，不属于稳定扩展 API。背包和网络使用内部事务协议，没有注册为自定义费用提供者。

新报价使用 `materialVersion: 2`，保留 `autoLava`，并可含 `boundContainer` 和 `materialTransaction`。工具绑定保存在 `CUSTOM_DATA` 的 `prefabdeploy:container_binding` 中，使用独立的版本 1 格式。缺少材料版本的旧报价仍使用库存协议，材料版本 1 保留原背包与超越维度顺序。玩家凭据保留 `resources` 和 `resourcesReady`。本地容器使用区块附件 `prefabdeploy:container_receipts`，第三方网络使用单独的预写日志。现有 `CostProvider`、KubeJS 事件和 `DeploymentManager.submit` 签名保持兼容。字段用途和保存流程见[资源兼容](RESOURCE-COMPAT.md)。

```json
{"cost":{"mode":"manual","custom":[
  {"provider":"yourmod:points","amount":50}
]}}
```

同一事务 ID 的 `reserve`、`commit` 和 `refund` 必须能重复调用。`reserve` 抛错后仍可能调用 `refund`，因此提供者必须先确认是否已经扣取费用。结果不确定时必须抛错并保留凭据，不能再次支付来试探状态。外部数据库货币需要额外的可恢复协议，不能声明为 `atomicWithPlayerSave`。当前实现不提供外部数据库的通用事务协调。

活动任务在报价时固定费用提供者实例，资源重载不会替换其交易回调。进程重启后由当前同 ID 提供者恢复，因此报价 NBT 应包含提供者自己的版本信息，并保持对已存在报价的兼容；不兼容时应抛错并保留 HELD 记录。

自 0.1.1 起，创造任务的报价含 `creativeExempt: true`、空 `items/custom` 和零 `xp/money`。创造模式无需消耗资源，核心在服务器实际报价阶段跳过费用逻辑，不调用任何 `CostProvider` 的 `atomicWithPlayerSave/quote/reserve/commit/refund` 回调。普通模式交易仍采用原接口。豁免保存于任务日志及原子保存的玩家凭据，恢复时不重新检测模式。`beforeDeploy` 等建造规则事件仍执行；它们应只检查规则，不另行扣费。

恢复日志格式版本仍为 1，仅追加 `beaconRefundCount` 与 `errorDisplay`。缺少字段的旧记录使用原信标数量和原始错误文字，原报价和凭据保持原含义。现有 `DeploymentManager.submit(..., boolean validation)` 保留；新增含实际信标消耗数量的重载。现有 `Sessions.markerPlaced(player, position)` 保留，并新增 `markerPlaced(player, position, consumed)`。

`UiText` 通过 `key/fallback/args` 表示可翻译内容。目录新增 `cost_display/conditions_display/reason_display`，状态和错误新增 `text_display`；原 String 字段继续发送，旧显示数据也能读取。`Rules.Result` 单独保存通过/依赖不可用状态和显示文字，判断不检查翻译后的句子。脚本规则返回的 String、`beforeDeploy.deny(reason)` 以及费用提供者自定义报价错误按普通原文显示。建筑作者的名称、分类和 `requirements_text` 同样不自动翻译。

玩家显示文字在 `tools/localization.py` 中维护。英文错误显示文字可通过 `ERROR_DISPLAY_EN` 按现有键名覆盖。原始诊断句、键名、匹配规则和回退文本保留原值，避免润色显示文字影响旧错误、日志或存档的解析。

ViScriptShop 桥接只支持原生玩家附件余额。若启用已安装的替代货币系统，该费用会拒绝，并要求单独的可恢复费用提供者。金额至多两位小数。未知扣款/退款结果保留 `moneyUncertain`，管理员使用 receipt 和 reconcile_currency 核对。

## KubeJS

安装 KubeJS 1.21.1 后，把 `examples/kubejs/server_scripts/prefab_rules.js` 复制到实例 `kubejs/server_scripts/`。事件均为服务器事件：

| 事件 | 方法 |
| --- | --- |
| `PrefabEvents.registry` | `getIds()`、`configure(id, JSON字符串)`、`registerRule(id, PlacementRule)`、`registerCost(id, CostProvider)` |
| `PrefabEvents.beforeDeploy` | `getPlayer()`、`getPrefabId()`、`deny(reason)` |
| `PrefabEvents.completed` | `getPlayer()`、`getPrefabId()`、`getTransaction()`、`isSuccess()` |

`configure` 修改名称、分类、参考层、条件和费用；源文件、`ignore_air` 必须在数据包中修改再重载。规则函数可以直接作为 Java SAM 传入，返回 `''` 或失败文字。费用接口有多个方法，建议使用 Java 适配类，或通过 `JavaAdapter` 实现完整接口及幂等逻辑。

`beforeDeploy` 在玩家第二次确认时执行，随后仍会完整验证和预留费用。`completed` 发生在事务结算结束后；异常会记录到日志，不再撤销已经完成的世界事务。崩溃瞬间可能没有向脚本投递完成通知，脚本外部副作用不能依赖该通知实现“恰好一次”。

`PrefabAPI.grantPlayer(player, flag)`、`revokePlayer`、`grantTeam`、`revokeTeam` 修改持久化解锁标记。团队方法依赖 FTB Teams。建议始终使用上述显式 getter；Rhino 对 Java record 同名方法的属性映射可能返回函数。

## 已验证的可选版本

| 联动 | 测试版本 | 验证 |
| --- | --- | --- |
| KubeJS | 2101.7.2-build.368 | 注册规则、配置建筑、建造否决、完成事件 |
| Rhino | 2101.2.7-build.81 | Java SAM 规则调用 |
| Architectury | 13.0.8 | FTB 依赖 |
| FTB Library | 2101.1.36 | FTB 依赖 |
| FTB Teams | 2101.1.11 | 玩家团队及团队标记 |
| FTB Quests | 2101.1.36 | 真实任务完成状态与方块实体 NBT |
| FTB Chunks | 2101.1.22 | 他人领地导致整栋拒绝 |
| ViScriptShop | 1.2.2.4 | 原生余额、混合扣款、磁盘重载及重复退款/结算 |
| LDLib2 | 2.2.41 | ViScriptShop 运行依赖 |

其他版本可能改变 API；桥接调用失败时关闭受影响的建筑并报告原因，不静默绕过规则或费用。

## 0.1.2 目录与访问

Java 扩展接口签名保持不变。`catalog_start` 增加 `local_import` 布尔字段。目录条目增加 `category_display`，使用 `UiText` 表示分类文字。原 `category` 字符串保留。

`prefabdeploy_local` 是本地建筑的保留命名空间。集成服务器只向主机开放目录、预览和选择。局域网加入者及专用服务器玩家不能访问本地 ID。网络消息不接受本地路径或文件上传。

本地扫描在后台线程执行。目录合并和 KubeJS `registry` 在服务器线程执行。文件变化或数据包重载触发目录重建。活动会话和任务保持原快照。新增界面文字和错误使用中英文翻译键。

费用说明见[COSTS.md](COSTS.md)。面向整合包作者的脚本见[KUBEJS.md](KUBEJS.md)。指导 AI 创建建筑数据包和魔改的流程见[AGENTS.md](../AGENTS.md)。
