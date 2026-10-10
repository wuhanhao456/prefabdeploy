# Prefab Deploy 0.1.4

普通暗室刷怪塔和僵尸刷怪笼塔现在随 Mod 内置，默认出现在建筑库中。两座塔直接解锁，仅限主世界，保留原蓝图、参考层和空气覆盖。普通塔自动计算建材；僵尸塔使用完整手动材料清单，以 50 块腐肉替代一个刷怪笼。

服务端配置增加 `buildings.enableDefaultTestBuildings`，默认 `true`。改为 `false` 并重启后关闭内置条目，外部数据包提供的同 ID 建筑仍可读取。

实例的 `prefabdeploy/blueprints/` 现在同时支持数据包 ZIP 和解压后的包目录。包根必须包含 `pack.mcmeta`，管理员执行 `/prefab reload` 后读取新增、修改或删除的内容。数据包保留作者定义的名称、规则和费用，向运行世界的服务器玩家共享。单人世界读取本机，局域网读取主机，专用服务器读取服务端。散装 NBT 和 Litematic 仍沿用原有本地权限，数据包内的 NBT 不会重复导入。

文件夹 ZIP 使用临时副本参与资源加载，在 Windows 上也能于运行中替换或删除原 ZIP。内置数据包优先级最低，其他数据包遵循 Minecraft 的启用顺序。世界已禁用的数据包不会被 `/prefab reload` 重新启用，活动定位和建造保留原快照。

需要 Minecraft 1.21.1、Java 21 和 NeoForge 21.1.248 或更高的 1.21.1 版本。客户端与服务器安装相同版本，删除旧版 Prefab Deploy JAR。0.1.3 文件继续保留。

- [安装包](https://github.com/wuhanhao456/prefabdeploy/releases/download/v0.1.4/prefabdeploy-0.1.4.zip)：正式 JAR、源码 JAR、许可、配置示例、安装说明和校验清单。
- [正式 JAR](https://github.com/wuhanhao456/prefabdeploy/releases/download/v0.1.4/prefabdeploy-0.1.4.jar)：直接放入 `mods/`。
- 发布附件还提供完整源码 ZIP 和 [SHA-256 清单](https://github.com/wuhanhao456/prefabdeploy/releases/download/v0.1.4/SHA256SUMS-0.1.4.txt)。

10 项单元测试和 35 个基础 GameTest 入口通过，其中 4 个可选联动入口因依赖缺失未执行实际检查。建筑库冷启动、文件夹数据包重载与活动任务快照专项通过。两座内置塔完成四个朝向共 8 次生存建造，17 个材料不足报价检查通过。图形客户端确认建筑库条目和共享访问，局域网加入者选择使用服务端测试玩家；没有运行第二个客户端的远程连接，也没有重跑完整整合包、光影或可选资源联动。

构建与验证步骤见[0.1.4 验证记录](https://github.com/wuhanhao456/prefabdeploy/blob/v0.1.4/docs/TESTING-0.1.4.md)。
