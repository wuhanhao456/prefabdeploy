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
    return player.getLastHurtByMob() == null ? '' : '请在脱离战斗后部署'
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

## 部署事件

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

## 自定义费用

`event.registerCost(id, provider)` 接收完整的 Java `CostProvider` 实例。这个接口包含多个方法，不能只传一个箭头函数。可以使用 Java 类或 Rhino `JavaAdapter`。实现必须满足幂等和玩家 NBT 原子保存要求，见[费用自定义](COSTS.md)。

重载时，核心先合并数据包和本地定义，再调用 `registry`。只在输入文件变化或资源重载时重建目录。不要依赖 GUI 每次打开都触发脚本。

事件和接口的完整说明见[Java API](API.md)。可修改的脚本位于 `examples/kubejs/server_scripts/prefab_rules.js`。
