package io.github.prefabdeploy.testing;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import io.github.prefabdeploy.*;
import io.github.prefabdeploy.blueprint.Blueprint;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.library.*;
import io.github.prefabdeploy.server.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(PrefabDeploy.ID)
@PrefixGameTestTemplate(false)
public final class SafetyGameTests {
  @GameTest(template = "empty", timeoutTicks = 100)
  public static void missing_inventory_mod_does_not_silently_empty_saved_stacks(GameTestHelper h) {
    var item = new net.minecraft.nbt.CompoundTag();
    item.putString("id", "missing_mod:stored_item");
    item.putInt("count", 1);
    var items = new net.minecraft.nbt.ListTag();
    items.add(item);
    var chest = new net.minecraft.nbt.CompoundTag();
    chest.putString("id", "minecraft:chest");
    chest.put("Items", items);
    boolean rejected = false;
    try {
      io.github.prefabdeploy.blueprint.NbtContentCheck.validate(chest);
    } catch (IllegalArgumentException ex) {
      rejected = true;
    }
    h.assertTrue(rejected, "Unknown inventory item would silently disappear");
    h.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void corrupt_states_and_conflicting_regions_fail_closed(GameTestHelper h) {
    var unknown = new net.minecraft.nbt.CompoundTag();
    unknown.putString("Name", "missing_mod:missing_block");
    boolean rejected = false;
    try {
      io.github.prefabdeploy.blueprint.StateCodec.read(unknown);
    } catch (IllegalArgumentException ex) {
      rejected = true;
    }
    h.assertTrue(rejected, "Unknown block became air");
    var regions = new net.minecraft.nbt.CompoundTag();
    for (int i = 0; i < 2; i++) {
      var r = new net.minecraft.nbt.CompoundTag();
      var position = new net.minecraft.nbt.CompoundTag();
      position.putInt("x", 0);
      position.putInt("y", 0);
      position.putInt("z", 0);
      var size = new net.minecraft.nbt.CompoundTag();
      size.putInt("x", 1);
      size.putInt("y", 1);
      size.putInt("z", 1);
      r.put("Position", position);
      r.put("Size", size);
      var palette = new net.minecraft.nbt.ListTag();
      palette.add(
          io.github.prefabdeploy.blueprint.StateCodec.write(
              i == 0 ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState()));
      r.put("BlockStatePalette", palette);
      r.putLongArray("BlockStates", new long[] {0});
      regions.put("region" + i, r);
    }
    var root = new net.minecraft.nbt.CompoundTag();
    root.putInt("Version", 6);
    root.put("Regions", regions);
    rejected = false;
    try {
      new io.github.prefabdeploy.blueprint.LitematicImporter().read(root, 100);
    } catch (IllegalArgumentException ex) {
      rejected = true;
    }
    h.assertTrue(rejected, "Conflicting regions were silently merged");
    var corrupt = root.copy();
    corrupt.getCompound("Regions").getCompound("region0").putLongArray("BlockStates", new long[0]);
    rejected = false;
    try {
      new io.github.prefabdeploy.blueprint.LitematicImporter().read(corrupt, 100);
    } catch (IllegalArgumentException ex) {
      rejected = true;
    }
    h.assertTrue(rejected, "Truncated block data was accepted");
    h.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 60000)
  public static void insufficient_cost_and_missing_nested_rule_never_modify_world(
      GameTestHelper h) {
    var p = player(h, "prefab_no_funds");
    p.getInventory().clearContent();
    var bp =
        new Blueprint(
            1,
            1,
            1,
            List.of(new Blueprint.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null)),
            List.of(),
            List.of());
    var f =
        prefab(
            "no_funds",
            bp,
            "{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":2}]}");
    for (String rule :
        List.of(
            "{\"type\":\"not\",\"rule\":{\"type\":\"script\",\"id\":\"missing:rule\"}}",
            "{\"type\":\"any\",\"rules\":[true,{\"type\":\"script\",\"id\":\"missing:rule\"}]}")) {
      h.assertTrue(
          !Rules.test(JsonParser.parseString(rule), p, f, p.blockPosition()).isEmpty(),
          "Nested rule bypassed a missing dependency");
    }
    var pos = h.absolutePos(new BlockPos(20, 3, 20));
    h.getLevel().setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
    var id = DeploymentManager.submit(p, f, at(pos), List.of(), false);
    h.succeedWhen(
        () -> {
          h.assertTrue(
              DeploymentManager.outcome(id).isPresent(), "Insufficient-cost validation incomplete");
          h.assertTrue(
              !DeploymentManager.outcome(id).get()
                  && h.getLevel().getBlockState(pos).is(Blocks.DIRT),
              "Insufficient funds modified terrain");
        });
  }

  @GameTest(template = "empty", timeoutTicks = 60000)
  public static void ungenerated_chunk_is_rejected_without_generation(GameTestHelper h) {
    var p = player(h, "prefab_unexplored");
    var pos = h.absolutePos(new BlockPos(4096, 20, 4096));
    var cp = new net.minecraft.world.level.ChunkPos(pos);
    h.assertTrue(
        h.getLevel().getChunkSource().getChunkNow(cp.x, cp.z) == null,
        "Unexplored fixture already loaded");
    var f =
        prefab(
            "unexplored",
            new Blueprint(
                1,
                1,
                1,
                List.of(new Blueprint.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null)),
                List.of(),
                List.of()),
            "{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":2}]}");
    var id = DeploymentManager.submit(p, f, at(pos), List.of(), false);
    h.succeedWhen(
        () -> {
          h.assertTrue(
              DeploymentManager.outcome(id).isPresent(), "Unexplored validation incomplete");
          h.assertTrue(
              !DeploymentManager.outcome(id).get()
                  && diamonds(p) == 5
                  && h.getLevel().getChunkSource().getChunkNow(cp.x, cp.z) == null,
              "Deployment generated an unexplored chunk or charged cost");
        });
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void active_cost_provider_is_pinned_across_registry_reload(GameTestHelper h) {
    var p = player(h, "prefab_cost_reload");
    String key = "test:pinned";
    var calls = new int[2];
    var original =
        new io.github.prefabdeploy.api.CostProvider() {
          public boolean atomicWithPlayerSave() {
            return true;
          }

          public net.minecraft.nbt.CompoundTag quote(ServerPlayer player, JsonObject spec) {
            return new net.minecraft.nbt.CompoundTag();
          }

          public void reserve(ServerPlayer player, UUID id, net.minecraft.nbt.CompoundTag q) {
            calls[0]++;
          }

          public void refund(ServerPlayer player, UUID id, net.minecraft.nbt.CompoundTag q) {
            calls[1]++;
          }
        };
    var replacement =
        new io.github.prefabdeploy.api.CostProvider() {
          public boolean atomicWithPlayerSave() {
            return true;
          }

          public net.minecraft.nbt.CompoundTag quote(ServerPlayer player, JsonObject spec) {
            throw new IllegalStateException("Wrong provider version");
          }

          public void reserve(ServerPlayer player, UUID id, net.minecraft.nbt.CompoundTag q) {
            throw new IllegalStateException("Wrong provider version");
          }

          public void refund(ServerPlayer player, UUID id, net.minecraft.nbt.CompoundTag q) {
            throw new IllegalStateException("Wrong provider version");
          }
        };
    var id = UUID.randomUUID();
    try {
      io.github.prefabdeploy.api.PrefabApi.COSTS.put(key, original);
      var f =
          prefab(
              "cost_reload",
              new Blueprint(
                  1,
                  1,
                  1,
                  List.of(
                      new Blueprint.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null)),
                  List.of(),
                  List.of()),
              "{\"mode\":\"manual\",\"custom\":[{\"provider\":\"test:pinned\"}]}");
      Costs.reserve(p, id, Costs.quote(p, f));
      io.github.prefabdeploy.api.PrefabApi.COSTS.put(key, replacement);
      Costs.refund(p, id);
      Costs.refund(p, id);
      h.assertTrue(
          calls[0] == 1 && calls[1] == 1, "Registry reload changed active transaction callbacks");
      h.succeed();
    } finally {
      Costs.unpin(id);
      io.github.prefabdeploy.api.PrefabApi.COSTS.remove(key);
    }
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void painting_leash_motion_and_uuid_use_consistent_transform(GameTestHelper h) {
    var n = new net.minecraft.nbt.CompoundTag();
    n.putString("id", "minecraft:painting");
    var old = UUID.randomUUID();
    var fresh = UUID.randomUUID();
    n.putUUID("UUID", old);
    n.put("Pos", NbtTransforms.doubles(101.5, 50, 201.5));
    n.put("Motion", NbtTransforms.doubles(1, 0, 0));
    n.putByte("facing", (byte) 2);
    n.putInt("TileX", 100);
    n.putInt("TileY", 50);
    n.putInt("TileZ", 200);
    n.putIntArray("leash", new int[] {102, 50, 201});
    var a = new Blueprint.Actor(1.5, 0, 1.5, n);
    var t = new GridTransform(20, 70, 30, 1, 0);
    var transformed = NbtTransforms.entity(a, t, fresh, Map.of(old, fresh), UUID.randomUUID());
    h.assertTrue(
        transformed.getUUID("UUID").equals(fresh) && transformed.getByte("facing") == 3,
        "Entity UUID or painting direction was not rotated");
    h.assertTrue(
        transformed.getInt("TileX") == 19
            && transformed.getInt("TileY") == 70
            && transformed.getInt("TileZ") == 30
            && Arrays.equals(transformed.getIntArray("leash"), new int[] {18, 70, 32}),
        "Attachment or 1.21 leash coordinates wrong");
    h.assertTrue(
        Math.abs(transformed.getList("Motion", 6).getDouble(2) - 1) < 1e-8,
        "Motion did not rotate");
    h.succeed();
  }

  private static ServerPlayer player(GameTestHelper h, String name) {
    var p =
        FakePlayerFactory.get(
            h.getLevel(),
            new GameProfile(
                UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                name));
    p.getInventory().clearContent();
    p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
    p.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(1, 12, 1)));
    return p;
  }

  private static Prefab prefab(String id, Blueprint bp, String cost) {
    return new Prefab(
        ResourceLocation.parse("prefabdeploy:" + id),
        id,
        "test",
        0,
        bp,
        JsonParser.parseString("{\"cost\":" + cost + "}").getAsJsonObject(),
        id,
        "");
  }

  private static GridTransform at(BlockPos pos) {
    return new GridTransform(pos.getX(), pos.getY(), pos.getZ(), 0, 0);
  }

  private static int diamonds(ServerPlayer p) {
    return p.getInventory().items.stream()
        .filter(s -> s.is(Items.DIAMOND))
        .mapToInt(ItemStack::getCount)
        .sum();
  }

  @GameTest(template = "empty", timeoutTicks = 60000)
  public static void tick_only_target_is_also_validated(GameTestHelper h) {
    var p = player(h, "prefab_tick_permission");
    var pos = h.absolutePos(new BlockPos(20, 3, 20));
    h.getLevel().setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
    h.getLevel().setBlock(pos.east(), Blocks.BEDROCK.defaultBlockState(), 3);
    var bp =
        new Blueprint(
            2,
            1,
            1,
            List.of(new Blueprint.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null)),
            List.of(),
            List.of(new Blueprint.Tick(new BlockPos(1, 0, 0), "minecraft:stone", false, 20, 0)));
    var f =
        prefab(
            "tick_permission",
            bp,
            "{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":2}]}");
    var job = DeploymentManager.submit(p, f, at(pos), List.of(), false);
    h.succeedWhen(
        () -> {
          h.assertTrue(DeploymentManager.outcome(job).isPresent(), "Tick validation incomplete");
          h.assertTrue(
              !DeploymentManager.outcome(job).get()
                  && h.getLevel().getBlockState(pos).is(Blocks.DIRT)
                  && diamonds(p) == 5,
              "Tick-only protected position bypassed validation");
        });
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void full_inventory_refund_is_persistent_and_claimable(GameTestHelper h) {
    var p = player(h, "prefab_mail");
    p.giveExperiencePoints(100 - p.totalExperience);
    var bp =
        new Blueprint(
            1,
            1,
            1,
            List.of(new Blueprint.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null)),
            List.of(),
            List.of());
    var f =
        prefab(
            "mail",
            bp,
            "{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":2}],\"xp\":30}");
    var id = UUID.randomUUID();
    Costs.reserve(p, id, Costs.quote(p, f));
    for (int i = 0; i < p.getInventory().getContainerSize(); i++)
      p.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
    Costs.refund(p, id);
    h.assertTrue(
        p.totalExperience == 100 && Costs.auditReceipt(p, id).getList("escrow", 10).size() == 1,
        "Full inventory refund was lost");
    Costs.claim(p);
    h.assertTrue(diamonds(p) == 0, "Refund dropped or bypassed a full inventory");
    p.getInventory().setItem(1, ItemStack.EMPTY);
    Costs.claim(p);
    Costs.claim(p);
    h.assertTrue(
        diamonds(p) == 2 && Costs.auditReceipt(p, id).getList("escrow", 10).isEmpty(),
        "Repeated claim duplicated or lost refund");
    h.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 60000)
  public static void beacons_return_once_after_success(GameTestHelper h) {
    var p = player(h, "prefab_beacons");
    var pos = h.absolutePos(new BlockPos(20, 3, 20));
    var t = at(pos);
    var b = t.cornerB(8);
    var c = t.cornerC(5);
    var markers =
        List.of(pos, new BlockPos(b.x(), b.y(), b.z()), new BlockPos(c.x(), c.y(), c.z()));
    for (var marker : markers)
      h.getLevel().setBlock(marker, PrefabDeploy.BEACON.get().defaultBlockState(), 3);
    var f =
        prefab(
            "beacon_return",
            new Blueprint(
                8,
                2,
                5,
                List.of(
                    new Blueprint.Voxel(
                        new BlockPos(1, 0, 1), Blocks.STONE.defaultBlockState(), null)),
                List.of(),
                List.of()),
            "{\"mode\":\"free\"}");
    var job = DeploymentManager.submit(p, f, t, markers, false);
    h.succeedWhen(
        () -> {
          h.assertTrue(
              DeploymentManager.outcome(job).orElse(false), "Beacon deployment incomplete");
          int returned =
              p.getInventory().items.stream()
                  .filter(s -> s.is(PrefabDeploy.BEACON_ITEM.get()))
                  .mapToInt(ItemStack::getCount)
                  .sum();
          h.assertTrue(
              returned == 3
                  && markers.stream().allMatch(m -> h.getLevel().getBlockState(m).isAir()),
              "Real beacons were not recovered exactly once");
          Costs.commit(p, job);
          h.assertTrue(returned == 3, "Repeated commit changed beacon count");
        });
  }

  @GameTest(template = "wide", timeoutTicks = 600000, batch = "multiplayer")
  public static void concurrent_builds_share_global_budget_and_overlap_is_rejected(
      GameTestHelper h) {
    var a = player(h, "prefab_multi_a");
    var b = player(h, "prefab_multi_b");
    var overlap = player(h, "prefab_multi_overlap");
    var voxels = new ArrayList<Blueprint.Voxel>(50000);
    for (int y = 0; y < 20; y++)
      for (int z = 0; z < 50; z++)
        for (int x = 0; x < 50; x++)
          voxels.add(
              new Blueprint.Voxel(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), null));
    var f =
        prefab(
            "multiplayer",
            new Blueprint(50, 20, 50, voxels, List.of(), List.of()),
            "{\"mode\":\"free\"}");
    var first = h.absolutePos(new BlockPos(10, 3, 10));
    var second = h.absolutePos(new BlockPos(106, 3, 10));
    DeploymentManager.resetMetrics();
    var one = DeploymentManager.submit(a, f, at(first), List.of(), false);
    var two = DeploymentManager.submit(b, f, at(second), List.of(), false);
    var rejected = new UUID[1];
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    RegionLocks.locked(h.getLevel(), first), "Waiting for first deployment lock"))
        .thenExecute(
            () -> rejected[0] = DeploymentManager.submit(overlap, f, at(first), List.of(), false))
        .thenWaitUntil(
            () -> {
              h.assertTrue(
                  DeploymentManager.outcome(one).orElse(false)
                      && DeploymentManager.outcome(two).orElse(false)
                      && DeploymentManager.outcome(rejected[0]).isPresent()
                      && !Sessions.hasUpdates(),
                  "Concurrent builds incomplete: " + DeploymentManager.status());
              h.assertTrue(
                  !DeploymentManager.outcome(rejected[0]).get() && diamonds(overlap) == 5,
                  "Overlapping deployment was not rejected");
              var result = DeploymentManager.metricData();
              h.assertTrue(
                  result.get("max_job_operations_per_tick").getAsInt()
                          <= Config.MAX_OPERATIONS.get()
                      && result.get("peak_active_jobs").getAsInt() >= 2,
                  "Global scheduler budget violated");
              try {
                var path =
                    java.nio.file.Path.of(
                        System.getProperty("prefabdeploy.reportDir", "reports"),
                        "multiplayer-benchmark.json");
                java.nio.file.Files.createDirectories(path.getParent());
                java.nio.file.Files.writeString(
                    path, new GsonBuilder().setPrettyPrinting().create().toJson(result));
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            })
        .thenSucceed();
  }
}
