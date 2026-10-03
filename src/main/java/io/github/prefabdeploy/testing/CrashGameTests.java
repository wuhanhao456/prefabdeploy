package io.github.prefabdeploy.testing;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.github.prefabdeploy.blueprint.*;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.library.Prefab;
import io.github.prefabdeploy.server.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

/** Separate namespace: never runs unless explicitly selected by -PcrashMode. */
@GameTestHolder("prefabcrash")
@PrefixGameTestTemplate(false)
public final class CrashGameTests {
  private static final UUID OWNER =
      UUID.nameUUIDFromBytes(
          "prefab-crash-player".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  private static final TicketType<UUID> VERIFY =
      TicketType.create("prefab_crash_verify", Comparator.comparing(UUID::toString));

  @GameTest(template = "empty", templateNamespace = "prefabcrash", timeoutTicks = 120000)
  public static void hard_process_restart(GameTestHelper h) {
    var server = h.getLevel().getServer();
    var p = FakePlayerFactory.get(h.getLevel(), new GameProfile(OWNER, "prefab_crash"));
    var directory = server.getWorldPath(LevelResource.ROOT).resolve("prefabdeploy");
    var fixture = directory.resolve("crash-fixture.dat");
    if (System.getProperty("prefabdeploy.crashMode", "read").equals("write")) {
      var pos = h.absolutePos(new BlockPos(20, 3, 20));
      p.setPos(pos.getX() + 20, pos.getY() + 12, pos.getZ() + 20);
      p.getInventory().clearContent();
      p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
      h.getLevel().setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
      ((ChestBlockEntity) h.getLevel().getBlockEntity(pos))
          .setItem(0, new ItemStack(Items.EMERALD, 7));
      h.getLevel().setBlock(pos.east(), Blocks.DIRT.defaultBlockState(), 3);
      var barrel = new CompoundTag();
      barrel.putString("id", "minecraft:barrel");
      var item = new CompoundTag();
      item.putByte("Slot", (byte) 0);
      item.putString("id", "minecraft:emerald");
      item.putInt("count", 11);
      var items = new ListTag();
      items.add(item);
      barrel.put("Items", items);
      var actor = new CompoundTag();
      actor.putString("id", "minecraft:armor_stand");
      actor.putBoolean("NoGravity", true);
      var bp =
          new Blueprint(
              3,
              3,
              3,
              List.of(
                  new Blueprint.Voxel(BlockPos.ZERO, Blocks.BARREL.defaultBlockState(), barrel),
                  new Blueprint.Voxel(
                      new BlockPos(1, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), null)),
              List.of(new Blueprint.Actor(1.5, 0, 1.5, actor)),
              List.of());
      var f =
          new Prefab(
              ResourceLocation.parse("prefabdeploy:test_crash"),
              "Crash",
              "test",
              0,
              bp,
              JsonParser.parseString(
                      "{\"cost\":{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":2}]}}")
                  .getAsJsonObject(),
              "crash",
              "");
      var job =
          DeploymentManager.submit(
              p, f, new GridTransform(pos.getX(), pos.getY(), pos.getZ(), 0, 0), List.of(), false);
      String phase = System.getProperty("prefabdeploy.crashStage", "TICKS");
      System.setProperty("prefabdeploy.testHold." + job, phase);
      h.succeedWhen(
          () -> {
            h.assertTrue(
                DeploymentManager.status().stream()
                    .anyMatch(s -> s.contains(job + " " + phase + " ")),
                "Waiting for crash stage");
            try {
              if (phase.equals("COMMIT")) {
                var journal =
                    NbtIo.readCompressed(
                        new java.io.ByteArrayInputStream(
                            AtomicFile.read(
                                directory.resolve("transactions/" + job + ".journal"),
                                64 * 1024 * 1024)),
                        NbtAccounter.create(64L * 1024 * 1024));
                h.assertTrue(
                    journal.getString("durable").equals("COMMITTED"),
                    "Waiting for committed record");
              }
              var record = new CompoundTag();
              record.putUUID("job", job);
              record.putLong("pos", pos.asLong());
              record.putString("stage", phase);
              NbtIo.writeCompressed(record, fixture);
              io.github.prefabdeploy.PrefabDeploy.LOGGER.warn(
                  "PREFAB HARD CRASH READY: job={} stage={} pos={}", job, phase, pos);
              Runtime.getRuntime().halt(91);
            } catch (java.io.IOException ex) {
              throw new RuntimeException(ex);
            }
          });
    } else {
      try {
        var record = NbtIo.readCompressed(fixture, NbtAccounter.create(1024 * 1024));
        var job = record.getUUID("job");
        var pos = BlockPos.of(record.getLong("pos"));
        boolean committed = record.getString("stage").equals("COMMIT");
        var entityChunk = new ChunkPos(pos.offset(1, 0, 1));
        if (committed) {
          // The restored task releases its tickets on completion. Keep the actor's chunk
          // observable for this assertion; FakePlayers do not supply normal player tickets.
          h.getLevel().getChunkSource().addRegionTicket(VERIFY, entityChunk, 2, job);
          h.getLevel()
              .getChunkSource()
              .getChunk(entityChunk.x, entityChunk.z, ChunkStatus.FULL, true);
        }
        p.load(
            NbtIo.readCompressed(
                server.getWorldPath(LevelResource.ROOT).resolve("playerdata/" + OWNER + ".dat"),
                NbtAccounter.create(64L * 1024 * 1024)));
        p.setPos(pos.getX() + 20, pos.getY() + 12, pos.getZ() + 20);
        DeploymentManager.attachFakePlayerForTesting(job, p);
        h.succeedWhen(
            () -> {
              h.assertTrue(DeploymentManager.outcome(job).isPresent(), "Cold recovery incomplete");
              h.assertTrue(
                  DeploymentManager.outcome(job).get() == committed, "Wrong durable outcome");
              int diamonds =
                  p.getInventory().items.stream()
                      .filter(s -> s.is(Items.DIAMOND))
                      .mapToInt(ItemStack::getCount)
                      .sum();
              h.assertTrue(diamonds == (committed ? 3 : 5), "Cold restart payment incorrect");
              if (committed) {
                h.assertTrue(
                    h.getLevel().getBlockState(pos).is(Blocks.BARREL)
                        && h.getLevel().getBlockState(pos.east()).is(Blocks.GOLD_BLOCK),
                    "Committed terrain lost");
                h.assertTrue(
                    h.getLevel()
                            .getEntitiesOfClass(ArmorStand.class, new AABB(pos).inflate(4))
                            .size()
                        == 1,
                    "Committed entity missing or duplicated");
                Costs.commit(p, job);
              } else {
                h.assertTrue(
                    h.getLevel().getBlockState(pos).is(Blocks.CHEST)
                        && h.getLevel().getBlockState(pos.east()).is(Blocks.DIRT),
                    "Cold rollback terrain wrong");
                h.assertTrue(
                    ((ChestBlockEntity) h.getLevel().getBlockEntity(pos)).getItem(0).getCount()
                        == 7,
                    "Cold rollback inventory wrong");
                h.assertTrue(
                    h.getLevel()
                        .getEntitiesOfClass(ArmorStand.class, new AABB(pos).inflate(4))
                        .isEmpty(),
                    "Cold restart entity leaked");
                Costs.refund(p, job);
              }
              h.assertTrue(!RegionLocks.locked(h.getLevel(), pos), "Cold recovery kept its lock");
              if (committed)
                h.getLevel().getChunkSource().removeRegionTicket(VERIFY, entityChunk, 2, job);
            });
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
    }
  }
}
