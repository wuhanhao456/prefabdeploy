# Prefab Deploy 0.1.3

建筑建造工具现在可以绑定材料容器。空闲时持工具对容器 `Shift+右键`，灰色物品提示显示绑定坐标和维度。建造优先从绑定来源消耗物品，再使用玩家库存、随身精妙背包和超越维度补足。

支持原版箱子、木桶、精妙储存和 AE2 终端或接口连接的整个 ME 网络。每把工具独立保存绑定；空闲时对空气 `Shift+右键` 清除绑定。定位期间该操作仍取消定位并保留绑定。来源须在同维度且区块已加载。权限或来源不可用时，显示原因并使用后续来源。

增加原来源返还、双箱跨区块凭据、建造覆盖范围过滤和 AE2 网络身份检查。第三方未完成事务遇到冷启动时保留 `HELD/UNCERTAIN`，供管理员核对，避免重复扣取或返还。

需要 Minecraft 1.21.1、Java 21、NeoForge 21.1.248 或更新的 1.21.1 版本。客户端和服务器安装相同版本，并删除旧 Prefab Deploy JAR。已有建筑数据包和 `cost` 无需修改。

- [安装包](https://github.com/wuhanhao456/prefabdeploy/releases/download/v0.1.3/prefabdeploy-0.1.3.zip)：正式 JAR、源码 JAR、许可、安装说明和校验清单。
- [正式 JAR](https://github.com/wuhanhao456/prefabdeploy/releases/download/v0.1.3/prefabdeploy-0.1.3.jar)：直接放入 `mods/`。
- 发布附件还提供完整源码 ZIP 和 SHA-256 清单，不包含第三方 Mod。

0.1.3 重跑并通过 10 项单元测试、35 个基础 GameTest 入口、45 个容器及领地入口、45 个旧资源入口和真实客户端流程。可选依赖用例按各自测试组合执行。改版前完成的 8 组独立 JVM 强制退出验证保留原始记录；正式 JAR 中的运行代码和资源与该开发构建一致，版本元数据改为 0.1.3。本次没有重跑光影及整套 BeLoong。

精妙储存验证版本为 1.6.1.2147；AE2 当前支持 19.2.18，只提供已存储物品。具体版本、来源范围与恢复方式见[资源兼容](https://github.com/wuhanhao456/prefabdeploy/blob/v0.1.3/docs/RESOURCE-COMPAT.md)，本次检查见[0.1.3 验证记录](https://github.com/wuhanhao456/prefabdeploy/blob/v0.1.3/docs/TESTING-0.1.3.md)。
