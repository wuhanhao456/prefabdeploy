package io.github.prefabdeploy.compat;

import io.github.prefabdeploy.UiText;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.fml.ModList;

/** Only this optional bridge resolves AE2 classes. No AE2 linkage exists on a base installation. */
final class Ae2ContainerSource extends BoundContainers.ContainerSource {
  private Object pinnedGrid;

  Ae2ContainerSource(ServerPlayer player, CompoundTag id) { super(player, id); }

  static CompoundTag identify(ServerPlayer player, BlockEntity be, BlockHitResult hit) throws Exception {
    if (!OptionalMods.loaded("ae2")) return null;
    var hostType = Class.forName("appeng.api.parts.IPartHost");
    boolean partHost = hostType.isInstance(be);
    var capability = capability();
    var nodeHost = player.serverLevel().getCapability(capability, be.getBlockPos(), null);
    if (!partHost && nodeHost == null) return null;
    version();
    String part = "";
    if (partHost) {
      var selected = call(be, "selectPartWorld", hit.getLocation());
      var selectedPart = selected.getClass().getField("part").get(selected);
      if (selectedPart == null) throw unavailable();
      var side = (Direction) selected.getClass().getField("side").get(selected);
      part = side == null ? "center" : side.getSerializedName();
    }
    var id = BoundContainers.identity(player, be, hit.getDirection(), "ae2");
    id.putString("part", part);
    node(player, id); // Validate the selected endpoint, not an arbitrary part on the cable.
    return id;
  }

  static ResourceSources.Source snapshot(ServerPlayer player, CompoundTag original) throws Exception {
    version();
    var id = original.copy();
    var node = node(player, id);
    if (!(boolean) call(node, "isActive")) throw unavailable();
    id.putString("grid", fingerprint(call(node, "getGrid")));
    return new Ae2ContainerSource(player, id);
  }

  @SuppressWarnings("unchecked")
  private static BlockCapability<Object, Void> capability() throws Exception {
    return (BlockCapability<Object, Void>) Class.forName("appeng.api.AECapabilities")
        .getField("IN_WORLD_GRID_NODE_HOST").get(null);
  }

  private static Object node(ServerPlayer player, CompoundTag id) throws Exception {
    var level = BoundContainers.level(player, id);
    var be = BoundContainers.endpoint(level, id);
    Object node;
    if (!id.getString("part").isEmpty()) {
      if (!Class.forName("appeng.api.parts.IPartHost").isInstance(be)) throw unavailable();
      Direction side = id.getString("part").equals("center") ? null : Direction.byName(id.getString("part"));
      if (side == null && !id.getString("part").equals("center")) throw unavailable();
      var part = call(be, "getPart", side);
      if (part == null) throw unavailable();
      node = call(part, "getGridNode");
    } else {
      var host = level.getCapability(capability(), be.getBlockPos(), null);
      if (host == null) throw unavailable();
      node = call(host, "getGridNode", Direction.byName(id.getString("side")));
    }
    if (node == null) throw unavailable();
    return node;
  }

  private Object grid() throws Exception {
    version();
    var node = node(owner, identity);
    if (!(boolean) call(node, "isActive")) throw unavailable();
    var grid = call(node, "getGrid");
    if ((pinnedGrid != null && pinnedGrid != grid)
        || !identity.getString("grid").equals(fingerprint(grid)))
      throw new IllegalStateException("Original AE2 network changed; recovery is pending");
    return grid;
  }

  @Override public String key() { return super.key() + ":" + identity.getString("part") + ":" + identity.getString("grid"); }
  @Override public void validateFunding(UUID transaction) throws Exception {
    BoundContainers.permission(owner, level, pos, transaction);
    pinnedGrid = grid();
  }
  @Override public List<ResourceSources.Entry> entries() throws Exception {
    var grid = grid();
    var storage = call(call(grid, "getStorageService"), "getInventory");
    var action = actionSource();
    var result = new ArrayList<ResourceSources.Entry>();
    for (Object entry : (Iterable<?>) call(storage, "getAvailableStacks")) {
      Object key = call(entry, "getKey");
      if (!Class.forName("appeng.api.stacks.AEItemKey").isInstance(key)) continue;
      long amount = ((Number) call(entry, "getLongValue")).longValue();
      long available = powered(grid, storage, key, amount, action, true, false);
      if (available > 0) result.add(new ResourceSources.Entry(
          ResourceSources.item(owner, (ItemStack) call(key, "toStack")), available));
    }
    return result;
  }
  @Override public CompoundTag take(CompoundTag requested) throws Exception {
    var grid = grid();
    var storage = call(call(grid, "getStorageService"), "getInventory");
    var key = call(Class.forName("appeng.api.stacks.AEItemKey"), "of", ResourceSources.stack(owner, requested));
    var action = actionSource();
    long amount = requested.getLong("amount");
    if (amount <= 0 || powered(grid, storage, key, amount, action, true, false) != amount)
      throw new ResourceSources.Changed();
    long actual = powered(grid, storage, key, amount, action, false, false);
    var result = requested.copy();
    result.putLong("amount", actual);
    return result;
  }
  @Override public long insert(CompoundTag resource) throws Exception {
    var grid = grid();
    var storage = call(call(grid, "getStorageService"), "getInventory");
    var key = call(Class.forName("appeng.api.stacks.AEItemKey"), "of", ResourceSources.stack(owner, resource));
    long amount = resource.getLong("amount");
    long inserted = powered(grid, storage, key, amount, actionSource(), false, true);
    if (inserted < 0 || inserted > amount) throw new IllegalStateException("Resource insertion returned an unknown result");
    return amount - inserted;
  }
  @Override public void changed() { BoundContainers.endpoint(level, identity).setChanged(); }
  @Override public void prepareReturn(CompoundTag resource) throws Exception {
    grid();
    ResourceSources.stack(owner, resource);
  }

  private Object actionSource() throws Exception {
    return call(Class.forName("appeng.api.networking.security.IActionSource"), "ofPlayer", owner);
  }
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static long powered(Object grid, Object storage, Object key, long amount, Object source,
      boolean simulation, boolean insert) throws Exception {
    var mode = Enum.valueOf((Class<Enum>) Class.forName("appeng.api.config.Actionable"), simulation ? "SIMULATE" : "MODULATE");
    return ((Number) call(Class.forName("appeng.api.storage.StorageHelper"),
        insert ? "poweredInsert" : "poweredExtraction", call(grid, "getEnergyService"), storage, key, amount, source, mode)).longValue();
  }

  /** Persist physical membership, not a runtime IGrid object. Changed topology needs an audit before return. */
  private static String fingerprint(Object grid) throws Exception {
    var members = new ArrayList<String>();
    for (Object node : (Iterable<?>) call(grid, "getNodes")) {
      Object owner = call(node, "getOwner");
      String member = owner.getClass().getName() + ":" + call(node, "getOwningPlayerProfileId");
      if (owner instanceof BlockEntity be) member += ":" + be.getLevel().dimension().location() + ":" + be.getBlockPos().asLong() + ":" + BoundContainers.entityIdentity(be);
      else if (Class.forName("appeng.api.parts.IPart").isInstance(owner)) {
        var host = call(owner, "getHost");
        var be = (BlockEntity) call(host, "getBlockEntity");
        member += ":" + be.getLevel().dimension().location() + ":" + be.getBlockPos().asLong() + ":" + BoundContainers.entityIdentity(be);
      }
      members.add(member);
    }
    Collections.sort(members);
    return UUID.nameUUIDFromBytes(String.join("\n", members).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
  }

  static boolean overlaps(ServerPlayer player, CompoundTag id,
      net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
      Set<net.minecraft.core.BlockPos> affected) throws Exception {
    for (Object node : (Iterable<?>) call(call(node(player, id), "getGrid"), "getNodes")) {
      Object owner = call(node, "getOwner");
      BlockEntity be = owner instanceof BlockEntity block ? block : null;
      if (be == null && Class.forName("appeng.api.parts.IPart").isInstance(owner))
        be = (BlockEntity) call(call(owner, "getHost"), "getBlockEntity");
      if (be != null && be.getLevel().dimension().equals(dimension) && affected.contains(be.getBlockPos())) return true;
    }
    return false;
  }

  private static void version() {
    var mod = ModList.get().getModContainerById("ae2");
    if (mod.isEmpty() || !mod.get().getModInfo().getVersion().toString().equals("19.2.18"))
      throw UiText.failure(UiText.tr("resource.adapter_version", "Resource adapter requires %s %s", "ae2", "19.2.18"));
  }
  private static UiText.Failure unavailable() {
    return UiText.failure(UiText.tr("binding.ae2_unavailable", "The bound AE2 endpoint is unavailable, unpowered, or missing channels"));
  }
  private static Object call(Object object, String method, Object... args) throws Exception {
    return OptionalMods.call(object, method, args);
  }
}
