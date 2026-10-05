# Prefab Deploy

## English

Prefab Deploy is a prefab building mod for Minecraft 1.21.1. Use the Building Tool to select a building, preview it, adjust its position and rotation, then confirm construction. Servers provide buildings through datapacks. Singleplayer and LAN hosts can also load local blueprints.

### Installation and building sources

Requires Minecraft 1.21.1, Java 21, and NeoForge 21.1.248 or later for Minecraft 1.21.1. Put the Prefab Deploy JAR in the instance's `mods/` folder. Install the same version on the client and server to use building features, and keep only one Prefab Deploy JAR in each instance.

You can keep Prefab Deploy on the client when joining a server without it; building features are disabled on that server. Other client mods must still be compatible with the server.

**The release JAR does not include a building library.** Add a building datapack or import your own blueprints before use. If a blueprint uses modded blocks or entities, install those mods on both the client and server.

### How to build

1. Craft a Building Tool with 2 iron ingots, 1 paper, 1 compass and 1 redstone dust, or take one from the **Prefab Deploy** creative tab.
2. Hold the tool and right-click to open the library, then select a building. Drag the preview on the right to rotate it, or scroll to zoom.
3. Select **Crosshair placement**. Move the crosshair to adjust the position, and press `R` to rotate clockwise by 90°.
4. Right-click inside the preview frame to fix the position. Once validation passes and the frame turns green, right-click inside it again to build.

Right-click outside the frame to cancel placement. While holding the tool or a Positioning Beacon, use `Shift+right-click` to cancel placement in any direction. Construction cannot be cancelled after it starts, and there is no manual undo feature.

You can also select **Three-beacon placement** and place Positioning Beacons A, B and C in that order to set the position and direction. All three must be at the same height. The A–B and A–C boundary distances must match the building's width and depth. After placing all three, switch back to the Building Tool and confirm twice.

### Importing blueprints

Select **Open blueprint folder** in the library, put files in the instance's `prefabdeploy/blueprints/` folder, then reopen the library. Subfolders are also read.

- Supports vanilla structure `.nbt` and `.litematic` format versions 5–7.
- Files must use Minecraft 1.21.1 DataVersion 3955.
- Create NBT files must follow the vanilla structure format.
- `.schem` and conversion of older blueprints are not supported.

Local import is available only to singleplayer and LAN hosts. LAN guests cannot access the host's local buildings, and local files are not uploaded to dedicated servers. Server administrators must put building datapacks in the world's `datapacks/` folder and run `/prefab reload`. This command requires permission level 2.

Local buildings use the lowest layer as their ground reference, and air overwrites existing blocks. Datapacks can change the ground reference to place part of a building underground, or set whether to skip air in the blueprint.

### Costs and modpack configuration

Survival mode charges the costs set in the building definition. Buildings can use automatically calculated materials, specified items or XP points, or be free to build. Creative mode waives costs, while unlock requirements, building conditions and position permissions still apply.

While idle, Shift+right-click a container with the Building Tool to bind it. The gray item tooltip shows its coordinates and dimension. Each tool keeps its own binding. Shift+right-click air while idle clears it; during placement the gesture still cancels placement. Item costs use the bound container or AE2 ME network first, then the player inventory, carried Sophisticated Backpacks and the player's current Beyond Dimensions primary network. Remote use requires the same dimension and an already loaded source chunk. Quantities can be combined across these sources. Water has no automatic material cost. Lava costs use lava buckets first, then 1000 mB of network lava for each remaining block. Manually configured bucket costs still require items, and backpack tanks do not supply fluid for construction.

Sophisticated Backpacks and Beyond Dimensions are optional. The resource adapters check each mod's declared version. Versions outside the supported list cannot supply construction resources. See the [resource compatibility guide](https://github.com/wuhanhao456/prefabdeploy/blob/main/docs/RESOURCE-COMPAT.md) for supported versions and source limits. This guide is in Chinese.

Modpack authors can use datapacks and KubeJS to configure building visibility, unlock requirements, building conditions and costs. Optional integrations include FTB Quests task completion, FTB Teams flags, FTB Chunks editing permissions and ViScriptShop native currency costs. Each integration requires its corresponding mod.

### Documentation and issue reports

[English quick start](https://github.com/wuhanhao456/prefabdeploy/blob/main/docs/QUICKSTART.en.md) · [Datapack guide](https://github.com/wuhanhao456/prefabdeploy/blob/main/docs/DATAPACKS.md) · [KubeJS guide](https://github.com/wuhanhao456/prefabdeploy/blob/main/docs/KUBEJS.md) · [Report an issue](https://github.com/wuhanhao456/prefabdeploy/issues)

The datapack and KubeJS guides are in Chinese. This mod was generated by AI. Stability or compatibility issues may occur. License: [MPL-2.0](https://github.com/wuhanhao456/prefabdeploy/blob/main/LICENSE).

## 中文

Prefab Deploy 是 Minecraft 1.21.1 的预制建筑 Mod。玩家用建筑建造工具选择建筑，查看预览、调整位置和方向，再确认建造。服务器通过数据包提供建筑；单人玩家和局域网主机也可以读取本地蓝图。

### 安装与建筑来源

需要 Minecraft 1.21.1、Java 21，以及 NeoForge 21.1.248 或更高的 1.21.1 版本。将 Prefab Deploy JAR 放入实例的 `mods/` 文件夹。使用建筑功能时，客户端和服务器安装同一版本，每个实例只保留一个 Prefab Deploy JAR。

连接未安装 Prefab Deploy 的服务器时，客户端会停用建筑功能。其他客户端模组仍须与服务器兼容。

**正式 JAR 不附带建筑库。** 开始使用前，请添加建筑数据包或导入自己的蓝图。蓝图使用模组方块或实体时，客户端和服务器还需安装对应模组。

### 如何建造

1. 合成建筑建造工具：需要 2 个铁锭、1 张纸、1 个指南针和 1 个红石粉。创造模式也可从“预制建筑”标签页取出工具。
2. 持工具右键打开建筑库，选择建筑。拖动右侧预览可旋转，滚轮可缩放。
3. 选择“准星定位”，移动准星调整位置，按 `R` 顺时针旋转 90°。
4. 对预览外框内右键固定位置。等待验证通过、外框变绿后，再对外框内右键建造。

对外框外右键可取消定位。持工具或位置信标时，`Shift+右键` 可向任意方向取消定位。施工开始后不能取消，也没有主动撤销功能。

也可选择“三点信标定位”，依次放置 A、B、C 三个位置信标来确定位置和方向。三点必须同高，A 到 B、A 到 C 的边界距离分别对应建筑宽度和深度。放完三点后换回工具，仍需两次右键确认。

### 导入蓝图

在建筑库点击“打开蓝图文件夹”，将文件放入实例的 `prefabdeploy/blueprints/`，然后重新打开建筑库。子目录也会读取。

- 支持原版结构 `.nbt` 和格式版本 5–7 的 `.litematic`。
- 文件必须使用 Minecraft 1.21.1 的 DataVersion 3955。
- Create NBT 必须符合原版结构格式。
- 不支持 `.schem`，也不转换旧版蓝图。

本地导入仅向单人玩家和局域网主机开放。局域网加入者不能访问主机的本地建筑，本地文件也不会上传到专用服务器。服务器管理员需将建筑数据包放入世界的 `datapacks/`，再执行 `/prefab reload`。该命令需要权限等级 2。

本地建筑默认以最低层为参考层，空气会覆盖原方块。数据包可以调整参考层，让建筑的一部分进入地下，也可以设置是否跳过蓝图中的空气。

### 费用与整合包配置

生存模式按建筑定义收取费用，可以自动计算建筑材料、指定物品或经验点数，也可以设为免费。创造模式免除费用，仍检查解锁条件、建造条件和位置权限。

空闲时持建筑建造工具，对容器 Shift+右键绑定材料来源；灰色物品提示显示坐标和维度。每把工具独立保存绑定，对空气 Shift+右键解绑，定位期间仍用于取消定位。物品优先从绑定容器或 AE2 ME 网络扣取，再由玩家库存、随身精妙背包和当前超越维度主网络补足。来源需要同维度且区块已加载，数量可以跨来源合并。自动材料中的水免费；岩浆先消耗岩浆桶，剩余格数从网络扣取每格 1000 mB 岩浆。手动配置的桶费用仍按物品收费，背包储罐不参与流体扣取。

精妙背包和超越维度均为可选依赖。当前资源兼容构建只支持文档列出的模组声明版本，版本不符时会拒绝该资源来源。具体版本和来源范围见[资源兼容说明](https://github.com/wuhanhao456/prefabdeploy/blob/main/docs/RESOURCE-COMPAT.md)。

整合包作者可通过数据包和 KubeJS 配置建筑的可见范围、解锁需求、建造条件和费用。可选联动包括 FTB Quests 任务解锁、FTB Teams 团队标记、FTB Chunks 领地权限，以及 ViScriptShop 原生货币费用。使用对应功能时需要安装对应模组。

### 文档与问题反馈

[中文使用说明](https://github.com/wuhanhao456/prefabdeploy/blob/main/README.md) · [数据包指南](https://github.com/wuhanhao456/prefabdeploy/blob/main/docs/DATAPACKS.md) · [KubeJS 指南](https://github.com/wuhanhao456/prefabdeploy/blob/main/docs/KUBEJS.md) · [报告问题](https://github.com/wuhanhao456/prefabdeploy/issues)

本模组由 AI 生成，可能存在稳定性或兼容性问题。许可为 [MPL-2.0](https://github.com/wuhanhao456/prefabdeploy/blob/main/LICENSE)。
