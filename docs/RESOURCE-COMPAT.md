# 容器绑定与 BeLoong 1.4 资源兼容

版本基准为 [BeLoong 官方快照 7f95d495](https://github.com/PorkChop-ZLG/BeLoong/tree/7f95d49553a38ba29a8b497e9e6f12daf4280178)。测试使用 Minecraft 1.21.1、Java 21 和 NeoForge 21.1.248。

下载 [Prefab Deploy 0.1.3](https://github.com/wuhanhao456/prefabdeploy/releases/download/v0.1.3/prefabdeploy-0.1.3.zip)。该版本包含工具容器绑定、随身背包和超越维度资源来源。

| 模组 | 基准 JAR 版本 | JAR 内声明版本 |
| --- | --- | --- |
| 超越维度 Beyond Dimensions | 0.7.30 | 0.7.30 |
| 精妙背包 Sophisticated Backpacks | 3.26.3.2158 | 3.26.3 |
| 精妙核心 Sophisticated Core | 1.5.1.2341 | 1.5.1 |
| Curios | 9.5.1 | 9.5.1+1.21.1 |

将 ZIP 内的 JAR 放入客户端和服务器的 `mods/`。同一实例只保留一个 Prefab Deploy JAR。兼容模组均为可选依赖。

绑定容器功能自 0.1.3 提供。工具绑定来源优先使用；绑定来源与库存足够支付时，不调用背包和超越维度。免费任务和创造任务不调用资源来源。背包和超越维度适配器仍检查表中声明版本。其他构建未验证。安装包不包含第三方 JAR。

## 工具绑定容器

空闲时持建筑建造工具，对容器 `Shift+右键` 绑定。灰色物品提示显示操作说明和绑定坐标、维度。绑定保存在每把工具中，保存世界、移动工具和重新登录后仍保留。空闲时对空气 `Shift+右键` 清除绑定。定位期间同一操作仍用于取消定位，不清除绑定。无效目标保留原绑定。

| 来源 | 本次验证版本 | 使用范围 |
| --- | --- | --- |
| 原版箱子、木桶 | Minecraft 1.21.1 | 直接库存；双箱读取两半 |
| 精妙储存 Sophisticated Storage | 1.6.1.2147，声明版本 1.6.1 | 对应点击面的物品能力；排除无限和不可访问槽位 |
| 精妙储存测试依赖 Sophisticated Core | 1.5.5.2363，声明版本 1.5.5 | 用于上述独立储存测试实例 |
| Applied Energistics 2 | 19.2.18 | 所点击终端或接口连接的整个 ME 网络，只提取现存物品 |
| AE2 测试依赖 GuideME | 21.1.19 | AE2 的必要运行依赖 |

两组测试实例分别使用表中的原始 JAR。旧精妙背包 3.26.3 不能与新核心 1.5.5 混装；本次没有扩大随身背包适配器的版本范围。

其他容器通过 NeoForge `Capabilities.ItemHandler.BLOCK` 的点击面能力读取，须提供可模拟、可实际提取的库存。精妙储存的直接库存使用本地保存；链接库存按外部来源处理。其他容器、升级和附属模组需单独验证。

AE2 使用真实玩家动作来源及 `StorageHelper.poweredExtraction/poweredInsert`，遵循网络供电、频道和提取结果。绑定记录所点击的终端部件方向。不会从接口本地缓冲区扣费，也不会发起自动合成。AE2 适配器目前仅接受 19.2.18，其他版本提示不支持并回退。

绑定需要交互距离内的真实命中、同维度、已加载来源、原版锁与出生点权限、FTB Chunks 编辑权限和建造区域锁检查。扣费时再次检查权限与来源 UUID。建造时可以远程使用同维度中已加载的来源，不会强制加载。来源缺失、未加载、断电、缺频道或权限不足时，提示原因并由后续来源补足。

定位选择固定工具绑定，费用预留固定来源与实际物品组件。之后换工具或解绑不会改动活动任务。会被本次建造覆盖的容器或 ME 网络节点跳过扣取，避免回滚恢复库存后重复返还。

## 扣取规则

已有建筑 `cost` JSON 无需修改。物品按以下顺序扣取，数量可以跨来源合并：

1. 建筑建造工具绑定的容器或整个 AE2 ME 网络。
2. 玩家库存。
3. 随身精妙背包。
4. 当前绑定的超越维度主网络。

背包依次读取主物品栏、胸甲和副手。Curios 背包最后读取，按槽位名称和索引排列。

适配器通过官方包装器读取顶层背包的直接库存。普通背包按内容 UUID 去重。链接背包按共享存储组 UUID 去重。Curios 只读取已启用的装备槽。装饰槽、嵌套背包、升级内部库存、无限槽位和不可访问槽位不参与扣取。

网络来源只使用玩家当前主网络。扣费前确认玩家仍是所有者、管理员或成员。模拟提取和实际提取使用真实资源键。扣取与返还保留物品组件。网络数量使用 `long`。

自动材料中的水免费。自动岩浆先按上述来源顺序消耗岩浆桶。每个桶支付一格岩浆。剩余格数从网络扣取每格 1000 mB 岩浆。999 mB 不能支付一格，1000 mB 可以。

流体返还保留原资源键和组件。精妙背包储罐不参与扣取。手动 `items` 配置的水桶和岩浆桶仍按物品收费。

XP、ViScriptShop 原生货币和自定义费用继续使用现有协议。创造豁免和位置保护保持原规则。旧报价和旧事务保留原费用与恢复规则。升级不会对已有任务重新计价。

## 事务文件

扣费前重新检查资源，并固定分配方案。预留按以下顺序保存：

1. 原子保存玩家自身的物品、XP、货币扣费结果和外部来源意图。
2. 依次扣取外部来源，并保存各来源的预留结果。
3. 保存玩家的预留确认 `resourcesReady`。

全部预留成功后才开始施工。

| 来源 | 世界根目录下的文件 | 保存身份 |
| --- | --- | --- |
| 玩家库存 | `playerdata/<玩家UUID>.dat` | 事务 UUID |
| 普通背包 | `data/sophisticatedbackpacks.dat` | 事务 UUID + 内容 UUID |
| 链接背包 | `data/sophisticatedcore_linked_storage_groups.dat` | 事务 UUID + 共享组 UUID |
| 超越维度 | `data/BDNet_<网络ID>.dat` | 事务 UUID + 网络 ID |
| 原版容器、精妙储存直接库存 | 来源维度的 `region/` 区块文件 | 事务 UUID + 容器 UUID + 位置和点击面 |
| AE2、其他外部容器 | `prefabdeploy/bound-resources/<来源身份哈希>.journal` | 事务 UUID + 原端点身份；AE2 另含原网络节点指纹 |

外部文件的 `data.prefabdeploy_resource_receipts` 保存所有者、请求、状态和实际扣取资源。资源变化与凭据写入同一个压缩 NBT 文件。文件强制刷新后执行原子替换。可选 Mixin 同步保存这些 SavedData，防止异步保存的旧快照覆盖新扣费。普通背包内容改变后，同一内容 UUID 的现存包装器会刷新。

本地容器使用 `prefabdeploy:container_receipts` 区块附件，将库存和凭据作为同一个区块快照刷新到磁盘。双箱两半分别保存，不跨区块伪造一次原子写入。AE2 和其他外部能力没有通用原子保存接口，先写 `UNCERTAIN` 预写日志，再调用真实提取或插入。已知结果固定实际资源，并刷新世界区块与 SavedData 后保存最终凭据。第三方数据若保存在自身数据库或世界保存之外，仍需对应模组提供原子事务支持。

第三方来源在冷启动时将未完成凭据转为 `UNCERTAIN`，保留实际资源和原来源供管理员核对，不自动重复扣取或返还。原端点消失或 AE2 网络节点指纹改变时，旧资源返还等待原来源恢复。已经完成的结算与返还保持幂等。

结算和资源返还使用事务 UUID 作为幂等键。失败时，资源退回凭据记录的原来源。移动背包、切换网络或撤销扣费权限不会改变返还目标。

原存储容量不足时，剩余资源保存在 `REFUND_PENDING` 凭据中。来源缺失、文件不一致、版本不支持或 API 结果不确定时，任务保留凭据并等待恢复。恢复流程不通过余额变化猜测扣费结果。管理员操作见[恢复说明](ADMIN.md#建造事务和恢复)。

## 验证与复现

验证记录见[资源兼容验证](TESTING-RESOURCES.md)。原始 JAR 的 SHA-256 保存在测试结果目录。准备一个仅包含上述四个原始 JAR 的目录，然后运行：

```powershell
.\Test-Resources.ps1 -CacheRoot C:/pdr -ResourceModsDir C:/mods/beloong-resources -Crash
.\Test.ps1 -CacheRoot C:/pdb -ReportDir docs/test-results/resources-base -Crash
.\Build.ps1 -CacheRoot C:/pdb -Task @('jar', 'sourcesJar')
```

测试使用独立世界。缓存路径须保持简短，以满足 Windows Java 本地套接字的路径长度限制。`Test-Resources.ps1` 检查 GameTest 成功标记。空测试运行不算成功。

崩溃测试在指定位置执行 JVM `halt(91)`。随后用同一世界冷启动。测试核对每个原来源的资源，并重复执行恢复。

本次测试使用服务器和 FakePlayer。整套 BeLoong 的客户端操作尚未验证。其他机器、升级和存储附属模组也须另行验证。

容器绑定更新的测试、客户端提示截图和独立 JVM 冷启动记录见[容器绑定验证](TESTING-CONTAINER-BINDING.md)。复现绑定的强制退出测试：

```powershell
.\Test-BoundContainers.ps1 -CacheRoot C:/pdbc -Kind container
.\Test-BoundContainers.ps1 -CacheRoot C:/pdae -Kind ae2 -ResourceModsDir C:/mods/ae2-storage
.\Build.ps1 -CacheRoot C:/pdb -Task runGameTestServer -ResourceSmoke -ResourceModsDir C:/mods/ae2-storage -TestRun C:/pdb/container-run
```
