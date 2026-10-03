# 费用自定义

每个数据包建筑都必须配置 `cost`。免费建筑也要写出模式。本地蓝图默认使用 `auto`。

## JSON 结构

```json
{
  "cost": {
    "mode": "combined",
    "items": [{"id": "minecraft:emerald", "count": 2}],
    "xp": 20,
    "viscriptshop": 12.50,
    "custom": [{"provider": "yourmod:points", "amount": 50}]
  }
}
```

| 字段 | 含义 |
| --- | --- |
| `mode` | 计费模式，见下表 |
| `items` | 物品 ID 和正整数数量 |
| `xp` | 经验点数，不是等级 |
| `viscriptshop` | ViScriptShop 原生玩家余额，金额非负，最多两位小数 |
| `custom` | 自定义费用数组；每项必须指定 `provider`，其余字段由提供者解释 |

| 模式 | 收费内容 |
| --- | --- |
| `free` | 免费，忽略其他费用项 |
| `manual` | 仅显式配置的费用 |
| `auto` | 自动建筑材料，加上显式配置的费用 |
| `combined` | 自动建筑材料与手动费用组合；收费行为与 `auto` 相同 |

## 自动材料

自动材料只计算建筑方块。门和双层植物只收下半部分。床只收脚端。双层台阶收两块。水和岩浆按桶计价。红石线按红石粉计价。

活塞头、移动活塞、气泡柱和火焰不收独立材料。蓝图库存和实体装备不额外收费。无法推导物品的模组方块会拒绝自动报价。此时使用手动定价。

实际扣除的物品保留其数据组件。退款返还相同组件的物品。创造模式在服务器报价时免除所有费用。核心不调用创造任务的自定义费用回调。创造模式仍受解锁、维度和位置权限限制。

## 自定义提供者

实现 `io.github.prefabdeploy.api.CostProvider`。将实例注册到 `PrefabApi.COSTS`，或在 KubeJS 注册事件中调用 `event.registerCost(id, provider)`。

```java
public interface CostProvider {
    boolean atomicWithPlayerSave();
    CompoundTag quote(ServerPlayer player, JsonObject specification);
    void reserve(ServerPlayer player, UUID transaction, CompoundTag quote);
    void commit(ServerPlayer player, UUID transaction, CompoundTag quote);
    void refund(ServerPlayer player, UUID transaction, CompoundTag quote);
}
```

`commit` 有默认空实现。`quote` 返回费用快照。`reserve` 预留费用。`commit` 确认收费。`refund` 返还费用。使用事务 UUID 作为幂等键。

当前协议要求 `atomicWithPlayerSave()` 返回 `true`。扣款和凭据必须全部位于玩家自己的 NBT 中。核心负责原子保存。回调不要自行异步保存玩家，也不要修改外部数据库余额。

同一事务的回调可能重复执行。`reserve` 抛错后仍可能调用 `refund`。退款必须先检查是否已经扣款。结果不确定时抛出异常，并保留凭据。

费用提供者要保存报价版本。重启后必须识别已有报价。无法识别时拒绝处理，不重新计价。运行中的任务固定原提供者实例和原报价。

ViScriptShop 仅支持原生玩家附件余额。替代货币后端需要单独的可恢复适配器。管理员可用 `/prefab receipt` 和 `/prefab reconcile_currency` 核对不确定交易，见[管理员说明](ADMIN.md)。

KubeJS 修改现有建筑费用的方法见[KubeJS 指南](KUBEJS.md)。接口细节见[Java API](API.md)。
