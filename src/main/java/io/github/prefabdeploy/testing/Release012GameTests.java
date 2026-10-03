package io.github.prefabdeploy.testing;

import com.mojang.authlib.GameProfile;
import io.github.prefabdeploy.*;
import io.github.prefabdeploy.library.*;
import io.github.prefabdeploy.network.Network;
import io.github.prefabdeploy.server.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(PrefabDeploy.ID)
@PrefixGameTestTemplate(false)
public final class Release012GameTests {
  @GameTest(template="empty", batch="012_recipes", timeoutTicks=100)
  public static void recipes_match_registered_items_and_reload_requires_operator(GameTestHelper h) {
    var registry = h.getLevel().registryAccess();
    var recipes = h.getLevel().getRecipeManager();
    var tool = (net.minecraft.world.item.crafting.ShapedRecipe)recipes.byKey(ResourceLocation.parse("prefabdeploy:deployment_tool")).orElseThrow().value();
    var beacon = (net.minecraft.world.item.crafting.ShapedRecipe)recipes.byKey(ResourceLocation.parse("prefabdeploy:positioning_beacon")).orElseThrow().value();
    var empty = ItemStack.EMPTY;
    var input = net.minecraft.world.item.crafting.CraftingInput.of(3,3,List.of(
        new ItemStack(net.minecraft.world.item.Items.IRON_INGOT), new ItemStack(net.minecraft.world.item.Items.PAPER), new ItemStack(net.minecraft.world.item.Items.IRON_INGOT),
        empty, new ItemStack(net.minecraft.world.item.Items.COMPASS), empty, empty, new ItemStack(net.minecraft.world.item.Items.REDSTONE), empty));
    h.assertTrue(tool.matches(input,h.getLevel()) && tool.assemble(input,registry).is(PrefabDeploy.TOOL.get()), "Tool recipe does not craft deployment tool");
    var beaconInput = net.minecraft.world.item.crafting.CraftingInput.of(3,3,List.of(
        empty,new ItemStack(net.minecraft.world.item.Items.AMETHYST_SHARD),empty,
        new ItemStack(net.minecraft.world.item.Items.AMETHYST_SHARD),new ItemStack(net.minecraft.world.item.Items.GLOWSTONE),new ItemStack(net.minecraft.world.item.Items.AMETHYST_SHARD),
        empty,new ItemStack(net.minecraft.world.item.Items.REDSTONE),empty));
    h.assertTrue(beacon.matches(beaconInput,h.getLevel()) && beacon.assemble(beaconInput,registry).is(PrefabDeploy.BEACON_ITEM.get())
        && beacon.assemble(beaconInput,registry).getCount()==3, "Beacon recipe result mismatch");
    var lowPermission = h.getLevel().getServer().createCommandSourceStack().withPermission(1);
    h.assertTrue(h.getLevel().getServer().getCommands().getDispatcher().parse("prefab reload",lowPermission).getReader().canRead(), "Non-operator can reload");
    h.succeed();
  }

  @GameTest(template="empty", batch="012_geometry", timeoutTicks=100)
  public static void preview_frame_rays_and_import_policy(GameTestHelper h) {
    for (int turns = 0; turns < 4; turns++) {
      var t = new io.github.prefabdeploy.core.GridTransform(-100, 70, 200, turns, 4);
      var box = io.github.prefabdeploy.core.PreviewTarget.bounds(t, 9, 7, 5);
      var center = box.getCenter();
      h.assertTrue(box.minY == 66 && box.maxY == 73, "Basement reference mismatch");
      h.assertTrue(io.github.prefabdeploy.core.PreviewTarget.hit(box, center.add(0, 0, -20), center.add(0, 0, 20)), "Frame ray missed");
      h.assertTrue(io.github.prefabdeploy.core.PreviewTarget.hit(box, center, center.add(20, 0, 0)), "Interior ray missed");
      h.assertTrue(!io.github.prefabdeploy.core.PreviewTarget.hit(box, center.add(0, 20, -20), center.add(0, 20, 20)), "Outside ray hit");
      h.assertTrue(!io.github.prefabdeploy.core.PreviewTarget.hit(box, center.add(0, 0, -20), center.add(0, 0, -10)), "Out-of-range ray hit");
    }
    var box = io.github.prefabdeploy.core.PreviewTarget.bounds(new io.github.prefabdeploy.core.GridTransform(0,0,0,0,0), 8,6,4);
    h.assertTrue(io.github.prefabdeploy.core.PreviewTarget.hit(box, new net.minecraft.world.phys.Vec3(0,3,-5), new net.minecraft.world.phys.Vec3(0,3,1)), "Boundary ray missed");
    h.assertTrue(!io.github.prefabdeploy.core.PreviewTarget.hit(box, new net.minecraft.world.phys.Vec3(8.01,3,-5), new net.minecraft.world.phys.Vec3(8.01,3,1)), "Outside boundary hit");
    h.assertTrue(LocalBlueprints.allowed(false, true) && !LocalBlueprints.allowed(false,false)
        && !LocalBlueprints.allowed(true,true) && !LocalBlueprints.allowed(true,false), "Host policy mismatch");
    var a = LocalBlueprints.id(Path.of("村庄/住宅.nbt"));
    h.assertTrue(a.equals(LocalBlueprints.id(Path.of("村庄/住宅.nbt")))
        && !a.equals(LocalBlueprints.id(Path.of("城镇/住宅.nbt")))
        && !a.equals(LocalBlueprints.id(Path.of("村庄/住宅.litematic"))), "Local IDs collide");
    h.succeed();
  }

  private static byte[] fixture(GameTestHelper h, String file) throws Exception {
    try (var stream = h.getLevel().getServer().getResourceManager().getResourceOrThrow(
        ResourceLocation.parse("prefabdeploy:blueprints/" + file)).open()) {
      return stream.readAllBytes();
    }
  }

  @GameTest(template="empty", batch="012_local", timeoutTicks=100)
  public static void local_files_refresh_preserve_ids_and_reject_bad_files(GameTestHelper h) throws Exception {
    Path root = Files.createTempDirectory("prefabdeploy-local-test-");
    try {
      Files.createDirectories(root.resolve("村庄"));
      Files.createDirectories(root.resolve("城镇"));
      Files.write(root.resolve("村庄/住宅.nbt"), fixture(h, "cottage.nbt"));
      Files.write(root.resolve("城镇/住宅.NBT"), fixture(h, "nbt_gallery.nbt"));
      Files.write(root.resolve("地下室.litematic"), fixture(h, "cellar.litematic"));
      Files.write(root.resolve("损坏.nbt"), new byte[]{1,2,3});
      Files.write(root.resolve("忽略.schem"), new byte[]{1,2,3});
      var first = LocalBlueprints.scan(root, 1201);
      h.assertTrue(first.entries().size() == 4, "Unsupported extension was included or file lost");
      h.assertTrue(first.entries().values().stream().filter(Prefab::valid).count() == 3, "Both formats did not import");
      var key = LocalBlueprints.id(Path.of("村庄/住宅.nbt"));
      var old = first.entries().get(key);
      h.assertTrue(old.groundY() == 0 && old.metadata().getAsJsonObject("cost").get("mode").getAsString().equals("auto"), "Local defaults changed");
      var same = LocalBlueprints.scan(root, 1201);
      h.assertTrue(same.entries().get(key) == old, "Unchanged blueprint was parsed again");
      Files.write(root.resolve("村庄/住宅.nbt"), fixture(h, "nbt_gallery.nbt"));
      Files.delete(root.resolve("地下室.litematic"));
      var changed = LocalBlueprints.scan(root, 1201);
      h.assertTrue(changed.entries().size() == 3 && changed.entries().containsKey(key), "Delete or stable ID failed");
      h.assertTrue(!old.hash().equals(changed.entries().get(key).hash()) && old.blueprint().width() == 9, "Changed file mutated an existing snapshot");
      int limit = Config.MAX_BYTES.get();
      try {
        Config.MAX_BYTES.set(1024);
        Files.write(root.resolve("过大.nbt"), new byte[1025]);
        var limited = LocalBlueprints.scan(root, 1202);
        h.assertTrue(!limited.entries().get(LocalBlueprints.id(Path.of("过大.nbt"))).valid(), "Byte limit not enforced");
      } finally { Config.MAX_BYTES.set(limit); }
      h.succeed();
    } finally {
      try (var paths = Files.walk(root)) {
        for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
      }
    }
  }

  @GameTest(template="empty", batch="012_access", timeoutTicks=100)
  public static void dedicated_server_rejects_local_ids_and_beacons_repair(GameTestHelper h) {
    var p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "012_access"));
    p.getInventory().setItem(0, new ItemStack(PrefabDeploy.TOOL.get()));
    Sessions.cancel(p);
    h.assertTrue(!LocalBlueprints.allowed(p), "Dedicated server permits local imports");
    var request = Network.message("select");
    request.putString("id", "prefabdeploy_local:blocked");
    Sessions.receive(p, request);
    h.assertTrue(!Sessions.active(p), "Forged local ID created a session");
    var pos = h.absolutePos(new BlockPos(5, 4, 5));
    h.getLevel().setBlock(pos, PrefabDeploy.BEACON.get().defaultBlockState(), 3);
    h.assertTrue(h.getLevel().getBlockEntity(pos).getType() == PrefabDeploy.BEACON_VISUAL.get(), "New beacon has no visual entity");
    var chunk = h.getLevel().getChunkAt(pos);
    chunk.removeBlockEntity(pos);
    BeaconMigration.onChunkLoad(new ChunkEvent.Load(chunk, false));
    h.assertTrue(h.getLevel().getBlockEntity(pos).getType() == PrefabDeploy.BEACON_VISUAL.get(), "Legacy beacon was not repaired");
    h.succeed();
  }
}
