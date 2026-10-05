# Prefab Deploy 0.1.3 验证记录

2026 年 10 月 5 日，使用 Minecraft 1.21.1、NeoForge 21.1.248、Java 21.0.11 和 Windows Server 2025 验证。改版前的容器开发构建记录见[容器绑定验证](TESTING-CONTAINER-BINDING.md)，本次结果保存在 [test-results/release-0.1.3](test-results/release-0.1.3/)。

## 本次检查

| 检查 | 结果与证据 |
| --- | --- |
| 单元测试 | 10 项通过，无跳过；`junit/`、`unit-test.log` |
| 基础回归 | 35 个 GameTest 入口通过；4 个可选联动入口因依赖缺失跳过；`gametest-base.log` |
| 容器、AE2、精妙储存和领地 | 45 个资源 GameTest 入口通过；真实容器绑定、优先扣取、实际建造、跨区块双箱返还和外国领地检查执行；背包与超越维度用例由另一组合覆盖；`containers/gametest.log` |
| 原有资源 | 45 个 GameTest 入口通过；玩家、容器、精妙背包和超越维度的组合支付与原来源返还执行；AE2、精妙储存和 FTB 权限入口在此组合跳过；`old-resources/gametest.log` |
| 真实客户端 | 建筑库、旋转、取消、确认、三点信标、生存收费、模型、绑定提示和取消保留绑定通过；`client-smoke.log`、`client-benchmark.json`、`screenshots/` |
| 构建产物 | JAR 内版本为 0.1.3，测试类与测试蓝图未打包，无第三方 JAR；`build.log`、`acceptance.json` |
| 版本改动核对 | 对比改版前 JAR，运行代码和资源内容相同，仅版本元数据变化；`runtime-equivalence.json` |

服务端测试使用 FakePlayer；绑定交互调用真实 `ServerPlayerGameMode.useItemOn`。客户端在 NVIDIA RTX 5060 Ti 上运行，1280×800 窗口内通过真实输入事件检查确认和取消。灰色物品提示使用改版前已核对的截图证据，见 `test-results/container-binding/tooltip/screenshots/`。本次完整客户端运行的两张物品提示截图未捕获悬浮提示，不作为显示证据；操作与样式由服务端用例和此前独立截图验证。

## 保留的验证与范围

改版前已完成原版容器 5 组和 AE2 3 组独立 JVM 强制退出验证，日志保留在 `test-results/container-binding/crash/`。这些验证针对与本次正式 JAR 相同的运行实现，不作为 0.1.3 新进程测试计数。AE2 未完成事务遇到冷启动时进入 `HELD/UNCERTAIN`，不能当作自动返还成功。

本次没有重跑旧版本的光影组合、客户端可选安装远程连接和所有历史崩溃点，也没有运行整套 BeLoong 或遍历储存升级、附属模组及所有能力提供者。此前记录继续保留各自版本。来源须同维度且已加载；代码不强制加载来源。

新容器组合使用 AE2 19.2.18、GuideME 21.1.19、Sophisticated Storage 1.6.1.2147、Sophisticated Core 1.5.5.2363，以及项目配置的 FTB 和脚本联动。旧资源组合使用 Beyond Dimensions 0.7.30、Sophisticated Backpacks 3.26.3.2158、Sophisticated Core 1.5.1.2341 和 Curios 9.5.1。旧背包不能与新核心混装，分别验证。第三方 JAR 校验值见 `test-results/container-binding/mods-sha256.txt`。

## 复现

源码从 `v0.1.3` 检出。缓存和测试世界放在本地磁盘，避免 UNC 路径影响 Gradle 与游戏运行。

```powershell
.\Test.ps1 -CacheRoot C:/pdb -ReportDir docs/test-results/release-0.1.3 -Client
.\Build.ps1 -CacheRoot C:/pdc -Task runGameTestServer -Compat -ResourceSmoke -ResourceModsDir C:/mods/ae2-storage -TestRun C:/pdc/containers
.\Build.ps1 -CacheRoot C:/pdc -Task runGameTestServer -ResourceSmoke -ResourceModsDir C:/mods/old-resources -TestRun C:/pdc/old-resources
.\Test-BoundContainers.ps1 -CacheRoot C:/pdbc -Kind container
.\Test-BoundContainers.ps1 -CacheRoot C:/pdae -Kind ae2 -ResourceModsDir C:/mods/ae2-storage
.\Build.ps1 -CacheRoot C:/pdb -Task @('jar','sourcesJar')
```

`Build.ps1` 返回 Gradle 日志路径。资源专项运行后，将该日志复制到对应报告目录作为证据。安装包内的 `verification.json` 保留发布前验收快照；发布后的附件大小、SHA-256 和重新下载检查记录在 `publication.json`。
