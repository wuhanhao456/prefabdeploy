# Prefab Deploy 0.1.4 验证记录

2026 年 10 月 10 日，使用 Minecraft 1.21.1、NeoForge 21.1.248、Java 21.0.11 和 Windows Server 2025 验证。缓存与测试世界位于本地磁盘，所有运行使用独立测试世界。证据保存在 [test-results/release-0.1.4](test-results/release-0.1.4/)。

## 本次检查

| 检查 | 结果与证据 |
| --- | --- |
| 单元测试 | 10 项通过，无失败或跳过；`junit/`、`unit-test.log` |
| 基础回归 | 35 个 GameTest 入口通过，其中 4 个可选联动入口因依赖缺失直接通过；`gametest-base.log` |
| 建筑库专项 | 开启和关闭配置分别启动新进程，各 1 个完整链路入口通过；`library/enabled.log`、`library/disabled.log` |
| 原世界数据包重载 | 1 个入口通过，新增、修改、删除和活动定位快照保持通过；`reload/reload-command.log` |
| 内置刷怪塔 | 3 个入口通过，两座塔各四个朝向，共 8 次生存建造；`mob-towers/gametest.log`、`mob-towers/mob-towers-runtime.txt` |
| 图形客户端 | 建筑库包含两座内置塔和文件夹数据包，单人读取与局域网加入者选择共享建筑通过；既有旋转、确认、取消、信标、生存收费和预览流程通过；`client-smoke.log`、`client-benchmark.json`、`screenshots/` |
| 正式构建 | JAR 版本为 0.1.4，包含独立内置包，不含开发测试类和其他测试蓝图；`build.log`、`verification.json` |

## 建筑库与蓝图

关闭配置的进程使用世界 `serverconfig/prefabdeploy-server.toml`，第一次进入即不发布两座内置建筑。开启进程从默认配置加载两座塔。资源文件解析与服务器配置加载时序分别核对，配置未加载时不发布内置条目。

文件夹专项实际写入中文命名 ZIP、解压目录、独立 NBT 和世界数据包，通过真实 `/prefab reload` 与 `/datapack` 命令完成加载。世界包与文件夹 ZIP 使用同一文件名，各自保留独立包标识。建筑名称、分类、参考层、空气过滤、解锁规则和手动费用保持 JSON 定义。损坏 JSON、丢失源文件及坏 NBT 成为不可用条目，其他定义正常读取。

关闭内置开关时，世界和文件夹数据包提供的同 ID 建筑仍可读取。禁用文件夹包后执行 `/prefab reload`，该包仍然禁用。重新启用、替换 ZIP、更新解压目录、删除定义、删除 ZIP 和删除整个包目录均已验证。Windows 下活动 ZIP 使用临时副本，原文件可以实际替换或删除；资源关闭后移除临时文件。

散装蓝图扫描只返回独立 NBT，解压包中的 NBT 不产生额外本地条目。专用服务器玩家能访问数据包 ID，不能访问散装本地 ID。单人及局域网主机保留本地权限；图形客户端启动的集成服务器实际从自己的蓝图文件夹读取数据包，发布局域网后加入者测试玩家可以选择共享建筑。

内置 NBT 与原样包的方块数据逐字节相同：普通暗室塔为 22×31×22、15004 个位置，僵尸塔为 13×16×14、2912 个位置。两份蓝图均为 DataVersion 3955，参考层 0，保留空气覆盖。普通塔自动建材与僵尸塔完整手动清单均与游戏报价核对，僵尸塔的刷怪笼仍用 50 块腐肉支付。

刷怪塔测试确认定义来源为 JAR 的独立内置包，不安装外部刷怪塔包。17 个逐项少一件的材料报价均拒绝，未扣取材料。8 次实际生存建造从真实双箱支付，交易只结算一次。四个朝向的方块状态、暗室光照、原版自然刷怪、僵尸刷怪笼运行、水流运输、营火处理和漏斗收集均通过。

## 验证边界

服务端建筑库专项使用 FakePlayer；刷怪塔建造使用 GameTest 玩家并设置生存模式。局域网在真实图形客户端的集成服务器中发布，加入者选择使用服务端 FakePlayer，没有运行第二个客户端的实际远程连接。

客户端截图确认两座内置塔和共享建筑在建筑库中显示，常规预览与定位回归使用现有 NBT 展示建筑。未单独截图验证两座刷怪塔的图形预览。自然刷怪测试调用原版逻辑检查距离、亮度和碰撞；水流载体使用移除自主行为的测试僵尸并保留物理与伤害。这些结果不代表长期产量。

本次没有重跑可选储存和货币联动、整套 BeLoong、光影组合或独立 JVM 崩溃恢复。历史记录保留各自版本，不计入本次通过项。`verification.json` 保存打包时的验证快照；GitHub 发布后的附件核对另记于 `publication.json`。

## 复现

以下命令在 PowerShell 7 中运行，测试缓存必须位于本地磁盘：

```powershell
.\tools\test_building_library.ps1 -CacheRoot C:/pd14
.\Test.ps1 -CacheRoot C:/pd14 -ReportDir docs/test-results/release-0.1.4 -Client
.\tools\test_builtin_mob_towers.ps1 -QaRoot C:/pd14/mob-towers
$reloadLog = .\Build.ps1 -CacheRoot C:/pd14reload -Task runGameTestServer -TestRun C:/pd14reload/run -ReportDir docs/test-results/release-0.1.4/reload -ReloadSmoke
Copy-Item -LiteralPath $reloadLog -Destination docs/test-results/release-0.1.4/reload/reload-command.log
$buildLog = .\Build.ps1 -CacheRoot C:/pd14release -Task @('jar','sourcesJar')
python tools/package_building_library_release.py --build-dir C:/pd14release/build --build-log $buildLog --reports docs/test-results/release-0.1.4
```

内置建筑的构建输入位于 `src/main/resources/builtin/mob_towers/`，无需生成或读取被 Git 忽略的 `examples/library/`、`artifacts/`。刷怪塔专项复用 `tools/fixtures/MobTowerGameTests.java`，复制到独立 QA 项目运行；该测试类不会进入正式 JAR。打包脚本只接受本版本的真实运行记录，并输出安装包、源码 JAR、完整源码 ZIP 与独立 SHA-256 清单。0.1.3 的产物和校验记录保留。
