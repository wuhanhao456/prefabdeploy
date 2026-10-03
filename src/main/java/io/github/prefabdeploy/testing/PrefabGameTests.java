package io.github.prefabdeploy.testing;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.blueprint.*;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.library.*;
import io.github.prefabdeploy.server.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(PrefabDeploy.ID)
@PrefixGameTestTemplate(false)
public final class PrefabGameTests {
  private static ServerPlayer player(GameTestHelper h, String name) {
    var p =
        FakePlayerFactory.get(
            h.getLevel(),
            new GameProfile(
                UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                name));
    p.getInventory().clearContent();
    var spawn = h.absolutePos(new BlockPos(1, 10, 1));
    p.setPos(spawn.getX(), spawn.getY(), spawn.getZ());
    p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
    return p;
  }

  private static Prefab prefab(String name, List<Blueprint.Voxel> voxels) {
    var bp = new Blueprint(3, 2, 2, voxels, List.of(), List.of());
    return new Prefab(
        ResourceLocation.fromNamespaceAndPath("prefabdeploy", name),
        name,
        "test",
        0,
        bp,
        JsonParser.parseString(
                "{\"visible\":true,\"unlock\":true,\"cost\":{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":2}]}}")
            .getAsJsonObject(),
        "test",
        "");
  }

  private static GridTransform transform(BlockPos p) {
    return new GridTransform(p.getX(), p.getY(), p.getZ(), 0, 0);
  }

  private static int diamonds(ServerPlayer p) {
    int n = 0;
    for (var s : p.getInventory().items) if (s.is(Items.DIAMOND)) n += s.getCount();
    return n;
  }

  @GameTest(template = "empty", timeoutTicks = 60000)
  public static void deploy_and_charge_exactly_once(GameTestHelper h) {
    var p = player(h, "prefab_success");
    var pos = h.absolutePos(new BlockPos(20, 3, 20));
    h.getLevel().setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
    var f =
        prefab(
            "test_success",
            List.of(new Blueprint.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null)));
    var job = DeploymentManager.submit(p, f, transform(pos), List.of(), false);
    h.succeedWhen(
        () -> {
          h.assertTrue(DeploymentManager.outcome(job).orElse(false), "Job not complete");
          h.assertTrue(h.getLevel().getBlockState(pos).is(Blocks.STONE), "Block not deployed");
          h.assertTrue(diamonds(p) == 3, "Payment not exactly two diamonds");
          Costs.commit(p, job);
          h.assertTrue(diamonds(p) == 3, "Repeated settlement changed inventory");
        });
  }

  @GameTest(template = "empty", timeoutTicks = 60000)
  public static void protected_position_rejects_entire_building(GameTestHelper h) {
    var p = player(h, "prefab_protect");
    var pos = h.absolutePos(new BlockPos(20, 3, 20));
    h.getLevel().setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
    h.getLevel().setBlock(pos.east(), Blocks.BEDROCK.defaultBlockState(), 3);
    var f =
        prefab(
            "test_protect",
            List.of(
                new Blueprint.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null),
                new Blueprint.Voxel(
                    new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState(), null)));
    var job = DeploymentManager.submit(p, f, transform(pos), List.of(), false);
    h.succeedWhen(
        () -> {
          h.assertTrue(DeploymentManager.outcome(job).isPresent(), "Validation not complete");
          h.assertTrue(!DeploymentManager.outcome(job).get(), "Protected build succeeded");
          h.assertTrue(
              h.getLevel().getBlockState(pos).is(Blocks.DIRT),
              "Legal positions were partially modified");
          h.assertTrue(diamonds(p) == 5, "Rejected build charged payment");
        });
  }

  @GameTest(template = "empty", timeoutTicks = 60000)
  public static void fault_restores_inventory_and_block_entity(GameTestHelper h) {
    var p = player(h, "prefab_rollback");
    var pos = h.absolutePos(new BlockPos(20, 3, 20));
    h.getLevel().setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
    var chest = (ChestBlockEntity) h.getLevel().getBlockEntity(pos);
    chest.setItem(0, new ItemStack(Items.EMERALD, 7));
    chest.setChanged();
    h.getLevel().setBlock(pos.east(), Blocks.DIRT.defaultBlockState(), 3);
    var f =
        prefab(
            "test_rollback",
            List.of(
                new Blueprint.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null),
                new Blueprint.Voxel(
                    new BlockPos(1, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), null)));
    var job = DeploymentManager.submit(p, f, transform(pos), List.of(), false);
    System.setProperty("prefabdeploy.testFault." + job, "block_1");
    h.succeedWhen(
        () -> {
          h.assertTrue(DeploymentManager.outcome(job).isPresent(), "Recovery not complete");
          h.assertTrue(!DeploymentManager.outcome(job).get(), "Injected failure succeeded");
          h.assertTrue(
              h.getLevel().getBlockState(pos).is(Blocks.CHEST), "Original chest was not restored");
          var restored = (ChestBlockEntity) h.getLevel().getBlockEntity(pos);
          h.assertTrue(
              restored != null
                  && restored.getItem(0).is(Items.EMERALD)
                  && restored.getItem(0).getCount() == 7,
              "Chest contents were not restored");
          h.assertTrue(
              h.getLevel().getBlockState(pos.east()).is(Blocks.DIRT),
              "Original terrain was not restored");
          h.assertTrue(diamonds(p) == 5, "Payment was not refunded");
          Costs.refund(p, job);
          h.assertTrue(diamonds(p) == 5, "Repeated refund duplicated items");
          System.clearProperty("prefabdeploy.testFault." + job);
        });
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void bundled_imports_preserve_regions_and_nbt(GameTestHelper h) {
    var library = PrefabLibrary.INSTANCE;
    var cottage = library.get(ResourceLocation.parse("prefabdeploy:cottage"));
    var cellar = library.get(ResourceLocation.parse("prefabdeploy:cellar"));
    var gallery = library.get(ResourceLocation.parse("prefabdeploy:nbt_gallery"));
    h.assertTrue(cottage != null && cottage.valid(), "Cottage import failed");
    h.assertTrue(cellar != null && cellar.valid(), "Litematic import failed");
    h.assertTrue(
        cellar.blueprint().width() == 12 && cellar.blueprint().voxels().size() == 396,
        "Negative-size or region bounds incorrect");
    h.assertTrue(
        cellar.blueprint().voxels().stream()
            .noneMatch(v -> v.pos().getX() >= 7 && v.pos().getX() < 10),
        "Unselected gap became air modifications");
    h.assertTrue(
        gallery != null && gallery.valid() && gallery.blueprint().entities().size() == 1,
        "Full NBT gallery failed to import");
    h.assertTrue(
        gallery.blueprint().voxels().stream()
            .anyMatch(v -> v.nbt() != null && v.nbt().contains("Items")),
        "Inventory data lost during import");
    h.succeed();
  }
}
