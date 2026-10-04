package io.github.prefabdeploy.testing;

import static io.github.prefabdeploy.testing.ResourceGameTests.*;

import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.compat.ResourceSources;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.server.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.*;

/** Every write run kills the JVM; the read run uses only cold SavedData and player/journal files. */
@GameTestHolder("prefabresourcecrash")
@PrefixGameTestTemplate(false)
public final class ResourceCrashGameTests {
  @GameTest(template = "empty", templateNamespace = "prefabresourcecrash", timeoutTicks = 120000)
  public static void external_resources_hard_restart(GameTestHelper h) throws Exception {
    var p = player(h, "resource_crash");
    var root = p.server.getWorldPath(LevelResource.ROOT);
    var fixture = root.resolve("prefabdeploy/resource-crash-fixture.dat");
    String stage = System.getProperty("prefabdeploy.resourceCrashStage");
    boolean committed = stage.contains("commit");
    if (System.getProperty("prefabdeploy.resourceCrashMode").equals("write")) {
      var bag = bag(p, new ItemStack(Items.DIAMOND, 3));
      var linked = bag(p, new ItemStack(Items.DIAMOND, 4));
      var adapter = Class.forName(SB + "backpack.wrapper.BackpackLinkedStorageEndpointAdapter").getConstructor().newInstance();
      var descriptor = call(adapter, "createHostDescriptor", h.getLevel(), linked);
      var contents = call(adapter, "copyCanonicalContents", h.getLevel(), linked);
      var saved = call(type(SC + "linkedstorage.LinkedStorageGroupsSavedData"), "get", h.getLevel());
      UUID endpointId = UUID.randomUUID();
      UUID group = (UUID) call(call(saved, "manager"), "createGroup", p.getUUID(), endpointId, descriptor, contents);
      var endpoint = Class.forName(SC + "linkedstorage.LinkedStorageEndpointData").getConstructor(UUID.class, UUID.class);
      call(adapter, "bindEndpoint", h.getLevel(), linked, endpoint.newInstance(group, endpointId));
      p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
      p.getInventory().setItem(1, bag); p.getInventory().setItem(2, linked);
      var net = net(p); put(net, new ItemStack(Items.DIAMOND), 5);
      put(net, new FluidStack(Fluids.LAVA, 1), 1000);
      var sources = new ListTag();
      for (var source : ResourceSources.backpacks(p)) {
        ResourceReceiptStorage.save(source.data, source.path, p.registryAccess());
        sources.add(source.identity.copy());
      }
      var source = ResourceSources.network(p);
      ResourceReceiptStorage.save(source.data, source.path, p.registryAccess());
      sources.add(source.identity.copy());
      Costs.save(p);
      var pos = h.absolutePos(new BlockPos(20, 3, 20));
      p.setPos(pos.getX() + 20, pos.getY() + 12, pos.getZ() + 20);
      h.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
      var f = prefab("crash_resources", "{\"mode\":\"combined\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":12}]}", 0, 1);
      var job = DeploymentManager.submit(p, f,
          new GridTransform(pos.getX(), pos.getY(), pos.getZ(), 0, 0), List.of(), false);
      var record = new CompoundTag(); record.putUUID("job", job); record.putUUID("owner", p.getUUID());
      record.putLong("pos", pos.asLong()); record.putString("stage", stage); record.put("sources", sources);
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
        throw new IllegalStateException("Crash point was never reached: " + stage);
      });
    } else {
      var record = NbtIo.readCompressed(fixture, NbtAccounter.create(1024 * 1024));
      var job = record.getUUID("job"); var pos = BlockPos.of(record.getLong("pos"));
      p.load(NbtIo.readCompressed(root.resolve("playerdata/" + record.getUUID("owner") + ".dat"),
          NbtAccounter.create(64L * 1024 * 1024)));
      p.setPos(pos.getX() + 20, pos.getY() + 12, pos.getZ() + 20);
      DeploymentManager.attachFakePlayerForTesting(job, p);
      h.succeedWhen(() -> {
        h.assertTrue(DeploymentManager.outcome(job).isPresent(), "Cold resource recovery incomplete");
        h.assertTrue(DeploymentManager.outcome(job).get() == committed, "Wrong durable resource outcome");
        try {
          for (int repeat = 0; repeat < 3; repeat++) {
            if (committed) Costs.commit(p, job); else Costs.refund(p, job);
          }
          long total = count(p, Items.DIAMOND), lava = 0;
          for (var tag : record.getList("sources", Tag.TAG_COMPOUND)) {
            var source = ResourceSources.resolve(p, (CompoundTag) tag);
            ResourceReceiptStorage.verify(source.data, source.path);
            long expected = switch (source.identity.getString("kind")) {
              case "backpack" -> committed ? 0 : 3;
              case "linked_backpack" -> committed ? 0 : 4;
              default -> committed ? 2 : 5;
            };
            long diamonds = 0;
            for (var entry : source.entries()) {
              if (entry.resource().contains("fluid")) lava += entry.available();
              else if (ResourceSources.stack(p, entry.resource()).is(Items.DIAMOND)) diamonds += entry.available();
            }
            h.assertTrue(diamonds == expected, "Original source refund differs: " + source.key());
            total += diamonds;
          }
          h.assertTrue(total == (committed ? 2 : 14) && lava == (committed ? 0 : 1000), "Cold recovery lost/duplicated resources");
          h.assertTrue(h.getLevel().getBlockState(pos).is(committed ? Blocks.LAVA : Blocks.STONE), "Cold resource terrain differs");
          h.assertTrue(!RegionLocks.locked(h.getLevel(), pos), "Cold resource recovery retained lock");
        } catch (Exception ex) { throw new RuntimeException(ex); }
      });
    }
  }
}
