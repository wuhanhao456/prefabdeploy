// Copy into kubejs/server_scripts. Requires Prefab Deploy and KubeJS 1.21.1.
// Replace yourpack:house with your datapack building ID.
PrefabEvents.registry(event => {
  event.registerRule('example:not_in_combat', (player, prefab, anchor) => {
    return player.getLastHurtByMob() == null ? '' : '请在脱离战斗后建造';
  });
  if (!event.getIds().contains('yourpack:house')) return;
  event.configure('yourpack:house', JSON.stringify({
    unlock: { type: 'script', id: 'example:not_in_combat' },
    conditions: { type: 'dimension', id: 'minecraft:overworld' },
    requirements_text: '脱离战斗；仅限主世界'
  }));
});

PrefabEvents.beforeDeploy(event => {
  if (event.getPlayer().getY() < -60) event.deny('此示例禁止在过低位置开始建造');
});

PrefabEvents.completed(event => {
  console.info('Prefab ' + event.getPrefabId() + ' transaction ' + event.getTransaction()
    + ' success=' + event.isSuccess());
});

// To grant a durable unlock from another server event:
// PrefabAPI.grantPlayer(player, 'housing_tier_2');
// PrefabAPI.grantTeam(player, 'housing_tier_2'); // requires FTB Teams
