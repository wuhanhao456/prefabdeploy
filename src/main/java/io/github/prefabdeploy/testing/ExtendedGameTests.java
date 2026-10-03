package io.github.prefabdeploy.testing;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import io.github.prefabdeploy.*;
import io.github.prefabdeploy.blueprint.*;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.library.*;
import io.github.prefabdeploy.server.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(PrefabDeploy.ID)
@PrefixGameTestTemplate(false)
public final class ExtendedGameTests {
  private static ServerPlayer player(GameTestHelper h, String name) {
    var p =
        FakePlayerFactory.get(
            h.getLevel(),
            new GameProfile(
                UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                name));
    p.getInventory().clearContent();
    var pos = h.absolutePos(new BlockPos(1, 12, 1));
    p.setPos(pos.getX(), pos.getY(), pos.getZ());
    p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
    return p;
  }

  private static Prefab building(String id, Blueprint bp, String cost) {
    return new Prefab(
        ResourceLocation.fromNamespaceAndPath("prefabdeploy", id),
        id,
        "test",
        0,
        bp,
        JsonParser.parseString("{\"visible\":true,\"unlock\":true,\"cost\":" + cost + "}")
            .getAsJsonObject(),
        id,
        "");
  }

  private static GridTransform at(BlockPos p, int turn, int ground) {
    return new GridTransform(p.getX(), p.getY(), p.getZ(), turn, ground);
  }

  private static int diamonds(ServerPlayer p) {
    return p.getInventory().items.stream()
        .filter(s -> s.is(Items.DIAMOND))
        .mapToInt(ItemStack::getCount)
        .sum();
  }

  private static Blueprint faultBlueprint() {
    var n = new CompoundTag();
    n.putString("id", "minecraft:barrel");
    var items = new ListTag();
    var item = new CompoundTag();
    item.putByte("Slot", (byte) 0);
    item.putString("id", "minecraft:emerald");
    item.putInt("count", 11);
    items.add(item);
    n.put("Items", items);
    var entity = new CompoundTag();
    entity.putString("id", "minecraft:armor_stand");
    entity.putBoolean("NoGravity", true);
    return new Blueprint(
        3,
        2,
        3,
        List.of(
            new Blueprint.Voxel(BlockPos.ZERO, Blocks.BARREL.defaultBlockState(), n),
            new Blueprint.Voxel(
                new BlockPos(1, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), null)),
        List.of(new Blueprint.Actor(1.5, 0, 1.5, entity)),
        List.of());
  }

  private static void original(GameTestHelper h, BlockPos pos) {
    h.getLevel().setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
    var chest = (ChestBlockEntity) h.getLevel().getBlockEntity(pos);
    chest.setItem(0, new ItemStack(Items.EMERALD, 7));
    chest.setChanged();
    h.getLevel().setBlock(pos.east(), Blocks.DIRT.defaultBlockState(), 3);
  }

  private static void restored(GameTestHelper h, BlockPos pos, ServerPlayer p, UUID id) {
    h.assertTrue(DeploymentManager.outcome(id).isPresent(), "Recovery not complete");
    h.assertTrue(!DeploymentManager.outcome(id).get(), "Failure became successful");
    h.assertTrue(
        h.getLevel().getBlockEntity(pos) instanceof ChestBlockEntity, "Original chest missing");
    var be = (ChestBlockEntity) h.getLevel().getBlockEntity(pos);
    h.assertTrue(
        be.getItem(0).is(Items.EMERALD) && be.getItem(0).getCount() == 7,
        "Original inventory corrupted");
    h.assertTrue(
        h.getLevel().getBlockState(pos.east()).is(Blocks.DIRT), "Original terrain missing");
    h.assertTrue(
        h.getLevel().getEntitiesOfClass(ArmorStand.class, new AABB(pos).inflate(4)).isEmpty(),
        "Generated entity leaked");
    h.assertTrue(diamonds(p) == 5, "Refund missing or duplicated");
  }

  @GameTest(template = "empty", timeoutTicks = 120000, batch = "fault_matrix")
  public static void every_failure_stage_is_recoverable(GameTestHelper h) {
    var p = player(h, "prefab_faults");
    var pos = h.absolutePos(new BlockPos(20, 3, 20));
    var sequence = h.startSequence();
    var job = new UUID[1];
    var f =
        building(
            "fault_matrix",
            faultBlueprint(),
            "{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":2}]}");
    for (String fault :
        List.of("after_payment", "block_1", "after_nbt", "after_entity", "after_save")) {
      sequence
          .thenExecute(
              () -> {
                original(h, pos);
                job[0] = DeploymentManager.submit(p, f, at(pos, 0, 0), List.of(), false);
                System.setProperty("prefabdeploy.testFault." + job[0], fault);
              })
          .thenWaitUntil(() -> restored(h, pos, p, job[0]))
          .thenExecute(
              () -> {
                System.clearProperty("prefabdeploy.testFault." + job[0]);
                Costs.refund(p, job[0]);
                h.assertTrue(diamonds(p) == 5, "Second recovery duplicated payment");
              });
    }
    sequence.thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 120000, batch = "journal_recovery")
  public static void reload_durable_journals_and_player_receipts(GameTestHelper h) {
    var p = player(h, "prefab_restart");
    var pos = h.absolutePos(new BlockPos(20, 3, 20));
    var sequence = h.startSequence();
    var job = new UUID[1];
    var f =
        building(
            "journal_recovery",
            faultBlueprint(),
            "{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":2}]}");
    for (String phase : List.of("BLOCKS", "NBT", "TICKS", "SAVE")) {
      sequence
          .thenExecute(
              () -> {
                original(h, pos);
                job[0] = DeploymentManager.submit(p, f, at(pos, 0, 0), List.of(), false);
                System.setProperty("prefabdeploy.testHold." + job[0], phase);
              })
          .thenWaitUntil(
              () ->
                  h.assertTrue(
                      DeploymentManager.status().stream()
                          .anyMatch(s -> s.contains(job[0] + " " + phase + " ")),
                      "Waiting for durable stage"))
          .thenExecute(
              () -> {
                System.clearProperty("prefabdeploy.testHold." + job[0]);
                DeploymentManager.stop();
                try {
                  p.load(
                      NbtIo.readCompressed(
                          p.server
                              .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                              .resolve("playerdata/" + p.getUUID() + ".dat"),
                          NbtAccounter.create(64L * 1024 * 1024)));
                } catch (Exception ex) {
                  throw new RuntimeException(ex);
                }
                DeploymentManager.start(p.server);
                DeploymentManager.attachFakePlayerForTesting(job[0], p);
              })
          .thenWaitUntil(() -> restored(h, pos, p, job[0]));
    }
    sequence.thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 120000, batch = "copy_rotation")
  public static void full_nbt_gallery_matches_all_four_rotations(GameTestHelper h) {
    var p = player(h, "prefab_gallery");
    var source = PrefabLibrary.INSTANCE.get(ResourceLocation.parse("prefabdeploy:nbt_gallery"));
    h.assertTrue(source != null && source.valid(), "Gallery missing");
    var meta = source.metadata().deepCopy();
    meta.add("cost", JsonParser.parseString("{\"mode\":\"free\"}"));
    var f = source.withMetadata(meta);
    var anchor = h.absolutePos(new BlockPos(24, 4, 24));
    var jobs = new UUID[1];
    var sequence = h.startSequence();
    for (int q = 0; q < 4; q++) {
      int turn = q;
      var t = at(anchor, q, f.groundY());
      sequence
          .thenExecute(() -> jobs[0] = DeploymentManager.submit(p, f, t, List.of(), false))
          .thenWaitUntil(
              () -> {
                h.assertTrue(
                    DeploymentManager.outcome(jobs[0]).orElse(false),
                    "Gallery deployment not complete");
                var chestPos = NbtTransforms.pos(t, new BlockPos(1, 1, 1));
                var be = h.getLevel().getBlockEntity(chestPos);
                h.assertTrue(be instanceof ChestBlockEntity, "Chest lost");
                var chest = (ChestBlockEntity) be;
                h.assertTrue(
                    chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 3,
                    "Chest inventory lost");
                var sign =
                    (SignBlockEntity)
                        h.getLevel().getBlockEntity(NbtTransforms.pos(t, new BlockPos(3, 1, 1)));
                h.assertTrue(
                    sign != null
                        && sign.getFrontText()
                            .getMessage(0, false)
                            .getString()
                            .equals("Prefab Deploy"),
                    "Sign text lost");
                var actors =
                    h.getLevel().getEntitiesOfClass(ArmorStand.class, new AABB(anchor).inflate(10));
                h.assertTrue(actors.size() == turn + 1, "Equipment entity count wrong");
                var point = t.point(3.5, 1, 3.5);
                h.assertTrue(
                    actors.stream()
                        .anyMatch(
                            e ->
                                e.position()
                                            .distanceToSqr(
                                                new net.minecraft.world.phys.Vec3(
                                                    point.x(), point.y(), point.z()))
                                        < .01
                                    && e.getItemBySlot(
                                            net.minecraft.world.entity.EquipmentSlot.HEAD)
                                        .is(Items.IRON_HELMET)),
                    "Entity transform or equipment wrong");
              });
    }
    sequence.thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void automatic_cost_counts_building_materials_only(GameTestHelper h) {
    var p = player(h, "prefab_material");
    var chest = new CompoundTag();
    chest.putString("id", "minecraft:chest");
    var item = new CompoundTag();
    item.putString("id", "minecraft:diamond");
    item.putInt("count", 64);
    var inventory = new ListTag();
    inventory.add(item);
    chest.put("Items", inventory);
    var voxels =
        List.of(
            new Blueprint.Voxel(
                BlockPos.ZERO,
                Blocks.OAK_DOOR
                    .defaultBlockState()
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER),
                null),
            new Blueprint.Voxel(
                new BlockPos(0, 1, 0),
                Blocks.OAK_DOOR
                    .defaultBlockState()
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                null),
            new Blueprint.Voxel(
                new BlockPos(1, 0, 0),
                Blocks.RED_BED
                    .defaultBlockState()
                    .setValue(BlockStateProperties.BED_PART, BedPart.FOOT),
                null),
            new Blueprint.Voxel(
                new BlockPos(2, 0, 0),
                Blocks.RED_BED
                    .defaultBlockState()
                    .setValue(BlockStateProperties.BED_PART, BedPart.HEAD),
                null),
            new Blueprint.Voxel(
                new BlockPos(1, 1, 0),
                Blocks.STONE_SLAB
                    .defaultBlockState()
                    .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE),
                null),
            new Blueprint.Voxel(new BlockPos(2, 1, 0), Blocks.CHEST.defaultBlockState(), chest));
    var f =
        building(
            "materials",
            new Blueprint(3, 2, 1, voxels, List.of(), List.of()),
            "{\"mode\":\"auto\"}");
    var quote = Costs.quoteUnchecked(p, f);
    var counts = new HashMap<String, Integer>();
    var list = quote.getList("items", Tag.TAG_COMPOUND);
    for (int i = 0; i < list.size(); i++)
      counts.put(list.getCompound(i).getString("id"), list.getCompound(i).getInt("count"));
    h.assertTrue(
        counts.get("minecraft:oak_door") == 1
            && counts.get("minecraft:red_bed") == 1
            && counts.get("minecraft:stone_slab") == 2
            && counts.get("minecraft:chest") == 1
            && !counts.containsKey("minecraft:diamond"),
        "Incorrect material bill");
    h.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void locks_stop_cross_chunk_hoppers_and_direct_writes(GameTestHelper h) {
    var level = h.getLevel();
    var origin = h.absolutePos(new BlockPos(16, 3, 20));
    int boundary = ((origin.getX() >> 4) + 1) * 16;
    var target = new BlockPos(boundary, origin.getY(), origin.getZ());
    var hopper = target.west();
    level.setBlock(target, Blocks.CHEST.defaultBlockState(), 3);
    level.setBlock(
        hopper,
        Blocks.HOPPER
            .defaultBlockState()
            .setValue(HopperBlock.FACING, net.minecraft.core.Direction.EAST),
        3);
    var source = (HopperBlockEntity) level.getBlockEntity(hopper);
    source.setItem(0, new ItemStack(Items.DIAMOND, 6));
    var id = UUID.randomUUID();
    h.assertTrue(
        RegionLocks.lock(level, Set.of(new ChunkPos(target).toLong()), id), "Fixture lock failed");
    h.assertTrue(
        !level.setBlock(target, Blocks.STONE.defaultBlockState(), 3), "Direct write bypassed lock");
    h.runAfterDelay(
        10,
        () -> {
          try {
            h.assertTrue(
                source.getItem(0).getCount() == 6, "Neighbor hopper removed inventory during lock");
            h.assertTrue(
                ((ChestBlockEntity) level.getBlockEntity(target)).isEmpty(),
                "Locked chest received items");
            h.succeed();
          } finally {
            RegionLocks.release(id);
          }
        });
  }

  @GameTest(template = "large", timeoutTicks = 600000, batch = "performance")
  public static void benchmark_10000_50000_100000_positions(GameTestHelper h) {
    var p = player(h, "prefab_benchmark");
    var pos = h.absolutePos(new BlockPos(10, 3, 10));
    var report = new JsonObject();
    report.addProperty("minecraft", "1.21.1");
    report.addProperty(
        "neoforge",
        net.neoforged.fml.ModList.get()
            .getModContainerById("neoforge")
            .orElseThrow()
            .getModInfo()
            .getVersion()
            .toString());
    report.addProperty("java", System.getProperty("java.version"));
    report.addProperty("os", System.getProperty("os.name"));
    report.addProperty("processors", Runtime.getRuntime().availableProcessors());
    report.addProperty("tick_budget_ms", Config.TICK_BUDGET_MS.get());
    report.addProperty("operations_per_tick", Config.MAX_OPERATIONS.get());
    report.addProperty("mode", "GameTestServer; scheduler time, not survival TPS");
    var runs = new JsonArray();
    report.add("runs", runs);
    var sequence = h.startSequence();
    var job = new UUID[1];
    var start = new long[1];
    for (int count : new int[] {10000, 50000, 100000}) {
      sequence
          .thenExecute(
              () -> {
                int width = 50, depth = count == 10000 ? 25 : 50, height = count / (width * depth);
                var voxels = new ArrayList<Blueprint.Voxel>(count);
                for (int y = 0; y < height; y++)
                  for (int z = 0; z < depth; z++)
                    for (int x = 0; x < width; x++) {
                      int i = (y * depth + z) * width + x;
                      voxels.add(
                          new Blueprint.Voxel(
                              new BlockPos(x, y, z),
                              i % 17 == 0
                                  ? Blocks.AIR.defaultBlockState()
                                  : i % 2 == 0
                                      ? Blocks.GLASS.defaultBlockState()
                                      : Blocks.STONE.defaultBlockState(),
                              null));
                    }
                var f =
                    building(
                        "benchmark_" + count,
                        new Blueprint(width, height, depth, voxels, List.of(), List.of()),
                        "{\"mode\":\"free\"}");
                DeploymentManager.resetMetrics();
                start[0] = System.nanoTime();
                job[0] = DeploymentManager.submit(p, f, at(pos, 0, 0), List.of(), false);
              })
          .thenWaitUntil(
              () ->
                  h.assertTrue(
                      DeploymentManager.outcome(job[0]).isPresent(), "Benchmark not finished"))
          .thenExecute(
              () -> {
                h.assertTrue(
                    DeploymentManager.outcome(job[0]).get(), "Benchmark deployment failed");
                var result = DeploymentManager.metricData();
                result.addProperty("positions", count);
                result.addProperty("elapsed_ms", (System.nanoTime() - start[0]) / 1e6);
                runs.add(result);
                PrefabDeploy.LOGGER.info("PREFAB BENCHMARK {}", result);
              });
    }
    sequence
        .thenExecute(
            () -> {
              try {
                var path =
                    Path.of(System.getProperty("prefabdeploy.reportDir", "reports"))
                        .resolve("server-benchmark.json");
                Files.createDirectories(path.getParent());
                Files.writeString(
                    path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            })
        .thenSucceed();
  }
}
