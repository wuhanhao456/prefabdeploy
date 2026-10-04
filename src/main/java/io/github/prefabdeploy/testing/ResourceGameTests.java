package io.github.prefabdeploy.testing;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.github.prefabdeploy.blueprint.Blueprint;
import io.github.prefabdeploy.compat.*;
import io.github.prefabdeploy.library.Prefab;
import io.github.prefabdeploy.server.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

@GameTestHolder("prefabresources")
@PrefixGameTestTemplate(false)
public final class ResourceGameTests {
  public static final String SB = "net.p3pp3rf1y.sophisticatedbackpacks.";
  public static final String SC = "net.p3pp3rf1y.sophisticatedcore.";
  public static final String BD = "com.wintercogs.beyonddimensions.api.";

  public static ServerPlayer player(GameTestHelper h, String name) {
    var p = FakePlayerFactory.get(h.getLevel(), new GameProfile(
        UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name));
    p.getInventory().clearContent();
    p.setGameMode(GameType.SURVIVAL);
    p.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(1, 12, 1)));
    return p;
  }

  public static Object call(Object receiver, String method, Object... args) throws Exception {
    return OptionalMods.call(receiver, method, args);
  }

  public static Object type(String name) throws Exception { return Class.forName(name); }
  public static Object wrapper(ItemStack bag) throws Exception {
    return call(type(SB + "backpack.wrapper.BackpackWrapper"), "fromStack", bag);
  }
  public static IItemHandlerModifiable inventory(ItemStack bag) throws Exception {
    return (IItemHandlerModifiable) call(wrapper(bag), "getInventoryHandler");
  }
  public static ItemStack bag(ServerPlayer p, ItemStack contents) throws Exception {
    var s = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:backpack")));
    var inv = inventory(s);
    if (!contents.isEmpty()) inv.setStackInSlot(0, contents.copy());
    return s;
  }
  public static Object net(ServerPlayer p) throws Exception {
    return call(type(BD + "dimensionnet.DimensionsNet"), "createNewNetForPlayer", p, Long.MAX_VALUE, Integer.MAX_VALUE);
  }
  public static Object key(Object stack) throws Exception {
    return Class.forName(BD + "storage.key.impl." + (stack instanceof ItemStack ? "ItemStackKey" : "FluidStackKey"))
        .getConstructor(stack instanceof ItemStack ? ItemStack.class : FluidStack.class).newInstance(stack);
  }
  public static void put(Object net, Object stack, long amount) throws Exception {
    Object result = call(call(net, "getUnifiedStorage"), "insert", key(stack), amount, false);
    if (((Number) call(result, "amount")).longValue() != 0) throw new AssertionError("Fixture insertion overflow");
  }
  public static long amount(Object net, Object stack) throws Exception {
    return ((Number) call(call(call(net, "getUnifiedStorage"), "getStackByKey", key(stack)), "amount")).longValue();
  }
  public static Prefab prefab(String name, String cost, int water, int lava) {
    var voxels = new ArrayList<Blueprint.Voxel>();
    for (int i = 0; i < water; i++) voxels.add(new Blueprint.Voxel(new BlockPos(i, 0, 0), Blocks.WATER.defaultBlockState(), null));
    for (int i = 0; i < lava; i++) voxels.add(new Blueprint.Voxel(new BlockPos(water + i, 0, 0), Blocks.LAVA.defaultBlockState(), null));
    if (voxels.isEmpty()) voxels.add(new Blueprint.Voxel(BlockPos.ZERO, Blocks.AIR.defaultBlockState(), null));
    return new Prefab(ResourceLocation.parse("prefabdeploy:" + name), name, "test", 0,
        new Blueprint(Math.max(1, water + lava), 1, 1, voxels, List.of(), List.of()),
        JsonParser.parseString("{\"cost\":" + cost + "}").getAsJsonObject(), "resource-test", "");
  }
  public static String diamonds(int n) { return "{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":" + n + "}]}"; }
  public static int count(ServerPlayer p, Item item) {
    int count = 0;
    for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) if (p.getInventory().getItem(slot).is(item)) count += p.getInventory().getItem(slot).getCount();
    return count;
  }
  private static void rejected(Runnable action) {
    boolean refused = false;
    try { action.run(); } catch (IllegalStateException ex) { refused = true; }
    if (!refused) throw new AssertionError("Expected resource rejection");
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void missing_item_components_are_not_stripped(GameTestHelper h) {
    var p = player(h, "rs_missing_component");
    var resource = ResourceSources.item(p, new ItemStack(Items.DIAMOND));
    var components = new CompoundTag(); components.putString("removedmod:missing_component", "saved value");
    resource.getCompound("item").put("components", components);
    rejected(() -> ResourceSources.stack(p, resource)); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void native_missing_components_hold_entire_refund(GameTestHelper h) {
    var p = player(h, "rs_native_component"); p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("native_components", diamonds(2), 0, 0)));
    var receipt = p.getPersistentData().getCompound("prefabdeploy_receipts").getCompound(id.toString());
    var saved = receipt.getList("escrow", Tag.TAG_COMPOUND).getCompound(0);
    var components = new CompoundTag(); components.putString("removedmod:missing_component", "original component");
    saved.put("components", components); rejected(() -> Costs.refund(p, id));
    h.assertTrue(count(p, Items.DIAMOND) == 0 && Costs.state(p, id).equals("RESERVED")
        && saved.getCompound("components").equals(components), "Missing native component discarded refund");
    saved.remove("components"); Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(count(p, Items.DIAMOND) == 2, "Native component repair duplicated items"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void normal_backpack_uuid_is_counted_once(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("sophisticatedbackpacks")) { h.succeed(); return; }
    var p = player(h, "rs_duplicate_uuid"); var bag = bag(p, new ItemStack(Items.DIAMOND, 3));
    p.getInventory().setItem(0, bag); p.getInventory().setItem(1, bag.copy());
    h.assertTrue(ResourceSources.backpacks(p).size() == 1, "Normal content UUID counted twice");
    rejected(() -> Costs.quote(p, prefab("duplicate_uuid", diamonds(4), 0, 0)));
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("one_uuid", diamonds(3), 0, 0)));
    Costs.refund(p, id); h.assertTrue(inventory(bag).getStackInSlot(0).getCount() == 3, "Shared UUID refund differs"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void backpack_shell_fee_can_also_pay_from_contents(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("sophisticatedbackpacks")) { h.succeed(); return; }
    var p = player(h, "rs_shell"); var bag = bag(p, new ItemStack(Items.DIAMOND, 3));
    var bagItem = bag.getItem(); p.getInventory().setItem(0, bag);
    var source = ResourceSources.backpacks(p).getFirst(); var f = prefab("shell_fee",
        "{\"mode\":\"manual\",\"items\":[{\"id\":\"sophisticatedbackpacks:backpack\",\"count\":1},{\"id\":\"minecraft:diamond\",\"count\":3}]}", 0, 0);
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, f));
    h.assertTrue(p.getInventory().getItem(0).isEmpty() && source.entries().isEmpty(), "Backpack shell escrow did not fund contents");
    Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(count(p, bagItem) == 1 && inventory(p.getInventory().getItem(0)).getStackInSlot(0).getCount() == 3, "Shell/content refund differs"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void backpack_changed_after_player_save_refunds_known_payments(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("sophisticatedbackpacks")) { h.succeed(); return; }
    var p = player(h, "rs_changed_bag"); var bag = bag(p, new ItemStack(Items.DIAMOND, 2));
    p.getInventory().setItem(0, new ItemStack(Items.DIAMOND)); p.getInventory().setItem(1, bag);
    var quote = Costs.quote(p, prefab("changed_bag", diamonds(3), 0, 0)); var id = UUID.randomUUID();
    MaterialPayments.testHook((tx, stage) -> {
      if (tx.equals(id) && stage.equals("player_saved")) try { inventory(bag).extractItem(0, 1, false); }
      catch (Exception ex) { throw new RuntimeException(ex); }
    });
    try { rejected(() -> Costs.reserve(p, id, quote)); } finally { MaterialPayments.testHook(null); }
    h.assertTrue(Costs.state(p, id).equals("REFUNDED") && count(p, Items.DIAMOND) == 1
        && inventory(bag).getStackInSlot(0).getCount() == 1, "Changed backpack used stale slots or lost known refund"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources", timeoutTicks = 120000)
  public static void actual_deployment_with_available_sources(GameTestHelper h) throws Exception {
    var p = player(h, "rs_deployment");
    boolean bags = OptionalMods.loaded("sophisticatedbackpacks"), networks = OptionalMods.loaded("beyonddimensions");
    if (bags) p.getInventory().setItem(1, bag(p, new ItemStack(Items.DIAMOND, networks ? 1 : 3)));
    if (networks) put(net(p), new ItemStack(Items.DIAMOND), bags ? 1 : 3);
    if (!bags && !networks) p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
    else if (bags && networks) p.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
    var pos = h.absolutePos(new BlockPos(20, 3, 20)); h.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
    p.setPos(pos.getX() + 20, pos.getY() + 12, pos.getZ() + 20);
    var job = DeploymentManager.submit(p, prefab("resource_deployment", diamonds(3), 0, 0),
        new io.github.prefabdeploy.core.GridTransform(pos.getX(), pos.getY(), pos.getZ(), 0, 0), List.of(), false);
    h.succeedWhen(() -> {
      h.assertTrue(DeploymentManager.outcome(job).isPresent(), "Resource deployment incomplete: " + DeploymentManager.status());
      h.assertTrue(DeploymentManager.outcome(job).get() && h.getLevel().getBlockState(pos).isAir(), "Resource deployment failed");
      h.assertTrue(Costs.state(p, job).equals("COMMITTED"), "World changed before resource settlement");
    });
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void network_competition_and_changed_quote(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("beyonddimensions")) { h.succeed(); return; }
    var owner = player(h, "rs_compete_owner"); var member = player(h, "rs_compete_member"); var net = net(owner);
    call(net, "addPlayer", member.getUUID()); call(type(BD + "dimensionnet.DimensionsNet"), "setPrimaryNetForPlayer", member, net);
    put(net, new ItemStack(Items.DIAMOND), 5); var f = prefab("competition", diamonds(4), 0, 0);
    var first = Costs.quote(owner, f); var second = Costs.quote(member, f);
    var tx = UUID.randomUUID(); Costs.reserve(owner, tx, first);
    rejected(() -> Costs.reserve(member, UUID.randomUUID(), second));
    h.assertTrue(amount(net, new ItemStack(Items.DIAMOND)) == 1, "Shared network overspent");
    Costs.refund(owner, tx); var changed = Costs.quote(owner, f);
    call(call(net, "getUnifiedStorage"), "extract", key(new ItemStack(Items.DIAMOND)), 2L, false, false);
    rejected(() -> Costs.reserve(owner, UUID.randomUUID(), changed));
    h.assertTrue(amount(net, new ItemStack(Items.DIAMOND)) == 3, "Old quote changed live resources"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void network_full_refund_remains_pending(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("beyonddimensions")) { h.succeed(); return; }
    var p = player(h, "rs_net_capacity"); var net = net(p); put(net, new ItemStack(Items.DIAMOND), 5);
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("net_capacity", diamonds(5), 0, 0)));
    var storage = call(net, "getUnifiedStorage"); call(storage, "setSlotMaxSize", 1);
    put(net, new ItemStack(Items.STONE), 1); rejected(() -> Costs.refund(p, id));
    h.assertTrue(Costs.state(p, id).equals("RESERVED") && amount(net, new ItemStack(Items.DIAMOND)) == 0, "Network pending refund lost");
    call(storage, "extract", key(new ItemStack(Items.STONE)), 1L, false, false);
    Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(amount(net, new ItemStack(Items.DIAMOND)) == 5, "Network capacity retry duplicated resources"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void backpack_tank_does_not_pay_lava(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("sophisticatedbackpacks")) { h.succeed(); return; }
    var p = player(h, "rs_tank"); var bag = bag(p, ItemStack.EMPTY); p.getInventory().setItem(0, bag);
    var upgrades = (IItemHandlerModifiable) call(wrapper(bag), "getUpgradeHandler");
    upgrades.setStackInSlot(0, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:tank_upgrade"))));
    var fluid = (Optional<?>) call(wrapper(bag), "getFluidHandler"); h.assertTrue(fluid.isPresent(), "Tank upgrade unavailable");
    var handler = (net.neoforged.neoforge.fluids.capability.IFluidHandler) fluid.get();
    h.assertTrue(handler.fill(new FluidStack(Fluids.LAVA, 1000), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE) == 1000, "Tank fixture could not fill");
    rejected(() -> Costs.quote(p, prefab("tank_lava", "{\"mode\":\"auto\"}", 0, 1)));
    h.assertTrue(handler.getFluidInTank(0).getAmount() == 1000, "Backpack tank was consumed"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void automatic_water_is_free_manual_water_is_charged(GameTestHelper h) {
    var p = player(h, "rs_water");
    var f = prefab("water", "{\"mode\":\"auto\"}", 3, 0);
    var q = Costs.quote(p, f);
    h.assertTrue(q.getList("items", 10).isEmpty() && q.getInt("autoLava") == 0, "Automatic water was charged");
    var id = UUID.randomUUID(); Costs.reserve(p, id, q); Costs.commit(p, id);
    var manual = prefab("manual_water", "{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:water_bucket\",\"count\":1}]}", 0, 0);
    rejected(() -> Costs.quote(p, manual));
    p.getInventory().setItem(0, new ItemStack(Items.WATER_BUCKET));
    var mid = UUID.randomUUID(); Costs.reserve(p, mid, Costs.quote(p, manual));
    h.assertTrue(count(p, Items.WATER_BUCKET) == 0, "Manual bucket was exempt");
    Costs.refund(p, mid); Costs.refund(p, mid);
    h.assertTrue(count(p, Items.WATER_BUCKET) == 1, "Manual bucket refund duplicated"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void native_lava_and_legacy_quotes(GameTestHelper h) {
    var p = player(h, "rs_native");
    p.getInventory().setItem(0, new ItemStack(Items.LAVA_BUCKET));
    var q = Costs.quote(p, prefab("lava", "{\"mode\":\"auto\"}", 1, 1));
    h.assertTrue(q.getInt("autoLava") == 1 && q.getList("items", 10).isEmpty(), "Wrong automatic fluid quote");
    var id = UUID.randomUUID(); Costs.reserve(p, id, q); Costs.refund(p, id);
    h.assertTrue(count(p, Items.LAVA_BUCKET) == 1, "Lava bucket refund lost");
    var legacy = new CompoundTag(); var list = new ListTag(); var n = new CompoundTag();
    n.putString("id", "minecraft:water_bucket"); n.putInt("count", 1); list.add(n); legacy.put("items", list);
    rejected(() -> Costs.check(p, legacy));
    p.getInventory().setItem(1, new ItemStack(Items.WATER_BUCKET));
    var old = UUID.randomUUID(); Costs.reserve(p, old, legacy); Costs.refund(p, old);
    h.assertTrue(count(p, Items.WATER_BUCKET) == 1, "Old water snapshot changed"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void backpack_positions_priority_and_components(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("sophisticatedbackpacks")) { h.succeed(); return; }
    var p = player(h, "rs_positions");
    var named = new ItemStack(Items.DIAMOND, 3); named.set(DataComponents.CUSTOM_NAME, Component.literal("Resource component"));
    var a = bag(p, named); var b = bag(p, new ItemStack(Items.DIAMOND, 4)); var c = bag(p, new ItemStack(Items.DIAMOND, 5));
    p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2)); p.getInventory().setItem(1, a);
    p.setItemSlot(EquipmentSlot.CHEST, b); p.setItemSlot(EquipmentSlot.OFFHAND, c);
    var quote = Costs.quote(p, prefab("bags", diamonds(10), 0, 0));
    h.assertTrue(inventory(a).getStackInSlot(0).getCount() == 3, "Quoting changed backpack");
    var id = UUID.randomUUID(); Costs.reserve(p, id, quote);
    h.assertTrue(count(p, Items.DIAMOND) == 0 && inventory(a).getStackInSlot(0).isEmpty()
        && inventory(b).getStackInSlot(0).isEmpty() && inventory(c).getStackInSlot(0).getCount() == 4, "Wrong inventory/backpack order or stale wrapper");
    // Original identity survives removing every physical backpack from the player.
    p.getInventory().setItem(1, ItemStack.EMPTY); p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY); p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(count(p, Items.DIAMOND) == 2 && inventory(a).getStackInSlot(0).getCount() == 3
        && ItemStack.isSameItemSameComponents(named, inventory(a).getStackInSlot(0))
        && inventory(b).getStackInSlot(0).getCount() == 4 && inventory(c).getStackInSlot(0).getCount() == 5, "Refund identity/components differ"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void nested_backpacks_are_not_resources(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("sophisticatedbackpacks")) { h.succeed(); return; }
    var p = player(h, "rs_nested"); var inner = bag(p, new ItemStack(Items.DIAMOND, 9)); var outer = bag(p, inner);
    p.getInventory().setItem(0, outer);
    rejected(() -> Costs.quote(p, prefab("nested", diamonds(1), 0, 0)));
    h.assertTrue(inventory(inner).getStackInSlot(0).getCount() == 9, "Nested backpack consumed"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void curios_backpack_uses_equipped_inventory(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("sophisticatedbackpacks") || !OptionalMods.loaded("curios")) { h.succeed(); return; }
    var p = player(h, "rs_curios"); var bag = bag(p, new ItemStack(Items.DIAMOND, 4));
    var cap = (Optional<?>) call(type("top.theillusivec4.curios.api.CuriosApi"), "getCuriosInventory", p);
    h.assertTrue(cap.isPresent(), "Curios capability unavailable");
    var back = (Optional<?>) call(cap.get(), "getStacksHandler", "back");
    h.assertTrue(back.isPresent(), "Back slot unavailable");
    var stacks = (IItemHandlerModifiable) call(back.get(), "getStacks");
    stacks.setStackInSlot(0, bag);
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("curios", diamonds(3), 0, 0)));
    h.assertTrue(inventory(bag).getStackInSlot(0).getCount() == 1, "Curios backpack not debited");
    Costs.refund(p, id); h.assertTrue(inventory(bag).getStackInSlot(0).getCount() == 4, "Curios refund differs");
    stacks.setStackInSlot(0, ItemStack.EMPTY); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void linked_backpacks_share_one_resource_pool(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("sophisticatedbackpacks")) { h.succeed(); return; }
    var p = player(h, "rs_linked"); var a = bag(p, new ItemStack(Items.DIAMOND, 6)); var b = bag(p, ItemStack.EMPTY);
    var adapter = Class.forName(SB + "backpack.wrapper.BackpackLinkedStorageEndpointAdapter").getConstructor().newInstance();
    var descriptor = call(adapter, "createHostDescriptor", h.getLevel(), a);
    var contents = call(adapter, "copyCanonicalContents", h.getLevel(), a);
    var saved = call(type(SC + "linkedstorage.LinkedStorageGroupsSavedData"), "get", h.getLevel());
    var manager = call(saved, "manager"); var first = UUID.randomUUID(); var second = UUID.randomUUID();
    UUID group = (UUID) call(manager, "createGroup", p.getUUID(), first, descriptor, contents);
    call(manager, "registerEndpoint", group, second);
    var endpoint = Class.forName(SC + "linkedstorage.LinkedStorageEndpointData").getConstructor(UUID.class, UUID.class);
    call(adapter, "bindEndpoint", h.getLevel(), a, endpoint.newInstance(group, first));
    call(adapter, "bindEndpoint", h.getLevel(), b, endpoint.newInstance(group, second));
    p.getInventory().setItem(0, a); p.getInventory().setItem(1, b);
    h.assertTrue(ResourceSources.backpacks(p).size() == 1, "Linked group counted twice");
    rejected(() -> Costs.quote(p, prefab("linked_short", diamonds(7), 0, 0)));
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("linked", diamonds(5), 0, 0)));
    h.assertTrue(inventory(a).getStackInSlot(0).getCount() == 1 && inventory(b).getStackInSlot(0).getCount() == 1, "Shared contents differ");
    Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(inventory(a).getStackInSlot(0).getCount() == 6, "Shared refund duplicated"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void backpack_full_refund_remains_recoverable(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("sophisticatedbackpacks")) { h.succeed(); return; }
    var p = player(h, "rs_capacity"); var bag = bag(p, new ItemStack(Items.DIAMOND, 6)); p.getInventory().setItem(0, bag);
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("capacity", diamonds(5), 0, 0)));
    var inv = inventory(bag); for (int slot = 0; slot < inv.getSlots(); slot++) inv.setStackInSlot(slot, new ItemStack(Items.STONE, 64));
    rejected(() -> Costs.refund(p, id));
    h.assertTrue(Costs.state(p, id).equals("RESERVED") && count(p, Items.DIAMOND) == 0, "Pending refund was discarded");
    inventory(bag).setStackInSlot(0, ItemStack.EMPTY); Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(inventory(bag).getStackInSlot(0).getCount() == 5, "Capacity retry duplicated refund"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void network_lava_quantities_components_and_manual_buckets(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("beyonddimensions")) { h.succeed(); return; }
    var p = player(h, "rs_lava"); var net = net(p); var lava = new FluidStack(Fluids.LAVA, 1); put(net, lava, 999);
    var f = prefab("net_lava", "{\"mode\":\"auto\"}", 4, 1); rejected(() -> Costs.quote(p, f));
    put(net, lava, 1); var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, f));
    h.assertTrue(amount(net, lava) == 0, "Wrong mB charge"); Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(amount(net, lava) == 1000, "Lava refund duplicated");
    var manual = prefab("manual_lava", "{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:lava_bucket\",\"count\":1}]}", 0, 0);
    rejected(() -> Costs.quote(p, manual));
    var named = new ItemStack(Items.DIAMOND); named.set(DataComponents.CUSTOM_NAME, Component.literal("Network key"));
    put(net, named, Long.MAX_VALUE - 1); var did = UUID.randomUUID();
    Costs.reserve(p, did, Costs.quote(p, prefab("long", diamonds(4), 0, 0))); Costs.refund(p, did); Costs.refund(p, did);
    h.assertTrue(amount(net, named) == Long.MAX_VALUE - 1, "Long amount/components changed"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void network_binding_and_permissions_rechecked(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("beyonddimensions")) { h.succeed(); return; }
    var owner = player(h, "rs_owner"); var p = player(h, "rs_member"); var net = net(owner);
    call(net, "addPlayer", p.getUUID()); call(type(BD + "dimensionnet.DimensionsNet"), "setPrimaryNetForPlayer", p, net);
    put(net, new ItemStack(Items.DIAMOND), 10); p.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
    var q = Costs.quote(p, prefab("permission", diamonds(3), 0, 0));
    MaterialPayments.testHook((id, stage) -> { if (stage.equals("player_saved")) try { call(net, "removePlayer", p.getUUID()); } catch (Exception ex) { throw new RuntimeException(ex); } });
    try { rejected(() -> Costs.reserve(p, UUID.randomUUID(), q)); } finally { MaterialPayments.testHook(null); }
    h.assertTrue(count(p, Items.DIAMOND) == 1 && amount(net, new ItemStack(Items.DIAMOND)) == 10, "Revoked member was charged"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void mixed_sources_refund_original_network_and_settle_once(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("beyonddimensions") || !OptionalMods.loaded("sophisticatedbackpacks")) { h.succeed(); return; }
    var p = player(h, "rs_mixed"); var bag = bag(p, new ItemStack(Items.DIAMOND, 3)); p.getInventory().setItem(1, bag);
    p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2)); var net = net(p); put(net, new ItemStack(Items.DIAMOND), 7);
    p.getInventory().setItem(2, new ItemStack(Items.LAVA_BUCKET)); inventory(bag).setStackInSlot(1, new ItemStack(Items.LAVA_BUCKET));
    put(net, new ItemStack(Items.LAVA_BUCKET), 1); put(net, new FluidStack(Fluids.LAVA, 1), 1000);
    var f = prefab("mixed", "{\"mode\":\"combined\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":9}]}", 2, 4);
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, f));
    h.assertTrue(amount(net, new ItemStack(Items.DIAMOND)) == 3 && amount(net, new FluidStack(Fluids.LAVA, 1)) == 0, "Mixed allocation differs");
    var other = net(player(h, "rs_other"));
    call(type(BD + "dimensionnet.DimensionsNet"), "setPrimaryNetForPlayer", p, other);
    p.getInventory().setItem(1, ItemStack.EMPTY); Costs.refund(p, id); Costs.refund(p, id);
    h.assertTrue(amount(net, new ItemStack(Items.DIAMOND)) == 7 && amount(other, new ItemStack(Items.DIAMOND)) == 0
        && inventory(bag).getStackInSlot(0).getCount() == 3, "Refund followed the new source");
    call(type(BD + "dimensionnet.DimensionsNet"), "setPrimaryNetForPlayer", p, net); p.getInventory().add(bag);
    var committed = UUID.randomUUID(); Costs.reserve(p, committed, Costs.quote(p, f)); Costs.commit(p, committed); Costs.commit(p, committed);
    h.assertTrue(amount(net, new ItemStack(Items.DIAMOND)) == 3, "Repeated settlement charged again"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void absent_source_file_is_not_an_unpaid_receipt(GameTestHelper h) throws Exception {
    if (!OptionalMods.loaded("beyonddimensions")) { h.succeed(); return; }
    var p = player(h, "rs_disk"); var net = net(p); put(net, new ItemStack(Items.DIAMOND), 5);
    var id = UUID.randomUUID(); Costs.reserve(p, id, Costs.quote(p, prefab("disk", diamonds(2), 0, 0)));
    var source = ResourceSources.network(p); var backup = source.path.resolveSibling(source.path.getFileName() + ".test-backup");
    Files.move(source.path, backup);
    try { rejected(() -> Costs.refund(p, id)); } finally { Files.move(backup, source.path); }
    h.assertTrue(Costs.state(p, id).equals("RESERVED"), "Missing file discarded receipt");
    Costs.refund(p, id); h.assertTrue(amount(net, new ItemStack(Items.DIAMOND)) == 5, "Disk recovery lost resources"); h.succeed();
  }

  @GameTest(template = "empty", templateNamespace = "prefabresources")
  public static void creative_and_free_never_call_resource_adapters(GameTestHelper h) {
    var p = player(h, "rs_creative"); p.setGameMode(GameType.CREATIVE);
    var q = Costs.quote(p, prefab("creative", diamonds(100), 0, 5));
    h.assertTrue(q.getBoolean("creativeExempt") && q.getList("items", 10).isEmpty() && q.getInt("autoLava") == 0, "Creative materials were quoted");
    var id = UUID.randomUUID(); Costs.reserve(p, id, q); Costs.commit(p, id);
    p.setGameMode(GameType.SURVIVAL); var free = UUID.randomUUID();
    Costs.reserve(p, free, Costs.quote(p, prefab("free", "{\"mode\":\"free\"}", 2, 5))); Costs.commit(p, free); h.succeed();
  }
}
