# Prefab Deploy agent guide

本项目为 Minecraft 1.21.1 / NeoForge Mod。Java 版本为 21。修改前读取 README 和当前任务涉及的文档。保持技术术语一致。

## 入口与文档

- `PrefabDeploy` 注册内容、生命周期和命令。`Network` 定义消息入口。`Sessions` 管理玩家定位和预览请求。
- `PrefabLibrary` 读取数据包并发布目录。`LocalBlueprints` 读取本地文件。二者共用蓝图解码与校验。
- `Client` 管理预览与输入。`LibraryScreen` 管理建筑库。`PreviewTarget` 使用渲染坐标计算预览外框命中。
- `DeploymentManager` 管理验证、施工与恢复。`Costs` 管理费用。`NbtTransforms` 管理 NBT 坐标。
- [数据包定义](docs/DATAPACKS.md)、[费用协议](docs/COSTS.md)、[KubeJS 用法](docs/KUBEJS.md)、[Java API](docs/API.md)、[管理员恢复](docs/ADMIN.md)。

## 扩展接口

`PrefabApi.IMPORTERS` 注册 `BlueprintImporter`。`accepts(extension)` 选择格式。`read(CompoundTag, positionLimit)` 返回 `Blueprint`。数据包可使用注册格式。本地文件夹入口只读取内置的 `.nbt` 和 `.litematic`。

`PrefabApi.RULES` 注册 `PlacementRule`。空字符串表示通过。其他字符串表示失败原因。脚本规则不能绕过位置权限。

`PrefabApi.COSTS` 注册 `CostProvider`。报价固定费用。事务 UUID 是幂等键。费用必须与玩家 NBT 原子保存。重启恢复必须识别旧报价。不得在回调中直接扣除外部数据库余额。

`PrefabApi.NBT_ADAPTERS` 注册 `NbtTransformAdapter`。通用坐标和方块朝向已经处理。只转换模组专有坐标、连接和 UUID 引用。`previewData` 只返回渲染所需白名单数据。

## 实现约束

- 文件解析和预览解码在后台线程执行。世界修改、玩家状态修改和目录发布在服务器线程执行。
- 保存明确的空气覆盖语义。未选中的 Litematic 位置不补成空气。保留 DataVersion 和大小限制。
- 本地目录只能由集成服务器主机读取。加入者和专用服务器不能请求本地建筑 ID。目录、预览和选择入口使用同一权限检查。
- 文件变更生成新建筑快照。不要修改运行中任务的蓝图、报价或凭据。资源重载不能替换已固定的交易回调。
- 定位信标的视觉方块实体没有服务器动画。客户端类只在物理客户端加载。保持注册 ID 和旧存档兼容。
- 新玩家文本使用 `UiText` 或翻译键。修改 `tools/localization.py` 后运行它。不要只修改生成的语言 JSON。
- 测试建筑仅在 `src/testFixtures/resources/`。正式 JAR 不含开发测试类或测试建筑。
- 文档使用短句、具体动词和一致术语。每句话表达一个主要意思。不要声称未运行的测试已通过。

## 构建与验证

Windows 使用 PowerShell 7，缓存目录必须在本地磁盘：

```powershell
.\Build.ps1 -CacheRoot C:/work/prefabdeploy-cache -Task @('jar', 'sourcesJar')
.\Test.ps1 -CacheRoot C:/work/prefabdeploy-cache -ReportDir docs/test-results/current -Crash -Client
.\Test.ps1 -CacheRoot C:/work/prefabdeploy-cache -ReportDir docs/test-results/current -Compat -VssJar C:/mods/ViScriptShop.jar
```

联动测试使用 `build.gradle` 固定的版本。测试在独立世界运行。GameTest 和客户端 smoke 会自动装入测试数据包。普通开发客户端不自动装入这些建筑。

Linux/macOS 可用 `./gradlew test runGameTestServer jar sourcesJar`。构建后检查 JAR 资源、许可、版本和测试入口排除情况。打包命令见[验证说明](docs/TESTING-0.1.2.md)。

## 热加载与配方

`PrefabReload` 执行 `/prefab reload`。保留权限等级 2、重复请求限制和可翻译反馈。发现数据包时遵循世界禁用列表和 NeoForge 排序。不要将资源重载用于修改活动任务快照。

两个合成配方位于 `data/prefabdeploy/recipe/`。对应配方书解锁位于 `advancement/recipes/misc/`。修改后用真实配方管理器验证输入和产出。

光影兼容测试用 `Build.ps1` 的 `-ShaderModsDir` 和 `-ShaderSmoke` 参数，在独立目录运行。不要改动作为参考的整合包。
