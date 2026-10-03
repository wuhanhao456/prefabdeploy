# Prefab Deploy 0.1.2 验证记录

测试日期为 2026 年 10 月 4 日。使用 Minecraft 1.21.1、NeoForge 21.1.248、Java 21.0.11 和 Windows Server 2025。客户端使用 NVIDIA RTX 5060 Ti，窗口为 1280×800。完整日志、JSON 和游戏截图位于 [test-results/release-0.1.2](test-results/release-0.1.2/)。

## 功能结果

| 检查 | 结果与证据 |
| --- | --- |
| 单元测试 | 10 项通过；`junit/` |
| 基础 GameTest | 35 个入口通过；31 项实际执行，4 项因未安装可选依赖而跳过；`gametest-base.log` |
| 联动 GameTest | 35 项全部执行并通过；`gametest-compat.log` |
| 热加载指令 | 新数据包发现、定义修改、定义删除、损坏源隔离和活动定位快照通过；`reload/reload-command.log` |
| 合成配方 | 用真实配方管理器核对两种输入与产出；无管理员权限的指令请求被拒绝 |
| 本地文件 | NBT、Litematic、中文路径、子目录、同名文件、修改、删除、损坏文件和大小限制通过 |
| 本地权限 | 集成服务器主机可导入；LAN 发布后主机权限保持；模拟加入者和专用服务器的伪造本地 ID 被拒绝 |
| 右键操作 | 四方向与地下参考层的外框射线通过；客户端确认、外框外取消、容器和实体输入拦截通过 |
| 加载与验证 | BSL 客户端检查确认加载中的预览不会固定位置；验证中可取消 |
| 三点定位 | A/B/C 浮空定位、两次确认、材料收费和信标返还通过 |
| 模型与动画 | 捕获左右惯用手、双手、副手盾牌、背包、第三人称及信标两帧截图；信标渲染器和粒子计数通过 |
| 旧信标 | 缺少方块实体的信标在区块加载时补建；保存后重新启动客户端仍可渲染；`model-reload.json` |
| 崩溃恢复 | BLOCKS、TICKS、COMMIT 三阶段独立 JVM 强制退出后冷启动恢复通过 |
| 正式资源 | 打包脚本检查版本、许可、模型、动画、配方和测试内容排除 |

联动套件使用 KubeJS 2101.7.2-build.368、Rhino 2101.2.7-build.81、Architectury 13.0.8、FTB Library 2101.1.36、Teams 2101.1.11、Chunks 2101.1.22、Quests 2101.1.36、ViScriptShop 1.2.2.4 和 LDLib2 2.2.41。联动依赖不进入正式 JAR。

## 光影结果

从化龙整合包 `Y:\折腾\beloong-dlc\BeLoong\mods` 复制原始 Iris 和 Sodium JAR 到独立测试实例。测试没有修改整合包。

使用 Iris `1.8.14-beta.1+mc1.21.1` 和 Sodium `0.8.13+mc1.21.1`。

| 光影配置 | 结果与报告 |
| --- | --- |
| 关闭光影 | 客户端流程通过；`shader-off/` |
| Complementary Reimagined r5.9 | Iris 确认光影启用，预览、部署、模型、核心和粒子通过；`shader-complementary/` |
| BSL v10.1.1 | Iris 确认光影启用，预览、部署、模型、核心和粒子通过；`shader-bsl/` |

已检查真实游戏截图。GUI、预览外框、透明城堡和信标核心可见。信标核心在两帧中发生旋转。光影会改变亮度和泛光。测试使用默认光影选项。

最初将 Sodium 作为开发运行时依赖时，NeoForge 选择了外层 JAR，导致缺少嵌套实现类。将原始 JAR 放入实例 `mods/` 后启动正常。`-ShaderModsDir` 现在执行同样的复制流程。测试没有发布重打包的第三方 JAR。

这些结果覆盖上述渲染组合。未装入整套化龙整合包，也未测试其全部光影包和附属 Mod。

## 性能

基础客户端在缓存预览和真实施工中测量 p95。GUI 与世界预览分别记录 CPU 提交时间和 GPU timestamp 时间。服务器列记录部署调度时间。

| 位置数 | 服务器 ms | GUI CPU ms | GUI GPU ms | 世界 CPU ms | 世界 GPU ms |
| --- | --- | --- | --- | --- | --- |
| 10,000 | 2.263 | 0.223 | 0.027 | 0.177 | 0.023 |
| 50,000 | 2.010 | 0.572 | 0.060 | 0.450 | 0.057 |
| 100,000 | 1.669 | 0.824 | 0.116 | 0.423 | 0.107 |

三个场景均完成部署和最终邻居更新。每 Tick 操作次数未超过 512。服务器 p95 低于 5 ms。热预览的 CPU 和 GPU p95 分别低于 3 ms。2 ms 调度预算是软预算。此表不表示整个游戏帧耗时，也不表示启用光影后的性能。

## 复现

Windows 使用 PowerShell 7。缓存和测试世界放在本地磁盘。

```powershell
.\Test.ps1 -CacheRoot C:/work/prefabdeploy-test -ReportDir docs/test-results/current -Crash -Client
.\Test.ps1 -CacheRoot C:/work/prefabdeploy-compat -ReportDir docs/test-results/current -Compat -VssJar C:/mods/ViScriptShop.jar
.\Build.ps1 -CacheRoot C:/work/prefabdeploy-reload -Task runGameTestServer -ReloadSmoke -TestRun C:/work/prefabdeploy-reload/run
```

重载测试使用独立世界。客户端测试自动装入开发数据包。保存后的信标检查必须重用已完成客户端测试的实例目录：

```powershell
.\Build.ps1 -CacheRoot C:/work/prefabdeploy-test -Task runClient -ClientSmoke -ModelReloadSmoke -TestRun C:/work/finished-client-run
```

光影测试先准备 `prefab-smoke` 世界和 `options.txt`。将两个原始图形 Mod 放入单独目录。将光影 ZIP 放入测试实例的 `shaderpacks/`。在 `config/iris.properties` 设置 `enableShaders=true` 和 `shaderPack=BSL_v10.1.1.zip`。

```powershell
.\Build.ps1 -CacheRoot C:/work/prefabdeploy-shader -Task runClient -ClientSmoke -ShaderModsDir C:/work/graphics-mods -ShaderSmoke BSL_v10.1.1.zip -TestRun C:/work/shader-instance -ReportDir docs/test-results/current/shader-bsl
```

正式构建与打包：

```powershell
.\Build.ps1 -CacheRoot C:/work/prefabdeploy-build -Task @('jar', 'sourcesJar')
python tools/package_examples.py
python tools/package_release.py --build-dir C:/work/prefabdeploy-build/build --runtime-reports docs/test-results/release-0.1.2 --build-log C:/work/prefabdeploy-build/logs/gradle-build.log
```

将最后一个参数换成构建脚本返回的实际日志路径。打包脚本拒绝缺失的验证证据。它生成源码 ZIP 和 `SHA256SUMS.txt`。干净检出构建的记录见[交付记录](RELEASE-0.1.2.md)。

## 测试范围

LAN 权限测试使用真实集成服务器和模拟加入者。它没有启动第二个远程客户端。输入回归将空气、方块和实体命中送入真实客户端事件入口。它没有人工点击每一种容器。

已检查中文 GUI 在 640×400 和 427×267 布局下的截图。中英文翻译键由生成脚本核对。没有遍历所有窗口尺寸和资源包。复杂机器仍需要专用 NBT 适配器。
