# Prefab Deploy 0.1.2

Prefab Deploy lets players preview and deploy saved buildings. It runs on Minecraft 1.21.1, NeoForge 21.1.248 or later for 1.21.1, and Java 21. Install the same Mod version on the client and server.

## Use

1. Put the Mod JAR in `mods/`. Remove the previous version.
2. Get a Deployment Tool from its recipe or the Prefab Deploy creative tab.
3. Right-click to open the library. Select a building and a placement mode.
4. For crosshair placement, press `R` to rotate clockwise by 90 degrees.
5. Right-click inside the preview frame to fix the position. Wait for validation. Right-click inside the frame again to deploy.
6. Right-click outside the frame to cancel. Hold the tool or a beacon and use `Shift+right-click` to cancel in any direction.

Empty space inside the frame counts as a target. Normal confirmation waits for the preview to load. Construction cannot be cancelled after it starts.

For three-beacon placement, place A, B and C at the same height. A–B must equal the building width. A–C must equal its depth. These are boundary distances. Switch back to the tool and confirm twice. Before all three beacons are placed, a tool right-click cancels the session.

Beacon right-click on a block uses normal placement. Right-click in the air places a floating beacon. Use `Shift+scroll` to change its distance. Successful deployment returns only the beacons that were consumed.

## Import local blueprints

Select **Open blueprint folder**. Put `.litematic` or structure `.nbt` files in `<instance>/prefabdeploy/blueprints/`. Reopen the library to read changes. Subfolders are supported.

Litematic versions 5–7 are supported. All files must use Minecraft 1.21.1 DataVersion 3955. Create NBT files must follow the vanilla structure format. `.schem` is not supported.

Local buildings use the filename as their name. The ground reference is 0. Air overwrites existing blocks. Survival mode charges building materials. Creative mode does not charge costs. Rules and position permissions still apply.

Local import is available only to the singleplayer or LAN world host. LAN guests cannot access the host's local buildings. The folder button is disabled on dedicated servers. Server buildings come from datapacks. The release JAR includes no test buildings.

See the [datapack guide](DATAPACKS.md), [cost guide](COSTS.md), [KubeJS guide](KUBEJS.md) and [Java API](API.md). The project uses [MPL-2.0](../LICENSE).

## Recipes and server reload

The Deployment Tool uses 2 iron ingots, 1 paper, 1 compass and 1 redstone dust. Put iron, paper and iron in the top row. Put the compass in the center. Put redstone below it. The recipe makes one tool.

The Positioning Beacon uses 3 amethyst shards, 1 glowstone and 1 redstone dust. Put amethyst above and beside the glowstone. Put redstone below it. The recipe makes three beacons.

Run `/prefab reload` in game, or `prefab reload` in the server console. The command requires permission level 2. It discovers new datapacks and reloads building definitions, blueprints, costs and rules. It reports the number of buildings and unavailable entries. Active placements and deployments keep their original snapshots. It does not reload server TOML configuration.
