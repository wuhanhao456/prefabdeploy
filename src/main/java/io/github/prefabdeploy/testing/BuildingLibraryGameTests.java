package io.github.prefabdeploy.testing;

import com.mojang.authlib.GameProfile;
import io.github.prefabdeploy.*;
import io.github.prefabdeploy.library.*;
import io.github.prefabdeploy.network.Network;
import io.github.prefabdeploy.server.Sessions;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

/** Isolated reload suite: run with -PbuildingLibrarySmoke in a disposable game directory. */
@GameTestHolder("prefabpacks")
@PrefixGameTestTemplate(false)
public final class BuildingLibraryGameTests {
  private static final String MC_META = "{\"pack\":{\"pack_format\":48,\"description\":\"Building library regression\"}}";
  private static final ResourceLocation DARK = ResourceLocation.parse("mobtowers:dark_tower");
  private static Prefab get(String id) { return PrefabLibrary.INSTANCE.get(ResourceLocation.parse(id)); }
  private static String definition(String name, String source) {
    return "{\"name\":\"" + name + "\",\"category\":\"Custom\",\"source\":\"" + source
        + "\",\"ground_y\":2,\"ignore_air\":true,\"unlock\":{\"type\":\"flag\",\"scope\":\"player\",\"id\":\"housing_test\"},"
        + "\"conditions\":{\"type\":\"dimension\",\"id\":\"minecraft:overworld\"},"
        + "\"cost\":{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:emerald\",\"count\":7}],\"xp\":12}}";
  }
  private static void write(Path path, byte[] bytes) throws IOException {
    Files.createDirectories(path.getParent()); Files.write(path, bytes);
  }
  private static void write(Path path, String text) throws IOException {
    write(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  private static void zip(Path file, byte[] blueprint, String name, boolean broken) throws IOException {
    try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
      var files = new LinkedHashMap<String, byte[]>();
      files.put("pack.mcmeta", MC_META.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      files.put("data/folderqa/blueprints/house.nbt", blueprint);
      files.put("data/folderqa/prefabs/house.json", definition(name, "folderqa:blueprints/house.nbt").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      files.put("data/mobtowers/prefabs/dark_tower.json", definition("Folder override", "folderqa:blueprints/house.nbt").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      if (broken) {
        files.put("data/folderqa/prefabs/broken.json", "{".getBytes());
        files.put("data/folderqa/prefabs/missing.json", definition("Missing", "folderqa:blueprints/missing.nbt").getBytes());
        files.put("data/folderqa/blueprints/broken.nbt", new byte[]{1, 2, 3});
        files.put("data/folderqa/prefabs/bad_nbt.json", definition("Broken NBT", "folderqa:blueprints/broken.nbt").getBytes());
      }
      for (var entry : files.entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey())); out.write(entry.getValue()); out.closeEntry();
      }
    }
  }
  private static void remove(Path root) throws IOException {
    if (!Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }
  private static void present(GameTestHelper h, String id, String name) {
    var f = get(id);
    h.assertTrue(f != null && f.valid() && f.name().equals(name), "Missing or invalid " + id + ": " + f);
  }

  @GameTest(template="empty", timeoutTicks=2400)
  public static void startup_switch_folder_packs_reload_disable_override_and_snapshots(GameTestHelper h) throws Exception {
    var server = h.getLevel().getServer();
    boolean initial = Config.ENABLE_DEFAULT_TEST_BUILDINGS.get();
    h.assertTrue(initial == Boolean.parseBoolean(System.getProperty("prefabdeploy.expectedDefaultBuildings", "true")), "SERVER config did not load expected startup setting");
    h.assertTrue((get(DARK.toString()) != null) == initial
        && (get("mobtowers:zombie_spawner_tower") != null) == initial, "Cold startup ignored default building switch");
    if (initial) {
      var dark = get(DARK.toString()); var zombie = get("mobtowers:zombie_spawner_tower");
      h.assertTrue(dark.valid() && dark.blueprint().width() == 22 && dark.blueprint().height() == 31
          && zombie.valid() && zombie.blueprint().width() == 13 && zombie.blueprint().height() == 16, "Builtin NBT failed import");
    }
    PrefabDeploy.LOGGER.info("PREFAB BUILDINGS COLD START VERIFIED enabled={}", initial);
    Path root = LocalBlueprints.directory();
    Files.createDirectories(root);
    Path packed = root.resolve("住宅示例.zip"), unpacked = root.resolve("解压样包"), loose = root.resolve("独立蓝图.nbt");
    Path world = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("住宅示例.zip");
    byte[] blueprint;
    try (var input = server.getResourceManager().getResourceOrThrow(ResourceLocation.parse("prefabdeploy:blueprints/cottage.nbt")).open()) { blueprint = input.readAllBytes(); }
    zip(packed, blueprint, "Zip1", true);
    write(unpacked.resolve("pack.mcmeta"), MC_META);
    write(unpacked.resolve("data/unpackedqa/blueprints/house.nbt"), blueprint);
    Path updated = unpacked.resolve("data/unpackedqa/prefabs/house.json");
    write(updated, definition("Directory1", "unpackedqa:blueprints/house.nbt"));
    write(loose, blueprint);
    write(world.resolve("pack.mcmeta"), MC_META);
    write(world.resolve("data/worldqa/blueprints/house.nbt"), blueprint);
    write(world.resolve("data/worldqa/prefabs/house.json"), definition("World", "worldqa:blueprints/house.nbt"));
    Path override = world.resolve("data/mobtowers/prefabs/dark_tower.json");
    write(override, definition("World override", "worldqa:blueprints/house.nbt"));
    var player = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "014_packs"));
    player.getInventory().setItem(0, new ItemStack(PrefabDeploy.TOOL.get()));
    var request = Network.message("select"); request.putString("id", "prefabdeploy:nbt_gallery"); Sessions.receive(player, request);
    var old = get("prefabdeploy:nbt_gallery");
    var source = server.createCommandSourceStack();
    String folderId = BlueprintPacks.FOLDER_PREFIX + packed.getFileName();
    int[] phase = {0}; long[] revision = {PrefabLibrary.INSTANCE.revision()};
    h.assertTrue(server.getCommands().getDispatcher().execute("prefab reload", source) == 1, "Initial reload refused");
    h.succeedWhen(() -> {
      h.assertTrue(PrefabLibrary.INSTANCE.revision() > revision[0], "Waiting for resource reload");
      try {
        if (phase[0] == 0) {
          present(h, "folderqa:house", "Zip1"); present(h, "unpackedqa:house", "Directory1"); present(h, "worldqa:house", "World");
          var repository = server.getPackRepository();
          var ids = new ArrayList<>(repository.getSelectedIds());
          h.assertTrue(ids.contains(folderId) && ids.contains("file/住宅示例.zip"), "Same-name folder and world pack IDs collided");
          h.assertTrue(ids.indexOf(BlueprintPacks.BUILTIN_ID) < ids.indexOf(folderId), "Builtin pack is not lowest priority");
          var f = get("folderqa:house");
          h.assertTrue(f.groundY() == 2 && f.category().equals("Custom") && f.blueprint().voxels().stream().noneMatch(v -> v.state().isAir())
              && f.metadata().getAsJsonObject("cost").get("xp").getAsInt() == 12
              && f.metadata().getAsJsonObject("unlock").get("id").getAsString().equals("housing_test"), "Author metadata was replaced by loose blueprint defaults");
          for (String bad : List.of("broken", "missing", "bad_nbt")) h.assertTrue(get("folderqa:" + bad) != null && !get("folderqa:" + bad).valid(), "Bad definition did not become unavailable");
          var local = LocalBlueprints.scan(root, PrefabLibrary.INSTANCE.revision());
          h.assertTrue(local.entries().size() == 1 && local.entries().containsKey(LocalBlueprints.id(Path.of("独立蓝图.nbt"))), "Datapack NBT was imported twice as a loose blueprint");
          h.assertTrue(LocalBlueprints.accessible(player, f.id()) && !LocalBlueprints.allowed(player)
              && !LocalBlueprints.accessible(player, LocalBlueprints.id(Path.of("独立蓝图.nbt"))), "Dedicated access policy changed");
          h.assertTrue(LocalBlueprints.allowed(false, true) && !LocalBlueprints.allowed(false, false), "Singleplayer/LAN loose blueprint policy changed");
          Config.ENABLE_DEFAULT_TEST_BUILDINGS.set(false);
          revision[0] = PrefabLibrary.INSTANCE.revision();
          server.getCommands().getDispatcher().execute("datapack disable \"" + folderId + "\"", source);
        } else if (phase[0] == 1) {
          h.assertTrue(get("folderqa:house") == null && get("mobtowers:zombie_spawner_tower") == null, "Disable switch or datapack disable failed");
          present(h, DARK.toString(), "World override");
          revision[0] = PrefabLibrary.INSTANCE.revision();
          server.getCommands().getDispatcher().execute("prefab reload", source);
        } else if (phase[0] == 2) {
          h.assertTrue(get("folderqa:house") == null, "Prefab reload re-enabled a disabled pack");
          present(h, DARK.toString(), "World override");
          revision[0] = PrefabLibrary.INSTANCE.revision();
          server.getCommands().getDispatcher().execute("datapack enable \"" + folderId + "\"", source);
        } else if (phase[0] == 3) {
          present(h, "folderqa:house", "Zip1");
          zip(packed, blueprint, "Zip2", false);
          write(updated, definition("Directory2", "unpackedqa:blueprints/house.nbt"));
          Files.delete(override);
          revision[0] = PrefabLibrary.INSTANCE.revision();
          server.getCommands().getDispatcher().execute("prefab reload", source);
        } else if (phase[0] == 4) {
          present(h, "folderqa:house", "Zip2"); present(h, "unpackedqa:house", "Directory2"); present(h, DARK.toString(), "Folder override");
          h.assertTrue(get("folderqa:broken") == null && get("folderqa:missing") == null && get("folderqa:bad_nbt") == null, "Removed JSON entries remain");
          Files.delete(packed);
          revision[0] = PrefabLibrary.INSTANCE.revision();
          server.getCommands().getDispatcher().execute("prefab reload", source);
        } else if (phase[0] == 5) {
          h.assertTrue(get("folderqa:house") == null && get(DARK.toString()) == null, "Deleted ZIP or disabled default remains");
          present(h, "unpackedqa:house", "Directory2");
          Config.ENABLE_DEFAULT_TEST_BUILDINGS.set(true);
          revision[0] = PrefabLibrary.INSTANCE.revision();
          server.getCommands().getDispatcher().execute("prefab reload", source);
        } else if (phase[0] == 6) {
          present(h, DARK.toString(), "普通暗室刷怪塔"); present(h, "mobtowers:zombie_spawner_tower", "僵尸刷怪笼塔");
          var active = Sessions.class.getDeclaredField("ACTIVE"); active.setAccessible(true);
          var session = ((Map<?, ?>)active.get(null)).get(player.getUUID());
          h.assertTrue(session != null, "Reload discarded active placement");
          var selected = session.getClass().getDeclaredField("prefab"); selected.setAccessible(true);
          h.assertTrue(selected.get(session) == old, "Reload replaced the active prefab snapshot");
          Sessions.cancel(player);
          remove(unpacked); remove(world); Files.delete(loose);
          Config.ENABLE_DEFAULT_TEST_BUILDINGS.set(initial);
          revision[0] = PrefabLibrary.INSTANCE.revision();
          server.getCommands().getDispatcher().execute("prefab reload", source);
        } else {
          h.assertTrue(get("unpackedqa:house") == null && get("worldqa:house") == null && (get(DARK.toString()) != null) == initial, "Cleanup reload failed");
          PrefabDeploy.LOGGER.info("PREFAB BUILDING LIBRARY ZIP DIRECTORY UPDATE DELETE DISABLE OVERRIDE ACCESS SNAPSHOT VERIFIED");
          return;
        }
        phase[0]++;
      } catch (GameTestAssertException error) { throw error; }
      catch (Exception error) { throw new GameTestAssertException(error.toString()); }
      throw new GameTestAssertException("Waiting for next building library phase");
    });
  }
}
