package io.github.prefabdeploy.testing;

import static io.github.prefabdeploy.testing.ResourceGameTests.*;

import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.compat.*;
import io.github.prefabdeploy.item.ContainerBinding;
import io.github.prefabdeploy.server.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

@GameTestHolder("prefabresources")
@PrefixGameTestTemplate(false)
public final class BoundContainerCompatGameTests {
  record Network(ServerPlayer player, BlockPos drive, BlockPos endpoint, BlockPos energy) {}
  private static Object actionable() throws Exception {
    return Class.forName("appeng.api.config.Actionable").getField("MODULATE").get(null);
  }
  private static Object action(ServerPlayer player) throws Exception {
    return call(type("appeng.api.networking.security.IActionSource"), "ofPlayer", player);
  }
  private static Object grid(Network fixture) throws Exception {
    return call(call(call(fixture.player.serverLevel().getBlockEntity(fixture.drive), "getMainNode"), "getNode"), "getGrid");
  }
  private static Object storage(Network fixture) throws Exception { return call(call(grid(fixture), "getStorageService"), "getInventory"); }
  private static Object itemKey(ItemStack stack) throws Exception { return call(type("appeng.api.stacks.AEItemKey"), "of", stack); }
  private static long stored(Network fixture, ItemStack stack) throws Exception {
    return ((Number) call(call(storage(fixture), "getAvailableStacks"), "get", itemKey(stack))).longValue();
  }
  static long cellStored(Network fixture, ItemStack stack) throws Exception {
    var cell = call(fixture.player.serverLevel().getBlockEntity(fixture.drive), "getOriginalCellInventory", 0);
    return ((Number) call(call(cell, "getAvailableStacks"), "get", itemKey(stack))).longValue();
  }
  static void put(Network fixture, ItemStack stack, long count) throws Exception {
    long inserted = ((Number) call(storage(fixture), "insert", itemKey(stack), count, actionable(), action(fixture.player))).longValue();
    if (inserted != count) throw new AssertionError("AE2 fixture insertion failed");
  }
  static Network network(GameTestHelper h, String name, boolean terminal) throws Exception {
    var player = player(h, name);
    var drive = h.absolutePos(new BlockPos(2, 1, 2)); var energy = drive.relative(Direction.EAST); var endpoint = drive.relative(Direction.SOUTH);
    h.getLevel().setBlock(drive, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("ae2:drive")).defaultBlockState(), 3);
    var driveEntity = h.getLevel().getBlockEntity(drive);
    call(call(driveEntity, "getInternalInventory"), "setItemDirect", 0,
        new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:item_storage_cell_1k"))));
    h.getLevel().setBlock(energy, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("ae2:energy_cell")).defaultBlockState(), 3);
    call(h.getLevel().getBlockEntity(energy), "injectAEPower", 200000D, actionable());
    h.getLevel().setBlock(endpoint, BuiltInRegistries.BLOCK.get(ResourceLocation.parse(terminal ? "ae2:cable_bus" : "ae2:interface")).defaultBlockState(), 3);
    if (terminal) {
      call(h.getLevel().getBlockEntity(endpoint), "addPart",
          BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:fluix_glass_cable")), null, player);
      call(h.getLevel().getBlockEntity(endpoint), "addPart",
          BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:terminal")), Direction.SOUTH, player);
    }
    for (var pos : List.of(drive, energy)) call(call(h.getLevel().getBlockEntity(pos), "getMainNode"), "setOwningPlayer", player);
    player.getInventory().setItem(0, new ItemStack(PrefabDeploy.TOOL.get())); player.getInventory().selected = 0;
    return new Network(player, drive, endpoint, energy);
  }
  static CompoundTag bind(Network fixture) throws Exception {
    var pos = fixture.endpoint;
    var p = fixture.player;
    var target = new Vec3(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .8125);
    p.setPos(pos.getX() + .5, pos.getY() + .5 - p.getEyeHeight(), pos.getZ() + 2.5);
    p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target); p.setShiftKeyDown(true);
    try {
      p.gameMode.useItemOn(p, p.serverLevel(), p.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND,
          new BlockHitResult(target, Direction.SOUTH, pos, false));
    } finally { p.setShiftKeyDown(false); }
    var binding = ContainerBinding.read(p.getMainHandItem());
    if (binding.isEmpty()) throw new IllegalStateException("Real AE2 Shift block interaction did not bind the tool");
    return binding;
  }
  private static boolean optional(GameTestHelper h, String mod) {
    if (OptionalMods.loaded(mod)) return true;
    PrefabDeploy.LOGGER.info("BOUND CONTAINER TEST SKIPPED: {} is absent", mod);
    h.succeed(); return false;
  }
  private static void refuses(Runnable action) {
    try { action.run(); } catch (IllegalStateException expected) { return; }
    throw new AssertionError("Expected unavailable AE2 source rejection");
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources", timeoutTicks = 300)
  public static void ae2_interface_uses_whole_network_and_returns_components(GameTestHelper h) throws Exception {
    if (!optional(h, "ae2")) return;
    var fixture = network(h, "bind_ae_interface", false);
    h.runAfterDelay(80, () -> {
      try {
        var named = new ItemStack(Items.DIAMOND); named.set(DataComponents.CUSTOM_NAME, Component.literal("ME material"));
        put(fixture, named, 5); bind(fixture); fixture.player.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 8));
        var id = UUID.randomUUID(); Costs.reserve(fixture.player, id, Costs.quote(fixture.player, prefab("ae_whole_network", diamonds(4), 0, 0)));
        h.assertTrue(stored(fixture, named) == 1 && count(fixture.player, Items.DIAMOND) == 8, "AE2 did not precede player inventory");
        Costs.refund(fixture.player, id); Costs.refund(fixture.player, id);
        h.assertTrue(stored(fixture, named) == 5, "AE2 return stripped components or duplicated resources");
        PrefabDeploy.LOGGER.info("BOUND AE2 INTERFACE NETWORK VERIFIED"); h.succeed();
      } catch (Exception ex) { throw new RuntimeException(ex); }
    });
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources", timeoutTicks = 300)
  public static void ae2_terminal_selects_clicked_part_and_uses_power(GameTestHelper h) throws Exception {
    if (!optional(h, "ae2")) return;
    var fixture = network(h, "bind_ae_terminal", true);
    h.runAfterDelay(80, () -> {
      try {
        put(fixture, new ItemStack(Items.DIAMOND), 5);
        var binding = bind(fixture);
        h.assertTrue(binding.getList("sources", Tag.TAG_COMPOUND).getCompound(0).getString("part").equals("south"), "Wrong AE2 cable part selected");
        var energy = call(grid(fixture), "getEnergyService");
        double before = ((Number) call(energy, "getStoredPower")).doubleValue();
        var id = UUID.randomUUID(); Costs.reserve(fixture.player, id, Costs.quote(fixture.player, prefab("ae_terminal", diamonds(4), 0, 0))); Costs.commit(fixture.player, id);
        h.assertTrue(stored(fixture, new ItemStack(Items.DIAMOND)) == 1 && ((Number) call(energy, "getStoredPower")).doubleValue() < before, "AE2 network extraction did not consume items and power");
        refuses(() -> Costs.quote(fixture.player, prefab("ae_missing", diamonds(2), 0, 0)));
        PrefabDeploy.LOGGER.info("BOUND AE2 TERMINAL AND ENERGY VERIFIED"); h.succeed();
      } catch (Exception ex) { throw new RuntimeException(ex); }
    });
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources", timeoutTicks = 300)
  public static void ae2_offline_network_falls_back_to_player(GameTestHelper h) throws Exception {
    if (!optional(h, "ae2")) return;
    var fixture = network(h, "bind_ae_offline", false);
    h.runAfterDelay(80, () -> {
      try { put(fixture, new ItemStack(Items.DIAMOND), 5); bind(fixture); }
      catch (Exception ex) { throw new RuntimeException(ex); }
      h.getLevel().setBlock(fixture.energy, Blocks.AIR.defaultBlockState(), 3);
    });
    h.runAfterDelay(140, () -> {
      try {
        fixture.player.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 3));
        var id = UUID.randomUUID(); Costs.reserve(fixture.player, id, Costs.quote(fixture.player, prefab("ae_offline", diamonds(2), 0, 0))); Costs.commit(fixture.player, id);
        h.assertTrue(count(fixture.player, Items.DIAMOND) == 1 && cellStored(fixture, new ItemStack(Items.DIAMOND)) == 5,
            "Offline AE2 did not use fallback materials"); h.succeed();
      } catch (Exception ex) { throw new RuntimeException(ex); }
    });
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources", timeoutTicks = 400)
  public static void ae2_changed_network_holds_return_until_original_membership_returns(GameTestHelper h) throws Exception {
    if (!optional(h, "ae2")) return;
    var fixture = network(h, "bind_ae_changed", false); var transaction = UUID.randomUUID();
    var added = fixture.drive.relative(Direction.WEST);
    h.runAfterDelay(80, () -> {
      try {
        put(fixture, new ItemStack(Items.DIAMOND), 5); bind(fixture);
        Costs.reserve(fixture.player, transaction, Costs.quote(fixture.player, prefab("ae_changed", diamonds(4), 0, 0)));
        h.getLevel().setBlock(added, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("ae2:energy_cell")).defaultBlockState(), 3);
      } catch (Exception ex) { throw new RuntimeException(ex); }
    });
    h.runAfterDelay(140, () -> {
      try {
        refuses(() -> Costs.refund(fixture.player, transaction));
        h.assertTrue(cellStored(fixture, new ItemStack(Items.DIAMOND)) == 1, "Return was inserted into a changed ME network");
        h.getLevel().setBlock(added, Blocks.AIR.defaultBlockState(), 3);
      } catch (Exception ex) { throw new RuntimeException(ex); }
    });
    h.runAfterDelay(200, () -> {
      try {
        Costs.refund(fixture.player, transaction); Costs.refund(fixture.player, transaction);
        h.assertTrue(cellStored(fixture, new ItemStack(Items.DIAMOND)) == 5, "Original ME network did not recover return");
        PrefabDeploy.LOGGER.info("BOUND AE2 ORIGINAL NETWORK RECOVERY VERIFIED"); h.succeed();
      } catch (Exception ex) { throw new RuntimeException(ex); }
    });
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void sophisticated_storage_direct_inventory_precedes_player(GameTestHelper h) throws Exception {
    if (!optional(h, "sophisticatedstorage")) return;
    var p = player(h, "bind_sophisticated"); var pos = h.absolutePos(new BlockPos(1, 1, 1));
    var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("sophisticatedstorage:chest"));
    h.assertTrue(block != Blocks.AIR, "Sophisticated Storage chest is missing");
    h.getLevel().setBlock(pos, block.defaultBlockState(), 3);
    var inventory = (IItemHandlerModifiable) call(call(h.getLevel().getBlockEntity(pos), "getStorageWrapper"), "getInventoryHandler");
    inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 5));
    p.getInventory().setItem(0, new ItemStack(PrefabDeploy.TOOL.get())); p.getInventory().selected = 0;
    p.setPos(pos.getX() + .5, pos.getY() + 2, pos.getZ() + .5);
    p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos)); p.setShiftKeyDown(true);
    p.gameMode.useItemOn(p, p.serverLevel(), p.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND,
        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)); p.setShiftKeyDown(false);
    h.assertTrue(!ContainerBinding.read(p.getMainHandItem()).isEmpty(), "Real Sophisticated Storage Shift interaction did not bind");
    p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 6));
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("sophisticated_bound", diamonds(4), 0, 0)));
    h.assertTrue(inventory.getStackInSlot(0).getCount() == 1 && count(p, Items.DIAMOND) == 6, "Sophisticated chest priority differs");
    Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(inventory.getStackInSlot(0).getCount() == 5, "Sophisticated chest return differs");
    PrefabDeploy.LOGGER.info("BOUND SOPHISTICATED STORAGE VERIFIED"); h.succeed();
  }
}
