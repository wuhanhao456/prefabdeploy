# Prefab Deploy

Prefab Deploy is a prefab building mod for Minecraft 1.21.1. Use the Building Tool to select a building, preview it, adjust its position and rotation, then confirm construction. Servers provide buildings through datapacks. Singleplayer and LAN hosts can also load local blueprints.

## Installation and building sources

Requires Minecraft 1.21.1, Java 21, and NeoForge 21.1.248 or later for Minecraft 1.21.1. Put the Prefab Deploy JAR in the instance's `mods/` folder. Install the same version on the client and server to use building features, and keep only one Prefab Deploy JAR in each instance.

You can keep Prefab Deploy on the client when joining a server without it; building features are disabled on that server. Other client mods must still be compatible with the server.

**The release JAR does not include a building library.** Add a building datapack or import your own blueprints before use. If a blueprint uses modded blocks or entities, install those mods on both the client and server.

## How to build

1. Craft a Building Tool with 2 iron ingots, 1 paper, 1 compass and 1 redstone dust, or take one from the **Prefab Deploy** creative tab.
2. Hold the tool and right-click to open the library, then select a building. Drag the preview on the right to rotate it, or scroll to zoom.
3. Select **Crosshair placement**. Move the crosshair to adjust the position, and press `R` to rotate clockwise by 90°.
4. Right-click inside the preview frame to fix the position. Once validation passes and the frame turns green, right-click inside it again to build.

Right-click outside the frame to cancel placement. While holding the tool or a Positioning Beacon, use `Shift+right-click` to cancel placement in any direction. Construction cannot be cancelled after it starts, and there is no manual undo feature.

You can also select **Three-beacon placement** and place Positioning Beacons A, B and C in that order to set the position and direction. All three must be at the same height. The A–B and A–C boundary distances must match the building's width and depth. After placing all three, switch back to the Building Tool and confirm twice.

## Importing blueprints

Select **Open blueprint folder** in the library, put files in the instance's `prefabdeploy/blueprints/` folder, then reopen the library. Subfolders are also read.

- Supports vanilla structure `.nbt` and `.litematic` format versions 5–7.
- Files must use Minecraft 1.21.1 DataVersion 3955.
- Create NBT files must follow the vanilla structure format.
- `.schem` and conversion of older blueprints are not supported.

Local import is available only to singleplayer and LAN hosts. LAN guests cannot access the host's local buildings, and local files are not uploaded to dedicated servers. Server administrators must put building datapacks in the world's `datapacks/` folder and run `/prefab reload`. This command requires permission level 2.

Local buildings use the lowest layer as their ground reference, and air overwrites existing blocks. Datapacks can change the ground reference to place part of a building underground, or set whether to skip air in the blueprint.

## Costs and modpack configuration

Survival mode charges the costs set in the building definition. Buildings can use automatically calculated materials, specified items or XP points, or be free to build. Creative mode waives costs, while unlock requirements, building conditions and position permissions still apply.

While idle, Shift+right-click a container with the Building Tool to bind it. The gray item tooltip shows its coordinates and dimension. Each tool keeps its own binding. Shift+right-click air while idle clears it; during placement the gesture still cancels placement. Item costs use the bound container or AE2 ME network first, then the player inventory, carried Sophisticated Backpacks and the player's current Beyond Dimensions primary network. Remote use requires the same dimension and an already loaded source chunk. Quantities can be combined across these sources. Water has no automatic material cost. Lava costs use lava buckets first, then 1000 mB of network lava for each remaining block. Manually configured bucket costs still require items, and backpack tanks do not supply fluid for construction.

Sophisticated Backpacks and Beyond Dimensions are optional. The resource adapters check each mod's declared version. Versions outside the supported list cannot supply construction resources. See the [resource compatibility guide](https://github.com/wuhanhao456/prefabdeploy/blob/main/docs/RESOURCE-COMPAT.md) for supported versions and source limits. This guide is in Chinese.

Modpack authors can use datapacks and KubeJS to configure building visibility, unlock requirements, building conditions and costs. Optional integrations include FTB Quests task completion, FTB Teams flags, FTB Chunks editing permissions and ViScriptShop native currency costs. Each integration requires its corresponding mod.

## Documentation and issue reports

[English quick start](https://github.com/wuhanhao456/prefabdeploy/blob/main/docs/QUICKSTART.en.md) · [Datapack guide](https://github.com/wuhanhao456/prefabdeploy/blob/main/docs/DATAPACKS.md) · [KubeJS guide](https://github.com/wuhanhao456/prefabdeploy/blob/main/docs/KUBEJS.md) · [Report an issue](https://github.com/wuhanhao456/prefabdeploy/issues)

The datapack and KubeJS guides are in Chinese. This mod was generated by AI. Stability or compatibility issues may occur. License: [MPL-2.0](https://github.com/wuhanhao456/prefabdeploy/blob/main/LICENSE).
