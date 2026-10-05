package io.github.prefabdeploy.testing;

import static io.github.prefabdeploy.testing.ResourceGameTests.*;

import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.compat.*;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.item.ContainerBinding;
import io.github.prefabdeploy.server.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;

/** Each pair uses separate JVMs. No warm caches or live handlers survive the write run. */
@GameTestHolder("prefabboundcrash")
@PrefixGameTestTemplate(false)
public final class BoundContainerCrashGameTests {
  private static final TicketType<UUID> VERIFY = TicketType.create("prefab_bound_verify", Comparator.comparing(UUID::toString));

  @GameTest(template = "empty", templateNamespace = "prefabboundcrash", timeoutTicks = 12000)
  public static void bound_resources_cold_restart(GameTestHelper h) throws Exception {
    var p = player(h, "bound_crash");
    var root = p.server.getWorldPath(LevelResource.ROOT);
    var fixture = root.resolve("prefabdeploy/bound-crash-fixture.dat");
    String kind = System.getProperty("prefabdeploy.boundCrashKind");
    String stage = System.getProperty("prefabdeploy.boundCrashStage");
    if (System.getProperty("prefabdeploy.boundCrashMode").equals("write")) {
      if (kind.equals("ae2")) {
        var network = BoundContainerCompatGameTests.network(h, "bound_crash", false);
        h.runAfterDelay(80, () -> {
          try {
            BoundContainerCompatGameTests.put(network, new ItemStack(Items.DIAMOND), 5);
            BoundContainerCompatGameTests.bind(network);
            write(h, p, fixture, network.endpoint(), network.drive(), stage);
          } catch (Exception ex) { throw new RuntimeException(ex); }
        });
      } else {
        var source = h.absolutePos(new BlockPos(20, 3, 20));
        h.getLevel().setBlock(source, Blocks.CHEST.defaultBlockState(), 3);
        ((ChestBlockEntity) h.getLevel().getBlockEntity(source)).setItem(0, new ItemStack(Items.DIAMOND, 5));
        p.getInventory().setItem(0, new ItemStack(PrefabDeploy.TOOL.get())); p.getInventory().selected = 0;
        ContainerBinding.write(p.getMainHandItem(), BoundContainers.bind(p,
            new BlockHitResult(Vec3.atCenterOf(source), Direction.NORTH, source, false)));
        write(h, p, fixture, source, source, stage);
      }
    } else {
      var record = NbtIo.readCompressed(fixture, NbtAccounter.create(1024 * 1024));
      var job = record.getUUID("job"); var source = BlockPos.of(record.getLong("source"));
      var drive = BlockPos.of(record.getLong("drive")); var target = BlockPos.of(record.getLong("target"));
      p.load(NbtIo.readCompressed(root.resolve("playerdata/" + p.getUUID() + ".dat"), NbtAccounter.create(64L * 1024 * 1024)));
      for (var pos : new HashSet<>(List.of(source, target, drive))) {
        var chunk = new ChunkPos(pos); h.getLevel().getChunkSource().addRegionTicket(VERIFY, chunk, 2, job);
        h.getLevel().getChunkSource().getChunk(chunk.x, chunk.z, ChunkStatus.FULL, true);
      }
      p.setPos(target.getX() + 10, target.getY() + 12, target.getZ() + 10);
      DeploymentManager.attachFakePlayerForTesting(job, p);
      boolean committed = stage.contains("commit");
      h.runAfterDelay(100, () -> h.succeedWhen(() -> {
        try {
          if (kind.equals("ae2") && !committed) {
            h.assertTrue(DeploymentManager.status().stream().anyMatch(s -> s.contains(job + " HELD ")), "Uncertain AE2 restart did not retain task");
            var audit = Costs.auditReceipt(p, job).getList("externalReceipts", Tag.TAG_COMPOUND).getCompound(0);
            h.assertTrue(audit.getCompound("receipt").getString("state").equals("UNCERTAIN"), "Cold third-party evidence was treated as atomic");
            long before = BoundContainerCompatGameTests.cellStored(new BoundContainerCompatGameTests.Network(p, drive, source, drive), new ItemStack(Items.DIAMOND));
            for (int repeat = 0; repeat < 2; repeat++) {
              boolean rejected = false; try { Costs.refund(p, job); } catch (IllegalStateException ex) { rejected = true; }
              h.assertTrue(rejected, "Uncertain return was retried automatically");
            }
            h.assertTrue(before == BoundContainerCompatGameTests.cellStored(new BoundContainerCompatGameTests.Network(p, drive, source, drive), new ItemStack(Items.DIAMOND)), "Uncertain return duplicated ME materials");
            PrefabDeploy.LOGGER.info("BOUND COLD UNCERTAIN VERIFIED: {}", stage);
          } else {
            h.assertTrue(DeploymentManager.outcome(job).isPresent(), "Cold bound recovery incomplete");
            h.assertTrue(DeploymentManager.outcome(job).get() == committed, "Wrong bound durable outcome");
            for (int repeat = 0; repeat < 3; repeat++) { if (committed) Costs.commit(p, job); else Costs.refund(p, job); }
            long items = kind.equals("ae2")
                ? BoundContainerCompatGameTests.cellStored(new BoundContainerCompatGameTests.Network(p, drive, source, drive), new ItemStack(Items.DIAMOND))
                : ((ChestBlockEntity) h.getLevel().getBlockEntity(source)).getItem(0).getCount();
            h.assertTrue(items == (committed ? 1 : 5) && count(p, Items.DIAMOND) == 2, "Cold bound resources lost or duplicated");
            h.assertTrue(h.getLevel().getBlockState(target).is(committed ? Blocks.AIR : Blocks.STONE), "Cold bound terrain differs");
            h.assertTrue(!RegionLocks.locked(h.getLevel(), target), "Cold bound recovery retained lock");
            PrefabDeploy.LOGGER.info("BOUND COLD RECOVERY VERIFIED: {} {}", kind, stage);
          }
        } catch (Exception ex) { throw new RuntimeException(ex); }
      }));
    }
  }

  private static void write(GameTestHelper h, net.minecraft.server.level.ServerPlayer p, Path fixture,
      BlockPos source, BlockPos drive, String stage) throws Exception {
    p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 2));
    var target = source.offset(3, 0, 0);
    h.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 3);
    p.setPos(target.getX() + 10, target.getY() + 12, target.getZ() + 10);
    // Persist fixture inventories, identities and the empty ledger before any crash window.
    p.server.saveAllChunks(true, true, true); Costs.save(p);
    var job = DeploymentManager.submit(p, prefab("bound_crash", diamonds(4), 0, 0),
        new GridTransform(target.getX(), target.getY(), target.getZ(), 0, 0), List.of(), false);
    var record = new CompoundTag(); record.putUUID("job", job); record.putLong("source", source.asLong());
    record.putLong("drive", drive.asLong()); record.putLong("target", target.asLong());
    Files.createDirectories(fixture.getParent()); NbtIo.writeCompressed(record, fixture);
    if (stage.contains("refund")) System.setProperty("prefabdeploy.testFault." + job, "after_payment");
    MaterialPayments.testHook((id, point) -> {
      if (id.equals(job) && point.equals(stage)) {
        PrefabDeploy.LOGGER.warn("PREFAB HARD CRASH READY: job={} stage={}", job, stage);
        Runtime.getRuntime().halt(91);
      }
    });
    h.succeedWhen(() -> {
      h.assertTrue(DeploymentManager.outcome(job).isPresent(), "Waiting for crash point " + stage);
      throw new IllegalStateException("Crash point not reached: " + stage);
    });
  }
}
