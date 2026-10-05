# 容器绑定验证

2026 年 10 月 5 日，使用 Minecraft 1.21.1、NeoForge 21.1.248、Java 21.0.11 和 Windows Server 2025 验证。结果位于 [test-results/container-binding](test-results/container-binding/)。下表记录容器功能在 0.1.2 开发构建中的完整验证。该功能以 0.1.3 发布，改版后的测试与构建记录见[0.1.3 验证](TESTING-0.1.3.md)。

| 检查 | 结果与证据 |
| --- | --- |
| 单元测试 | 10 项通过；`base/junit/` |
| 原有基础流程 | 35 个 GameTest 入口通过；4 个可选联动入口因缺少依赖跳过；`base/gametest-base.log` |
| 原有背包和超越维度 | 43 个资源测试入口通过，新增容器与库存、背包、网络的合并扣取及返还实际执行；AE2 与精妙储存入口跳过；`existing-resources/gametest.log` |
| 容器、AE2、精妙储存与领地 | 45 个资源测试入口通过；绑定、优先扣取、原来源返还、实际建造与领地相关用例实际执行；此组合缺少背包和超越维度，相应用例由上一组覆盖；`claims/gametest.log` |
| 工具操作和显示 | 每把工具独立保存、物品序列化、绑定坐标、灰色样式、非法目标保留绑定、空气解绑和活动定位取消通过 |
| 实际建造 | 同一施工锁区块内、施工范围外的材料容器优先扣料；施工范围内的来源被排除，注入失败回滚后无重复返还 |
| 双箱与容量 | 同区块、跨区块两半独立预留与返还；保留物品组件；空间不足保存剩余资源，腾出空间后只返还一次 |
| AE2 网络 | 接口读取整个 ME 网络，终端读取所点击部件，实际消耗能量；离线回退；网络成员变化时保留返还，原网络恢复后返还一次 |
| 权限 | 无强制加载、跨维度回退、建造区域锁回退、FTB 外国领地拒绝新绑定，并重新检查已有绑定 |
| 原版容器冷启动 | 5 个独立 JVM 强制退出阶段通过，见下表和 `crash/container-*.log` |
| AE2 冷启动 | 3 个独立 JVM 强制退出阶段通过，见下表和 `crash/ae2-*.log` |
| 真实客户端 | 完整建筑库、取消、旋转、确认、信标和生存收费流程通过；绑定保留检查通过；`base/client-smoke.log` |
| 物品提示截图 | 已检查中文灰色说明、坐标、维度和解绑提示；`tooltip/screenshots/tool-tooltip-unbound.png`、`tool-tooltip-bound.png` |
| 构建产物 | 正式 JAR 排除测试入口和测试建筑，不包含第三方 JAR；构建日志与 SHA-256 见本次结果目录 |

服务器用例使用 FakePlayer。客户端在 NVIDIA RTX 5060 Ti 上运行，窗口为 1280×800。提示截图通过真实物品提示渲染取得。客户端完整流程向真实输入事件入口发送取消与确认，原版箱子、精妙储存和 AE2 的绑定交互通过真实服务器 `ServerPlayerGameMode.useItemOn` 路径验证，空气解绑通过物品 `use` 路径验证。

## 独立依赖组合

旧来源组合：Beyond Dimensions 0.7.30、Sophisticated Backpacks 3.26.3.2158、Sophisticated Core 1.5.1.2341、Curios 9.5.1。新容器组合：AE2 19.2.18、GuideME 21.1.19、Sophisticated Storage 1.6.1.2147、Sophisticated Core 1.5.5.2363。领地组合另外启用 FTB Chunks 2101.1.22、FTB Teams 2101.1.11 及现有开发联动依赖。

旧背包与新核心混装无法启动，报缺少 `ILinkedStorageContentsBinding`，因此分开验证。原始第三方 JAR 的 SHA-256 记录在 `mods-sha256.txt`，没有修改或重新打包第三方 JAR。

## 强制退出阶段

每个写入进程在指定事务钩子执行 `Runtime.halt(91)`。读取进程重用同一世界，从磁盘加载玩家、区块、凭据和建造日志，并重复结算或返还。

| 来源与阶段 | 冷启动结果 |
| --- | --- |
| 原版容器 `source_before_save` | 未保存的扣取不作为已支付；地形回滚，材料总数恢复 |
| 原版容器 `source_saved` | 从区块内的库存和凭据恢复，材料返还到原容器 |
| 原版容器 `source_refund_before_save` | 磁盘仍保留原预留，冷启动重新返还一次 |
| 原版容器 `source_refund_saved` | 已保存的返还不重复执行 |
| 原版容器 `source_commit_saved` | 已完成建造与扣料保留，重复结算不再次扣料 |
| AE2 `source_before_save` | 第三方调用窗口无原子证据，进入 HELD/UNCERTAIN，重复恢复不再次变更 ME 库存 |
| AE2 `source_saved` | 未完成第三方事务在冷启动时进入 HELD/UNCERTAIN，保留原来源与实际资源供核对 |
| AE2 `source_commit_saved` | 世界和最终凭据已刷新，保留已完成建造与材料扣取 |

## 复现

缓存和测试世界使用简短的本地路径。AE2 与精妙储存目录须包含各自必要依赖。领地检查通过 `-Compat` 加载项目中配置的联动版本。

```powershell
.\Test.ps1 -CacheRoot C:/pdb -ReportDir docs/test-results/container-binding/base -Client
.\Build.ps1 -CacheRoot C:/pdc -Task runGameTestServer -ResourceSmoke -ResourceModsDir C:/mods/old-resources -TestRun C:/pdc/old-run
.\Build.ps1 -CacheRoot C:/pdb -Task runGameTestServer -Compat -ResourceSmoke -ResourceModsDir C:/mods/ae2-storage -TestRun C:/pdb/container-run
.\Test-BoundContainers.ps1 -CacheRoot C:/pdbc -Kind container
.\Test-BoundContainers.ps1 -CacheRoot C:/pdae -Kind ae2 -ResourceModsDir C:/mods/ae2-storage
.\Build.ps1 -CacheRoot C:/pdb -Task @('jar','sourcesJar')
```

单独重现提示截图时，先准备名为 `prefab-smoke` 的测试世界和中文 `options.txt`，再运行 `Build.ps1 -Task runClient -BindingTooltipSmoke -TestRun <实例目录> -ReportDir <输出目录>`。

## 范围

AE2 目前只接受 19.2.18，并提供已存储物品，不提供流体或自动合成。未运行整套 BeLoong，也未遍历所有储存升级、附属模组或能力提供者。非原版能力若在世界保存之外持久化，仍需该模组提供事务支持。第三方未完成事务的冷启动保守等待管理员核对，不能当作自动返还成功。

本地容器支持正常卸载后重新加载，再恢复原来源。测试验证没有强制加载资金来源；冷启动测试为观察原来源，测试代码自行添加区块票据。客户端性能数据记录在 `base/client-benchmark.json`，本次不将其作为大型 ME 网络保存耗时的保证。
