# KubeJS 指南

将脚本放入实例的 `kubejs/server_scripts/`。需要 KubeJS 1.21.1。示例使用服务器事件。

## 配置建筑

先制作[建筑数据包](DATAPACKS.md)。下面的脚本修改 `yourpack:house`。将 ID 换成你的建筑 ID。

```js
PrefabEvents.registry(event => {
  const id = 'yourpack:house'
  if (!event.getIds().contains(id)) return
  event.configure(id, JSON.stringify({
    name: '住宅',
    category: '住宅',
    ground_y: 2,
    conditions: {type: 'dimension', id: 'minecraft:overworld'},
    cost: {mode: 'manual', items: [{id: 'minecraft:emerald', count: 2}], xp: 20}
  }))
})
```

`configure` 可修改名称、分类、参考层、条件和费用。源文件和 `ignore_air` 必须在数据包中修改，然后重载。本地蓝图参考层默认为 0。需要地下结构时，也可通过 `configure` 修改 `ground_y`。

本地建筑 ID 在 GUI 条目提示中显示。ID 来自相对文件路径。修改内容不改变 ID。移动或改名会改变 ID。局域网加入者不能访问这些 ID。

## 注册规则

```js
PrefabEvents.registry(event => {
  event.registerRule('yourpack:not_in_combat', (player, prefab, anchor) => {
    return player.getLastHurtByMob() == null ? '' : '请在脱离战斗后建造'
  })
  const id = 'yourpack:house'
  if (event.getIds().contains(id)) {
    event.configure(id, JSON.stringify({
      unlock: {type: 'script', id: 'yourpack:not_in_combat'},
      requirements_text: '脱离战斗'
    }))
  }
})
```

规则返回空字符串表示通过。其他字符串作为失败原因显示。脚本提供的文本按原文显示。规则不能绕过位置保护。

<a id="部署事件"></a>

## 建造事件

```js
PrefabEvents.beforeDeploy(event => {
  if (event.getPlayer().getY() < -60) event.deny('当前位置过低')
})

PrefabEvents.completed(event => {
  console.info('Prefab ' + event.getPrefabId()
    + ' transaction=' + event.getTransaction()
    + ' success=' + event.isSuccess())
})
```

`beforeDeploy` 在第二次确认时执行。核心随后仍会检查位置和预留费用。此事件只检查规则，不要另行扣费。

`completed` 在事务结算后执行。它不会撤销已完成的世界修改。崩溃时可能没有完成通知。外部奖励不能仅凭此事件保证只发一次。

## 解锁标记

```js
PrefabAPI.grantPlayer(player, 'housing_tier_2')
PrefabAPI.revokePlayer(player, 'housing_tier_2')
PrefabAPI.grantTeam(player, 'housing_tier_2')
PrefabAPI.revokeTeam(player, 'housing_tier_2')
```

团队方法需要 FTB Teams。建筑规则使用 `flag`，例如：

```json
{"type":"flag","scope":"player","id":"housing_tier_2"}
```

这些调用需要真实的服务器玩家对象。将它们放入提供玩家对象的事件处理函数。不要直接把含有未定义 `player` 的调用放在脚本文件顶层。

`player` 标记属于个人。`team` 标记属于调用时的 FTB 团队。两种标记使用不同的所有者。授予方式必须与建筑规则中的 `scope` 一致。标记不会自动同步 GameStages 或商店购买记录。

## 购买建筑许可

此示例在 ViScriptShop 购买成功后授予个人许可。需要 ViScriptShop 和 KubeJS。事件与 getter 按 ViScriptShop 1.2.2.4 核对。

1. 在 ViScriptShop 中创建单独的许可商店。
2. 将商店名称设置为 `housing_permit`。这是商店配置的名称，不是商品名或文件名。
3. 商店只保留一个住宅许可交易，例如收取 50 商店货币并交付一张纸。
4. 在建筑 JSON 中合并下面的片段。

```json
{
  "unlock": {"type": "flag", "scope": "player", "id": "housing_tier_2"},
  "requirements_text": "购买二级住宅许可。",
  "cost": {"mode": "auto"}
}
```

在 `kubejs/server_scripts/housing_permit.js` 中写入：

```js
ViScriptShopEvents.buySuccess(event => {
  const shopName = String(event.getShopInfo().getName())
  if (shopName !== 'housing_permit') return
  PrefabAPI.grantPlayer(event.getPlayer(), 'housing_tier_2')
})
```

ViScriptShop 已处理购买付款。此事件只授予标记，不再扣款。建筑使用 `auto`，因此每次建造仍收取建筑材料。需要许可后的建造免费时，将建筑费用改为 `{"mode":"free"}`。

此事件匹配整个商店。ViScriptShop 1.2.2.4 的 `BuySuccess` 脚本对象只提供玩家和商店信息，没有商品 ID getter。不要在 `housing_permit` 中加入普通商品，否则购买这些商品也会授予许可。接口来源见 [事件注册](https://github.com/zhenshiz/ViScriptShop/blob/master/src/main/java/com/viscriptshop/event/ViScriptShopEventsJS.java)和[事件参数](https://github.com/zhenshiz/ViScriptShop/blob/master/src/main/java/com/viscriptshop/event/kubejs/ShopServerEventJS.java)。

许可保存为标记。丢弃示例中的纸不会取消许可。需要撤销时，在提供玩家对象的脚本中调用 `PrefabAPI.revokePlayer(player, 'housing_tier_2')`。

需要团队许可时，将建筑的 `scope` 改为 `team`，并将事件中的 `grantPlayer` 改为 `grantTeam`。此时还需 FTB Teams。许可授予购买者当时所在的团队。

修改脚本后，重载 KubeJS 服务器脚本或重启服务器。随后执行 `/prefab reload`。用生存模式检查以下结果：购买失败不授予标记；购买成功后重新打开建筑库可见解锁；建造时按建筑 `cost` 收费。还需确认其他商店的购买不会解锁该建筑。

FTB 任务直接解锁和任务货币奖励的配置见 [FTB 联动](DATAPACKS.md#ftb-联动)与[组合示例](DATAPACKS.md#ftb-任务与商店货币组合示例)。

## 自定义费用

`event.registerCost(id, provider)` 接收完整的 Java `CostProvider` 实例。这个接口包含多个方法，不能只传一个箭头函数。可以使用 Java 类或 Rhino `JavaAdapter`。实现必须满足幂等和玩家 NBT 原子保存要求，见[费用自定义](COSTS.md)。

重载时，核心先合并数据包和本地定义，再调用 `registry`。只在输入文件变化或资源重载时重建目录。不要依赖 GUI 每次打开都触发脚本。

事件和接口的完整说明见[Java API](API.md)。可修改的脚本位于 `examples/kubejs/server_scripts/prefab_rules.js`。
