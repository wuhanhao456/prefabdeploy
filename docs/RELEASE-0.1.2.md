# Prefab Deploy 0.1.2 交付记录

需要 Minecraft 1.21.1、Java 21 和 NeoForge 21.1.248 或更高的 1.21.1 版本。客户端和服务器使用同一 Mod 版本。项目许可为 MPL-2.0。

当前安装使用[资源兼容构建](https://github.com/wuhanhao456/prefabdeploy/releases/download/v0.1.2/prefabdeploy-0.1.2-resource-compat.zip)。下文分别记录原始发布和追加构建的验证范围。

## 原始 0.1.2 修改

- 部署工具使用新蓝图模型。双手持握支持左右惯用手。副手持物时使用单手姿态。城堡纹理按透明度序列播放。
- 位置信标使用新模型。客户端显示浮动核心、旋转和粒子。旧信标在区块加载时补建视觉方块实体。
- 普通右键命中预览外框时确认，外框外取消。Shift+右键可向任意方向取消。加载中的预览等待完成后才接受普通确认。
- 本地蓝图文件夹支持 NBT 和 Litematic。主机可读取子目录和文件变更。服务器检查本地建筑权限。
- 正式版移除三个测试建筑。开发测试使用独立数据包。
- `/prefab reload` 发现和重读建筑数据包。活动任务保留原快照。指令结果支持本地化。
- 增加部署工具和位置信标配方。配方书在获得对应材料后解锁。
- 更新中英文文本、README、数据包指南、费用指南、KubeJS 指南和 Agent 指南。

## 原始 0.1.2 验证

10 项单元测试通过。基础套件的 35 个 GameTest 入口通过，其中 4 项可选联动场景跳过。联动套件的 35 项全部执行并通过。热加载专项测试、三阶段崩溃恢复和真实客户端流程通过。

Iris 1.8.14-beta.1 与 Sodium 0.8.13 的组合通过测试。分别测试关闭光影、Complementary Reimagined r5.9 和 BSL v10.1.1。详细范围与限制见[验证记录](TESTING-0.1.2.md)。

正式 JAR 不包含测试建筑、开发测试类或独立模型 QA 产物。打包脚本核对许可、版本、配方和模型资源。干净检出构建的结果记录在 `test-results/release-0.1.2/clean-build.json`。

## 原始 0.1.2 文件

交付文件位于 `dist/`：正式 JAR、sources JAR、完整源码 ZIP、开发性能数据包和 SHA-256 清单。

将 `prefabdeploy-0.1.2.jar` 放入客户端和服务器的 `mods/`。删除同一 Mod 的旧版 JAR。服务器将建筑数据包放入世界的 `datapacks/` 后执行 `/prefab reload`。单人玩家和局域网主机可使用本地蓝图文件夹。

源码仓库：[wuhanhao456/prefabdeploy](https://github.com/wuhanhao456/prefabdeploy)。

[0.1.2 发布页](https://github.com/wuhanhao456/prefabdeploy/releases/tag/v0.1.2) 提供上述文件。上传后已核对文件大小和 GitHub 返回的 SHA-256。发布记录位于 `test-results/release-0.1.2/publication.json`。源码 ZIP 保留上传前的验收快照。

## 客户端可选安装追加构建

2026 年 10 月 4 日追加 [prefabdeploy-0.1.2-client-optional.zip](https://github.com/wuhanhao456/prefabdeploy/releases/download/v0.1.2/prefabdeploy-0.1.2-client-optional.zip)。版本号仍为 0.1.2。解压后使用其中的 JAR，可连接未安装本 Mod 的 NeoForge 和原版服务器，连接中自动停用建筑功能。单人世界和支持服务器保留建筑功能。

ZIP 包含新 JAR、sources JAR、许可、安装说明和 SHA-256 清单。原发布文件保持原内容。五次真实远程连接、单人回归、基础 GameTest 和单元测试通过；本次没有重跑可选联动、光影及崩溃恢复。具体范围见[客户端可选安装验证](TESTING-CLIENT-OPTIONAL.md)。

上传后的大小、GitHub SHA-256 和重新下载校验全部通过；此前五个发布文件保持原内容。核验记录位于 [test-results/client-optional/publication.json](test-results/client-optional/publication.json)。

## 资源兼容追加构建（2026-10-04）

下载 [prefabdeploy-0.1.2-resource-compat.zip](https://github.com/wuhanhao456/prefabdeploy/releases/download/v0.1.2/prefabdeploy-0.1.2-resource-compat.zip)。解压后，将其中的 JAR 放入客户端和服务器的 `mods/`。同一实例只保留一个 Prefab Deploy JAR。

- 物品按玩家库存、随身精妙背包、当前绑定的超越维度主网络扣取。数量可以跨来源合并。
- 自动水方块免费。自动岩浆先消耗岩浆桶，剩余格数从网络扣取每格 1000 mB 岩浆。
- 手动配置的水桶和岩浆桶仍按物品收费。背包储罐不参与扣取。
- 外部资源退回原存储。玩家更换背包或网络不会改变退款目标。
- 来源缺失、退款空间不足或保存结果不确定时，保留凭据并等待恢复。

已有建筑 `cost` 无需修改。公开 `CostProvider` 接口保持兼容。此构建也包含此前的客户端可选安装功能。

兼容基准为超越维度 0.7.30、精妙背包 3.26.3.2158、精妙核心 1.5.1.2341 和 Curios 9.5.1。兼容模组均为可选依赖。支持版本和来源范围见[资源兼容](RESOURCE-COMPAT.md)。

五种安装组合均通过服务器启动和部署检查。完整资源组合执行并通过 21 项用例。首轮 20 个进程崩溃点通过。最后一次背包缓存修正后复测其中 2 个崩溃点。10 项单元测试通过。基础和原有联动 GameTest 的 35 个入口通过。基础套件跳过 4 个可选联动入口。整套 BeLoong 客户端操作尚未验证。详细范围见[资源兼容验证](TESTING-RESOURCES.md)。

版本号仍为 0.1.2。ZIP 包含正式 JAR、源码 JAR、许可、安装说明、文档和内部 SHA-256 清单。外部校验清单为 `SHA256SUMS-resource-compat.txt`。发布页中的旧独立 JAR 和旧 ZIP 保留各自的内容。
