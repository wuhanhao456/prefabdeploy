PrefabEvents.registry(event => {
  console.info('PREFAB_KUBE_REGISTRY_OK');
  event.registerRule('test:allowed', (player, prefab, anchor) => '');
  event.configure('prefabdeploy:cottage', JSON.stringify({unlock: {type: 'script', id: 'test:allowed'}}));
});
PrefabEvents.beforeDeploy(event => {
  if (event.getPrefabId() === 'prefabdeploy:test_kube_deny') event.deny('KubeJS test denied');
});
PrefabEvents.completed(event => console.info('PREFAB_KUBE_COMPLETED ' + event.getTransaction()));
