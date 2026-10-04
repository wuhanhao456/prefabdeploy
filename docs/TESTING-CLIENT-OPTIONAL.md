# 客户端可选安装验证

验证日期：2026 年 10 月 4 日。使用 Minecraft 1.21.1、NeoForge 21.1.248、Java 21.0.11、Windows Server 2025 和 NVIDIA RTX 5060 Ti。

此改动允许客户端额外安装 Prefab Deploy。服务器未安装时，客户端停用建筑功能。单人世界和已安装本 Mod 的服务器继续提供功能。客户端其他 Mod 仍须与服务器兼容。

## 实现

`prefabdeploy:message` 通道改为可选，协议版本仍为 `1`。客户端用当前连接的 `hasChannel` 检查支持情况。客户端请求和服务端发送都先检查连接；无连接或无对应通道时不发送。

服务器不支持时，客户端关闭建筑库、预览、定位、HUD 和相关输入拦截，隐藏创造分类中的工具和信标。恢复保存的快捷栏时，跳过本 Mod 物品及包含这些物品的容器、收纳袋和已装填投射物。原保存文件保留。创造物品提交和丢弃也检查同一条件。

登录和退出会清理目录、定位状态、预览缓存及未完成的解码结果。创造物品栏缓存随连接支持状态刷新。物品和方块 ID、蓝图格式、部署快照及 KubeJS 接口保持兼容。

## 实际结果

完整日志、JSON 和真实游戏截图位于 [test-results/client-optional](test-results/client-optional/)。连接测试使用一个真实 OpenGL 开发客户端及三个独立专用服务器进程。缺装 NeoForge 服务器的 Mod 列表只有 Minecraft 和 NeoForge；原版服务器使用缓存的官方 1.21.1 服务端 JAR。

| 场景 | 结果 |
| --- | --- |
| 同一客户端依次连接支持服务器、缺装 NeoForge、原版、缺装 NeoForge、支持服务器 | 五次连接全部通过；`client-connections.json` |
| 缺装连接 | 正常移动、放置和破坏原版方块、往返下界及重连；无建筑界面或定位状态 |
| 创造物品栏 | 实际打开界面；缺装时隐藏本 Mod 分类和搜索条目；返回支持服务器后恢复 |
| 保存的快捷栏 | 缺装时过滤工具、信标和包含工具的潜影盒，保留原版石头；返回支持服务器后恢复原槽位 |
| 请求与创造提交 | 无通道时调用目录、预览、选择、工具使用、创造提交和丢弃入口，连接保持正常；右键输入不被拦截 |
| 远程部署 | 两次在支持服务器上加载建筑库和预览、验证并实际部署小屋；逐个核对非空气建筑方块 |
| 单人回归 | 真实客户端流程通过；包含本地导入、定位、信标、收费、模型及 1 万／5 万／10 万位置部署；`regression/` |
| 服务端回归 | 35 个 GameTest 入口通过，其中 4 个可选联动入口因未安装依赖而跳过 |
| 单元测试与构建 | 10 项单元测试通过，`assemble` 和 `sourcesJar` 完成；`junit/`、`build.log` |
| 交付 JAR | 检查客户端 Mixin、资源、版本、测试类排除及 SHA-256；`package-check.json` |

已检查原版服务器的创造界面、恢复后的本 Mod 创造界面和建筑库截图。测试没有修改真实整合包或用户世界。

首轮服务端回归发现模拟玩家没有真实 Netty 通道。发送入口已先检查连接有效性，相关测试重跑通过。连接测试夹具也改为等待最终验证通过状态，避免将“检查中”通知当成完成。这些首轮失败不计为通过。

本次没有重跑可选联动、光影和崩溃恢复套件。性能夹具完成部署，但同时运行多个测试进程，不能将这轮数据作为独立性能验收。运行验证使用开发启动参数；交付 JAR 核对了其编译产物和打包结构。本次构建沿用 0.1.2 版本号，以独立 ZIP 追加到该版本发布页。

## 复现

使用全新的本地缓存目录。Windows 在 PowerShell 7 中执行：

```powershell
$compatCache = 'C:/work/prefabdeploy-client-optional'
.\Build.ps1 -CacheRoot $compatCache -Task @('test', 'prepareClientRun', 'prepareServerRun', 'assemble', 'sourcesJar') -ConnectionSmoke -ConnectionServers 'supported@127.0.0.1:25580,neoforge@127.0.0.1:25581,vanilla@127.0.0.1:25582,neoforge@127.0.0.1:25581,supported@127.0.0.1:25580' -TestRun "$compatCache/connections/client" -ReportDir "$compatCache/reports"
python tools/test_client_connections.py --cache $compatCache --vanilla-jar "$env:USERPROFILE/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_server.jar"
.\Test.ps1 -CacheRoot 'C:/work/prefabdeploy-client-regression' -ReportDir 'C:/work/prefabdeploy-client-regression/reports' -Client
```

连接测试只监听 `127.0.0.1`，使用 25580–25582。测试结束关闭它创建的服务器进程，世界和日志保留在缓存中。`--base-port` 可选择其他连续端口。重跑时用新的缓存，或为连接脚本指定新的 `--run-name` 和 `--report-name`，保留旧证据。

## 安装本次构建

从 [GitHub 发布页下载客户端可选安装构建](https://github.com/wuhanhao456/prefabdeploy/releases/download/v0.1.2/prefabdeploy-0.1.2-client-optional.zip)，或使用本地交付目录 `dist/client-optional/`。解压后将其中的 `prefabdeploy-0.1.2.jar` 替换到客户端 `mods/`，同一实例只保留一个 Prefab Deploy JAR。无需修改缺装服务器，也无需配置开关。

本次构建沿用 0.1.2 版本号，但包含上述源码改动。它与此前发布的 0.1.2 文件不同；核对 ZIP 内的 `SHA256SUMS.txt`。原发布页单独提供的 JAR 和源码 ZIP 保留此前内容。

GitHub 上传后已核对新增 ZIP 和外部校验清单的大小、平台返回的 SHA-256 及重新下载文件的 SHA-256，并验证 ZIP 内的全部校验值。此前五个发布文件保持原内容。结果见[发布核验记录](test-results/client-optional/publication.json)。
