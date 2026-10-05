package io.github.prefabdeploy.testing;

import static io.github.prefabdeploy.testing.ResourceGameTests.*;

import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.compat.*;
import io.github.prefabdeploy.item.ContainerBinding;
import io.github.prefabdeploy.server.*;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("prefabresources")
@PrefixGameTestTemplate(false)
public final class BoundContainerGameTests {
  private static BlockPos chest(GameTestHelper h, BlockPos local, int count) {
    h.setBlock(local, Blocks.CHEST);
    var pos = h.absolutePos(local);
    ((ChestBlockEntity) h.getLevel().getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, count));
    return pos;
  }
  private static CompoundTag bind(ServerPlayer player, BlockPos pos) throws Exception {
    var tool = new ItemStack(PrefabDeploy.TOOL.get());
    player.getInventory().setItem(0, tool);
    player.getInventory().selected = 0;
    var binding = BoundContainers.bind(player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false));
    ContainerBinding.write(tool, binding);
    return binding;
  }
  private static int remaining(GameTestHelper h, BlockPos pos) {
    var chest = (BaseContainerBlockEntity) h.getLevel().getBlockEntity(pos);
    int total = 0;
    for (int slot = 0; slot < chest.getContainerSize(); slot++) if (chest.getItem(slot).is(Items.DIAMOND)) total += chest.getItem(slot).getCount();
    return total;
  }
  private static void refuses(Runnable action) {
    try { action.run(); } catch (IllegalStateException expected) { return; }
    throw new AssertionError("Expected material transaction rejection");
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void bound_container_precedes_inventory_and_returns_once(GameTestHelper h) throws Exception {
    var p = player(h, "bind_priority"); var pos = chest(h, new BlockPos(1, 1, 1), 3); bind(p, pos);
    p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 5));
    var id = UUID.randomUUID(); var quote = Costs.quote(p, prefab("bound_priority", diamonds(2), 0, 0));
    Costs.reserve(p, id, quote);
    h.assertTrue(remaining(h, pos) == 1 && count(p, Items.DIAMOND) == 5, "Player inventory was consumed before container");
    Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(remaining(h, pos) == 3 && count(p, Items.DIAMOND) == 5, "Container return lost/duplicated resources");
    h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void container_and_inventory_combine_and_commit_once(GameTestHelper h) throws Exception {
    var p = player(h, "bind_combined"); var pos = chest(h, new BlockPos(1, 1, 1), 2); bind(p, pos);
    p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 3));
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("bound_combined", diamonds(4), 0, 0)));
    Costs.commit(p, id); Costs.commit(p, id);
    h.assertTrue(remaining(h, pos) == 0 && count(p, Items.DIAMOND) == 1 && Costs.state(p, id).equals("COMMITTED"), "Combined payment differs");
    refuses(() -> Costs.refund(p, id)); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void tools_keep_independent_bindings_and_quote_snapshot(GameTestHelper h) throws Exception {
    var p = player(h, "bind_snapshot"); var first = chest(h, new BlockPos(1, 1, 1), 2); var second = chest(h, new BlockPos(3, 1, 1), 4);
    bind(p, first); var firstTool = p.getMainHandItem().copy();
    var quote = Costs.quote(p, prefab("bound_snapshot", diamonds(2), 0, 0));
    bind(p, second);
    var parsed = ItemStack.CODEC.parse(p.registryAccess().createSerializationContext(NbtOps.INSTANCE), firstTool.save(p.registryAccess())).getOrThrow();
    h.assertTrue(ContainerBinding.read(parsed).getLong("pos") == first.asLong()
        && ContainerBinding.read(p.getMainHandItem()).getLong("pos") == second.asLong(), "Tool binding was shared or not persisted");
    var id = UUID.randomUUID(); Costs.reserve(p, id, quote); Costs.refund(p, id);
    h.assertTrue(remaining(h, first) == 2 && remaining(h, second) == 4, "Quote snapshot used replacement binding");
    h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void missing_material_never_deducts_partial_sources(GameTestHelper h) throws Exception {
    var p = player(h, "bind_missing"); var pos = chest(h, new BlockPos(1, 1, 1), 2); bind(p, pos);
    p.getInventory().setItem(1, new ItemStack(Items.DIAMOND));
    refuses(() -> Costs.quote(p, prefab("bound_missing", diamonds(4), 0, 0)));
    h.assertTrue(remaining(h, pos) == 2 && count(p, Items.DIAMOND) == 1, "Read-only quote deducted resources"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void changed_container_rejects_and_returns_local_payment(GameTestHelper h) throws Exception {
    var p = player(h, "bind_changed"); var pos = chest(h, new BlockPos(1, 1, 1), 2); bind(p, pos);
    p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 2));
    var quote = Costs.quote(p, prefab("bound_changed", diamonds(4), 0, 0));
    var id = UUID.randomUUID();
    try {
      MaterialPayments.testHook((transaction, stage) -> {
        if (transaction.equals(id) && stage.equals("player_saved")) ((ChestBlockEntity) h.getLevel().getBlockEntity(pos)).clearContent();
      });
      refuses(() -> Costs.reserve(p, id, quote));
    } finally { MaterialPayments.testHook(null); }
    h.assertTrue(count(p, Items.DIAMOND) == 2 && remaining(h, pos) == 0 && Costs.state(p, id).equals("REFUNDED"), "Reservation failure lost player payment");
    h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void container_return_preserves_components_and_pending_capacity(GameTestHelper h) throws Exception {
    var p = player(h, "bind_capacity"); var pos = chest(h, new BlockPos(1, 1, 1), 2); bind(p, pos);
    var original = (ChestBlockEntity) h.getLevel().getBlockEntity(pos);
    var named = new ItemStack(Items.DIAMOND, 2); named.set(DataComponents.CUSTOM_NAME, Component.literal("Bound material"));
    original.setItem(0, named.copy());
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("bound_capacity", diamonds(2), 0, 0)));
    for (int slot = 0; slot < original.getContainerSize(); slot++) original.setItem(slot, new ItemStack(Items.STONE, 64));
    refuses(() -> Costs.refund(p, id)); refuses(() -> Costs.refund(p, id));
    original.setItem(0, ItemStack.EMPTY); Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(original.getItem(0).getCount() == 2 && ItemStack.isSameItemSameComponents(named, original.getItem(0)), "Pending return changed components or duplicated items");
    h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void replacement_container_is_not_the_original_return_target(GameTestHelper h) throws Exception {
    var p = player(h, "bind_replaced"); var pos = chest(h, new BlockPos(1, 1, 1), 3); bind(p, pos);
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("bound_replaced", diamonds(2), 0, 0)));
    var original = h.getLevel().getBlockEntity(pos).saveWithFullMetadata(p.registryAccess());
    h.getLevel().removeBlockEntity(pos); h.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3); h.getLevel().setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
    refuses(() -> Costs.refund(p, id)); h.assertTrue(remaining(h, pos) == 0, "Return inserted into replacement chest");
    h.getLevel().getBlockEntity(pos).loadWithComponents(original, p.registryAccess());
    Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(remaining(h, pos) == 3, "Original container could not recover return"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void unloaded_and_other_dimension_bindings_fall_back_without_loading(GameTestHelper h) throws Exception {
    var p = player(h, "bind_unloaded"); var pos = chest(h, new BlockPos(1, 1, 1), 3); var binding = bind(p, pos);
    p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 5));
    var far = new BlockPos(10000000, 64, 10000000);
    var unavailable = binding.copy(); unavailable.getList("sources", Tag.TAG_COMPOUND).getCompound(0).putLong("pos", far.asLong());
    ContainerBinding.write(p.getMainHandItem(), unavailable);
    var first = UUID.randomUUID(); Costs.reserve(p, first, Costs.quote(p, prefab("bound_unloaded", diamonds(2), 0, 0))); Costs.commit(p, first);
    h.assertTrue(h.getLevel().getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null, "Source chunk was forcibly loaded");
    binding.putString("dimension", "minecraft:the_nether"); ContainerBinding.write(p.getMainHandItem(), binding);
    var second = UUID.randomUUID(); Costs.reserve(p, second, Costs.quote(p, prefab("bound_dimension", diamonds(2), 0, 0))); Costs.commit(p, second);
    h.assertTrue(count(p, Items.DIAMOND) == 1 && remaining(h, pos) == 3, "Unavailable source was consumed"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void creative_and_old_quotes_keep_original_material_protocol(GameTestHelper h) throws Exception {
    var p = player(h, "bind_legacy"); var pos = chest(h, new BlockPos(1, 1, 1), 3); bind(p, pos);
    p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 5));
    var quote = Costs.quote(p, prefab("bound_legacy", diamonds(2), 0, 0)); quote.putInt("materialVersion", 1);
    var id = UUID.randomUUID(); Costs.reserve(p, id, quote); Costs.commit(p, id);
    h.assertTrue(count(p, Items.DIAMOND) == 3 && remaining(h, pos) == 3, "Old quote applied new priority");
    p.setGameMode(GameType.CREATIVE);
    var free = UUID.randomUUID(); Costs.reserve(p, free, Costs.quote(p, prefab("bound_creative", diamonds(100), 0, 0))); Costs.commit(p, free);
    h.assertTrue(count(p, Items.DIAMOND) == 3 && remaining(h, pos) == 3, "Creative task consumed bound materials"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void two_players_cannot_spend_the_same_container_items(GameTestHelper h) throws Exception {
    var first = player(h, "bind_first"); var second = player(h, "bind_second"); var pos = chest(h, new BlockPos(1, 1, 1), 3);
    bind(first, pos); bind(second, pos);
    var f = prefab("bound_race", diamonds(2), 0, 0); var one = Costs.quote(first, f); var two = Costs.quote(second, f);
    var id = UUID.randomUUID(); Costs.reserve(first, id, one); refuses(() -> Costs.reserve(second, UUID.randomUUID(), two));
    Costs.refund(first, id); h.assertTrue(remaining(h, pos) == 3, "Concurrent reservations duplicated payment"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void tool_interaction_and_gray_tooltip_preserve_binding_on_invalid_target(GameTestHelper h) throws Exception {
    var p = player(h, "bind_interaction"); var pos = chest(h, new BlockPos(1, 1, 1), 3);
    p.getInventory().setItem(0, new ItemStack(PrefabDeploy.TOOL.get())); p.getInventory().selected = 0;
    p.setPos(pos.getX() + .5, pos.getY() + 2, pos.getZ() + .5); p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos)); p.setShiftKeyDown(true);
    h.assertTrue(p.isShiftKeyDown(), "FakePlayer did not start crouching");
    var actualHit = p.level().clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(),
        p.getEyePosition().add(p.getLookAngle().scale(p.blockInteractionRange())),
        net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
    h.assertTrue(actualHit.getType() == HitResult.Type.BLOCK && actualHit.getBlockPos().equals(pos),
        "Test player did not look at container: " + actualHit.getBlockPos() + " wanted " + pos + " look " + p.getLookAngle());
    var context = new UseOnContext(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false));
    p.gameMode.useItemOn(p, p.serverLevel(), p.getMainHandItem(), InteractionHand.MAIN_HAND,
        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    var binding = ContainerBinding.read(p.getMainHandItem());
    h.assertTrue(binding.getLong("pos") == pos.asLong() && !binding.isEmpty(), "Shift use did not bind the looked-at chest");
    var lines = new ArrayList<Component>();
    PrefabDeploy.TOOL.get().appendHoverText(p.getMainHandItem(), Item.TooltipContext.of(h.getLevel()), lines, TooltipFlag.NORMAL);
    h.assertTrue(lines.size() == 3 && lines.stream().allMatch(line -> ChatFormatting.GRAY.getColor().equals(line.getStyle().getColor().getValue())), "Binding tooltip is missing or not gray");
    var invalid = h.absolutePos(new BlockPos(3, 1, 1)); h.setBlock(new BlockPos(3, 1, 1), Blocks.STONE);
    p.setPos(invalid.getX() + .5, invalid.getY() + 2, invalid.getZ() + .5);
    p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(invalid));
    PrefabDeploy.TOOL.get().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(invalid), Direction.NORTH, invalid, false)));
    h.assertTrue(ContainerBinding.read(p.getMainHandItem()).equals(binding), "Invalid target cleared previous binding");
    p.setPos(invalid.getX() + .5, invalid.getY() + 64, invalid.getZ() + .5);
    p.setXRot(-90);
    var airHit = p.level().clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(),
        p.getEyePosition().add(p.getLookAngle().scale(p.blockInteractionRange())),
        net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
    h.assertTrue(p.isShiftKeyDown() && airHit.getType() == HitResult.Type.MISS,
        "Test player does not crouch/look into air: " + p.isShiftKeyDown() + " " + airHit.getType() + " " + airHit.getBlockPos());
    PrefabDeploy.TOOL.get().use(p.level(), p, InteractionHand.MAIN_HAND);
    h.assertTrue(ContainerBinding.read(p.getMainHandItem()).isEmpty(), "Shift air did not unbind"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void double_chest_halves_are_distinct_durable_participants(GameTestHelper h) throws Exception {
    var p = player(h, "bind_double");
    var first = h.absolutePos(new BlockPos(1, 1, 1)); var second = first.relative(Direction.EAST);
    h.getLevel().setBlock(first, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
    h.getLevel().setBlock(second, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
    ((ChestBlockEntity) h.getLevel().getBlockEntity(first)).setItem(0, new ItemStack(Items.DIAMOND, 2));
    ((ChestBlockEntity) h.getLevel().getBlockEntity(second)).setItem(0, new ItemStack(Items.DIAMOND, 3));
    var binding = bind(p, first);
    h.assertTrue(binding.getList("sources", Tag.TAG_COMPOUND).size() == 2, "Double chest binding only contains one half");
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("bound_double", diamonds(4), 0, 0)));
    h.assertTrue(remaining(h, first) == 0 && remaining(h, second) == 1, "Double chest allocation differs");
    Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(remaining(h, first) == 2 && remaining(h, second) == 3, "Double chest resources returned to wrong half"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void double_chest_across_chunks_returns_each_original_half(GameTestHelper h) throws Exception {
    var p = player(h, "bind_cross_chunk");
    var origin = h.absolutePos(new BlockPos(1, 1, 1));
    var first = origin.offset(15 - (origin.getX() & 15), 0, 0); var second = first.east();
    h.getLevel().setBlock(first, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
    h.getLevel().setBlock(second, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
    ((ChestBlockEntity) h.getLevel().getBlockEntity(first)).setItem(0, new ItemStack(Items.DIAMOND, 2));
    ((ChestBlockEntity) h.getLevel().getBlockEntity(second)).setItem(0, new ItemStack(Items.DIAMOND, 3)); bind(p, first);
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("bound_cross_chunk", diamonds(4), 0, 0)));
    Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(remaining(h, first) == 2 && remaining(h, second) == 3, "Cross-chunk chest return differs"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void barrel_and_construction_lock_permissions_are_rechecked(GameTestHelper h) throws Exception {
    var p = player(h, "bind_barrel"); var pos = h.absolutePos(new BlockPos(1, 1, 1));
    h.setBlock(new BlockPos(1, 1, 1), Blocks.BARREL);
    ((BarrelBlockEntity) h.getLevel().getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 3)); bind(p, pos);
    p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 4));
    var lock = UUID.randomUUID();
    try {
      h.assertTrue(RegionLocks.lock(h.getLevel(), Set.of(new net.minecraft.world.level.ChunkPos(pos).toLong()), lock), "Could not lock fixture");
      var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("bound_locked", diamonds(2), 0, 0))); Costs.commit(p, id);
      h.assertTrue(remaining(h, pos) == 3 && count(p, Items.DIAMOND) == 2, "Locked source was consumed");
    } finally { RegionLocks.release(lock); }
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("bound_barrel", diamonds(2), 0, 0))); Costs.refund(p, id);
    h.assertTrue(remaining(h, pos) == 3 && count(p, Items.DIAMOND) == 2, "Barrel return differs"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void active_placement_keeps_binding_snapshot_and_shift_cancels(GameTestHelper h) throws Exception {
    var p = player(h, "bind_active"); var first = chest(h, new BlockPos(1, 1, 1), 3); var second = chest(h, new BlockPos(3, 1, 1), 4);
    var binding = bind(p, first); p.setGameMode(GameType.CREATIVE);
    var request = new CompoundTag(); request.putString("op", "select"); request.putString("id", "prefabdeploy:cottage");
    Sessions.receive(p, request); h.assertTrue(Sessions.active(p), "Placement fixture did not start");
    bind(p, second);
    h.assertTrue(Sessions.materialBinding(p).equals(binding), "Active placement used a changed tool binding");
    var current = ContainerBinding.read(p.getMainHandItem()); p.setShiftKeyDown(true);
    PrefabDeploy.TOOL.get().use(p.level(), p, InteractionHand.MAIN_HAND);
    h.assertTrue(!Sessions.active(p) && ContainerBinding.read(p.getMainHandItem()).equals(current), "Shift cancel changed binding"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources", timeoutTicks = 600)
  public static void construction_uses_bound_container_in_its_locked_chunk(GameTestHelper h) throws Exception {
    var p = player(h, "bind_real_build"); var source = chest(h, new BlockPos(1, 1, 1), 5); bind(p, source);
    p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 4));
    var target = source.offset(2, 0, 0); h.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 3);
    var id = DeploymentManager.submit(p, prefab("bound_real_build", diamonds(3), 0, 0),
        new io.github.prefabdeploy.core.GridTransform(target.getX(), target.getY(), target.getZ(), 0, 0), List.of(), false);
    h.succeedWhen(() -> {
      h.assertTrue(DeploymentManager.outcome(id).orElse(false), "Bound construction not completed");
      h.assertTrue(remaining(h, source) == 2 && count(p, Items.DIAMOND) == 4 && h.getLevel().getBlockState(target).isAir(), "Construction did not use the container");
    });
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources", timeoutTicks = 600)
  public static void rollback_does_not_duplicate_materials_inside_construction_area(GameTestHelper h) throws Exception {
    var p = player(h, "bind_overlap"); var source = chest(h, new BlockPos(1, 1, 1), 5); bind(p, source);
    p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 4));
    var id = DeploymentManager.submit(p, prefab("bound_overlap", diamonds(3), 0, 0),
        new io.github.prefabdeploy.core.GridTransform(source.getX(), source.getY(), source.getZ(), 0, 0), List.of(), false);
    System.setProperty("prefabdeploy.testFault." + id, "after_payment");
    h.succeedWhen(() -> {
      h.assertTrue(DeploymentManager.outcome(id).isPresent(), "Bound rollback not completed");
      h.assertTrue(!DeploymentManager.outcome(id).get() && remaining(h, source) == 5 && count(p, Items.DIAMOND) == 4, "Rollback duplicated source contents");
      System.clearProperty("prefabdeploy.testFault." + id);
    });
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void bound_container_combines_with_existing_backpack_and_network(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("sophisticatedbackpacks") || !OptionalMods.loaded("beyonddimensions")) { h.succeed(); return; }
    var p = player(h, "bind_all_sources"); var source = chest(h, new BlockPos(1, 1, 1), 2); bind(p, source);
    p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 2));
    var backpack = bag(p, new ItemStack(Items.DIAMOND, 3)); p.getInventory().setItem(2, backpack);
    var network = net(p); put(network, new ItemStack(Items.DIAMOND), 4);
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("bound_all_sources", diamonds(9), 0, 0)));
    h.assertTrue(remaining(h, source) == 0 && count(p, Items.DIAMOND) == 0 && inventory(backpack).getStackInSlot(0).isEmpty()
        && amount(network, new ItemStack(Items.DIAMOND)) == 2, "Combined source order differs");
    Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(remaining(h, source) == 2 && count(p, Items.DIAMOND) == 2 && inventory(backpack).getStackInSlot(0).getCount() == 3
        && amount(network, new ItemStack(Items.DIAMOND)) == 4, "Combined sources returned incorrectly"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void foreign_claim_rejects_binding_and_rechecks_existing_binding(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("ftbchunks")) { h.succeed(); return; }
    var p = player(h, "bind_claim_intruder"); var owner = player(h, "bind_claim_owner");
    var teams = call(call(type("dev.ftb.mods.ftbteams.api.FTBTeamsAPI"), "api"), "getManager");
    for (var player : List.of(p, owner)) call(teams, "playerLoggedIn", null, player.getUUID(), player.getGameProfile().getName());
    var pos = chest(h, new BlockPos(1, 1, 1), 5); bind(p, pos);
    var policy = Class.forName("dev.ftb.mods.ftbchunks.FTBChunksWorldConfig").getField("ALLOW_FAKE_PLAYERS").get(null);
    call(policy, "set", Class.forName("dev.ftb.mods.ftbchunks.api.ProtectionPolicy").getField("CHECK").get(null));
    var manager = call(call(type("dev.ftb.mods.ftbchunks.api.FTBChunksAPI"), "api"), "getManager");
    var data = call(manager, "getOrCreateData", owner); call(data, "setExtraClaimChunks", 16); call(data, "updateLimits");
    var cp = Class.forName("dev.ftb.mods.ftblibrary.math.ChunkDimPos")
        .getConstructor(net.minecraft.resources.ResourceKey.class, net.minecraft.world.level.ChunkPos.class)
        .newInstance(h.getLevel().dimension(), new net.minecraft.world.level.ChunkPos(pos));
    var claim = call(data, "claim", owner.createCommandSourceStack(), cp, false);
    h.assertTrue(Boolean.TRUE.equals(call(claim, "isSuccess")), "Claim fixture failed");
    try {
      boolean rejected = false;
      try { BoundContainers.bind(p, new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false)); }
      catch (IllegalStateException ex) { rejected = true; }
      h.assertTrue(rejected, "Foreign claim allowed a new binding");
      p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 3));
      var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("bound_foreign_claim", diamonds(2), 0, 0))); Costs.commit(p, id);
      h.assertTrue(remaining(h, pos) == 5 && count(p, Items.DIAMOND) == 1, "Permission change did not fall back to inventory");
      PrefabDeploy.LOGGER.info("BOUND FOREIGN CLAIM PERMISSION VERIFIED");
    } finally { call(data, "unclaim", owner.createCommandSourceStack(), cp, false, true); }
    h.succeed();
  }
}
