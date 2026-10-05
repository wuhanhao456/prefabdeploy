package io.github.prefabdeploy.compat;

import io.github.prefabdeploy.UiText;
import io.github.prefabdeploy.server.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.*;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

public final class BoundContainers {
  private static final String UUID_TAG = "prefabdeploy:container_identity";
  private record Notice(String text, long time) {}
  private static final Map<UUID, Notice> NOTICES = new HashMap<>();

  public static CompoundTag bind(ServerPlayer player, BlockHitResult hit) throws Exception {
    var level = player.serverLevel();
    var pos = hit.getBlockPos();
    permission(player, level, pos, null);
    var blockEntity = level.getBlockEntity(pos);
    if (blockEntity == null) throw unsupported();
    var sources = new ListTag();
    var ae = Ae2ContainerSource.identify(player, blockEntity, hit);
    if (ae != null) sources.add(ae);
    else {
      var first = identify(player, blockEntity, hit.getDirection());
      sources.add(first);
      var state = blockEntity.getBlockState();
      if (blockEntity instanceof ChestBlockEntity && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
        var otherPos = pos.relative(ChestBlock.getConnectedDirection(state));
        permission(player, level, otherPos, null);
        var other = level.getBlockEntity(otherPos);
        if (!(other instanceof ChestBlockEntity)) throw unsupported();
        sources.add(identify(player, other, hit.getDirection()));
      }
    }
    var binding = new CompoundTag();
    binding.putInt("version", 1);
    binding.putString("dimension", level.dimension().location().toString());
    binding.putLong("pos", pos.asLong());
    binding.put("sources", sources);
    return binding;
  }

  static CompoundTag identity(ServerPlayer player, BlockEntity be, Direction side, String kind) {
    var id = new CompoundTag();
    id.putString("kind", kind);
    id.putString("dimension", player.level().dimension().location().toString());
    id.putLong("pos", be.getBlockPos().asLong());
    id.putString("side", side.getSerializedName());
    id.putUUID("uuid", entityIdentity(be));
    id.putString("type", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
    return id;
  }

  static UUID entityIdentity(BlockEntity be) {
    var persistent = be.getPersistentData();
    if (!persistent.hasUUID(UUID_TAG)) { persistent.putUUID(UUID_TAG, UUID.randomUUID()); be.setChanged(); }
    return persistent.getUUID(UUID_TAG);
  }

  private static CompoundTag identify(ServerPlayer player, BlockEntity be, Direction side) throws Exception {
    if (be instanceof BaseContainerBlockEntity container && !container.canOpen(player))
      throw UiText.failure(UiText.tr("binding.denied", "No permission to use this material container"));
    var inventory = handler(player.serverLevel(), be, side);
    if (inventory == null) throw unsupported();
    var id = identity(player, be, side, "container");
    boolean local = be instanceof BaseContainerBlockEntity;
    if (sophisticated(be)) local = !linkedStorage(be);
    id.putBoolean("local", local);
    return id;
  }

  static boolean sophisticated(BlockEntity be) {
    return OptionalMods.loaded("sophisticatedstorage") && be.getClass().getName()
        .startsWith("net.p3pp3rf1y.sophisticatedstorage.block.");
  }

  private static boolean linkedStorage(BlockEntity be) throws Exception {
    // Older direct-storage releases predate linked storage entirely.
    boolean linkedApi = Arrays.stream(be.getClass().getMethods()).anyMatch(method -> method.getName().equals("isLinkedStorage") && method.getParameterCount() == 0);
    return linkedApi && (boolean) OptionalMods.call(be, "isLinkedStorage");
  }

  private static IItemHandler handler(ServerLevel level, BlockEntity be, Direction side) {
    // NeoForge's chest capability joins both halves. Each half needs its own durable participant.
    if (be instanceof ChestBlockEntity chest) return new InvWrapper(chest);
    return level.getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), side);
  }

  public static List<ResourceSources.Source> available(ServerPlayer player, CompoundTag binding, UUID transaction) {
    if (binding.isEmpty()) return List.of();
    var out = new ArrayList<ResourceSources.Source>();
    var seen = new HashSet<String>();
    try {
      if (!player.level().dimension().location().toString().equals(binding.getString("dimension")))
        throw UiText.failure(UiText.tr("binding.dimension", "The material container is in another dimension"));
      for (var tag : binding.getList("sources", Tag.TAG_COMPOUND)) {
        try {
          var id = (CompoundTag) tag;
          var source = id.getString("kind").equals("ae2") ? Ae2ContainerSource.snapshot(player, id) : resolve(player, id);
          source.validateFunding(transaction);
          if (seen.add(source.key())) out.add(source);
        } catch (Exception ex) { notice(player, ex); }
      }
    } catch (Exception ex) { notice(player, ex); }
    return out;
  }

  public static void notice(ServerPlayer player, Exception failure) {
    var message = UiText.tr("binding.fallback", "Bound container unavailable; using other material sources: %s",
        UiText.fromThrowable(failure));
    String text = message.plain();
    var previous = NOTICES.get(player.getUUID());
    long now = System.nanoTime();
    if (previous == null || !previous.text.equals(text) || now - previous.time > 5_000_000_000L) {
      player.displayClientMessage(message.component(), true);
      if (player instanceof net.neoforged.neoforge.common.util.FakePlayer)
        io.github.prefabdeploy.PrefabDeploy.LOGGER.info("BOUND CONTAINER FALLBACK: {}", text);
      NOTICES.put(player.getUUID(), new Notice(text, now));
    }
  }

  public static void clear() { NOTICES.clear(); }

  public static CompoundTag forConstruction(ServerPlayer player, CompoundTag original,
      ResourceKey<Level> dimension, Set<BlockPos> affected) {
    var binding = original.copy();
    var sources = binding.getList("sources", Tag.TAG_COMPOUND);
    sources.removeIf(tag -> {
      var id = (CompoundTag) tag;
      boolean overlap = dimension.location().toString().equals(id.getString("dimension"))
          && affected.contains(BlockPos.of(id.getLong("pos")));
      if (!overlap && id.getString("kind").equals("ae2")) {
        try { overlap = Ae2ContainerSource.overlaps(player, id, dimension, affected); }
        catch (Exception ignored) { /* Normal availability checking supplies the failure reason. */ }
      }
      if (overlap) notice(player, UiText.failure(UiText.tr("binding.overlap", "The bound material source is inside the construction area")));
      return overlap;
    });
    return sources.isEmpty() ? new CompoundTag() : binding;
  }

  public static ResourceSources.Source resolve(ServerPlayer player, CompoundTag id) throws Exception {
    if (id.getString("kind").equals("ae2")) return new Ae2ContainerSource(player, id);
    if (!id.getString("kind").equals("container")) throw unsupported();
    return new ContainerSource(player, id);
  }

  static ServerLevel level(ServerPlayer player, CompoundTag id) {
    var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id.getString("dimension")));
    var level = player.server.getLevel(key);
    if (level == null || !id.hasUUID("uuid") || Direction.byName(id.getString("side")) == null)
      throw new IllegalStateException("Original container is unavailable; recovery is pending");
    return level;
  }

  static LevelChunk chunk(ServerLevel level, BlockPos pos) {
    var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    if (chunk == null) throw UiText.failure(UiText.tr("binding.unloaded", "The material container chunk is not loaded"));
    return chunk;
  }

  static BlockEntity endpoint(ServerLevel level, CompoundTag id) {
    var pos = BlockPos.of(id.getLong("pos"));
    chunk(level, pos);
    var be = level.getBlockEntity(pos);
    if (be == null || !be.getPersistentData().hasUUID(UUID_TAG)
        || !id.getUUID("uuid").equals(be.getPersistentData().getUUID(UUID_TAG))
        || !id.getString("type").equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString()))
      throw new IllegalStateException("Original container is unavailable; recovery is pending");
    return be;
  }

  static void permission(ServerPlayer player, ServerLevel level, BlockPos pos, UUID transaction) {
    chunk(level, pos);
    if (!level.dimension().equals(player.level().dimension()) || player.isSpectator()
        || !level.getWorldBorder().isWithinBounds(pos)
        || player.server.isUnderSpawnProtection(level, pos, player))
      throw UiText.failure(UiText.tr("binding.denied", "No permission to use this material container"));
    if (RegionLocks.locked(level, pos) && !RegionLocks.ownedBy(level, pos, transaction))
      throw new IllegalStateException("Construction area is locked");
    String protection = OptionalMods.protection(player, pos);
    if (!protection.isEmpty()) throw UiText.failure(UiText.fromLegacy(protection));
  }

  private static UiText.Failure unsupported() {
    return UiText.failure(UiText.tr("binding.unsupported", "This block does not provide a material inventory"));
  }

  static class ContainerSource extends ResourceSources.Source {
    protected final ServerPlayer owner;
    protected final ServerLevel level;
    protected final BlockPos pos;
    private final boolean local;
    private final BoundResourceJournal journal;

    ContainerSource(ServerPlayer owner, CompoundTag id) {
      super(owner, id);
      this.owner = owner;
      level = level(owner, id);
      pos = BlockPos.of(id.getLong("pos"));
      local = id.getBoolean("local") && id.getString("kind").equals("container");
      journal = local ? null : BoundResourceJournal.get(owner.server, identity);
    }

    @Override public String key() {
      return identity.getString("kind") + ":" + identity.getString("dimension") + ":" + pos.asLong()
          + ":" + identity.getUUID("uuid") + ":" + identity.getString("side");
    }
    @Override public CompoundTag receipts() {
      return local ? ContainerReceipts.ledger(chunk(level, pos)) : journal.receipts();
    }
    @Override public void verify() {
      if (local) ContainerReceipts.verify(level, chunk(level, pos)); else journal.verify();
    }
    @Override public void persist() {
      if (local) ContainerReceipts.save(level, chunk(level, pos));
      else {
        // Network cells and linked handlers can own data outside the endpoint's chunk.
        // Flush standard world/SavedData persistence before publishing a completed receipt.
        // The pre-call journal remains UNCERTAIN if this flush or the final journal write fails.
        owner.server.saveAllChunks(true, true, true);
        journal.save();
      }
    }
    @Override public void beforeMutation() { if (!local) journal.save(); }
    @Override public String location() {
      return local ? identity.getString("dimension") + "/region " + (pos.getX() >> 4) + ", " + (pos.getZ() >> 4)
          : journal.location();
    }
    @Override public void changed() { endpoint(level, identity).setChanged(); }
    @Override public void validateFunding(UUID transaction) throws Exception {
      permission(owner, level, pos, transaction);
      var be = endpoint(level, identity);
      if (local && !(be instanceof BaseContainerBlockEntity) && !sophisticated(be)) throw unsupported();
      if (be instanceof BaseContainerBlockEntity container && !container.canOpen(owner))
        throw UiText.failure(UiText.tr("binding.denied", "No permission to use this material container"));
      inventory();
    }
    protected IItemHandler inventory() throws Exception {
      var be = endpoint(level, identity);
      // A local-storage binding must never silently become a linked, externally persisted inventory.
      if (sophisticated(be) && local && linkedStorage(be)) throw unsupported();
      var inventory = handler(level, be, Direction.byName(identity.getString("side")));
      if (inventory == null) throw unsupported();
      return inventory;
    }
    private boolean slotAvailable(int slot) throws Exception {
      var be = endpoint(level, identity);
      if (!sophisticated(be)) return true;
      var direct = OptionalMods.call(OptionalMods.call(be, "getStorageWrapper"), "getInventoryHandler");
      return !(boolean) OptionalMods.call(direct, "isInfinite", slot)
          && (boolean) OptionalMods.call(direct, "isSlotAccessible", slot);
    }
    @Override public List<ResourceSources.Entry> entries() throws Exception {
      var inventory = inventory();
      var out = new ArrayList<ResourceSources.Entry>();
      for (int slot = 0; slot < inventory.getSlots(); slot++) {
        if (!slotAvailable(slot)) continue;
        var stack = inventory.getStackInSlot(slot);
        if (stack.isEmpty()) continue;
        var available = inventory.extractItem(slot, stack.getCount(), true);
        if (available.isEmpty() || !ItemStack.isSameItemSameComponents(stack, available)) continue;
        var resource = ResourceSources.item(owner, available);
        resource.putInt("slot", slot);
        out.add(new ResourceSources.Entry(resource, available.getCount()));
      }
      return out;
    }
    @Override public CompoundTag take(CompoundTag resource) throws Exception {
      var inventory = inventory();
      int slot = resource.getInt("slot"), amount = Math.toIntExact(resource.getLong("amount"));
      if (amount <= 0 || slot < 0 || slot >= inventory.getSlots() || !slotAvailable(slot))
        throw new ResourceSources.Changed();
      var expected = ResourceSources.stack(owner, resource);
      var simulated = inventory.extractItem(slot, amount, true);
      if (!ItemStack.isSameItemSameComponents(simulated, expected) || simulated.getCount() != amount)
        throw new ResourceSources.Changed();
      var actual = inventory.extractItem(slot, amount, false);
      var taken = actual.isEmpty() ? resource.copy() : ResourceSources.item(owner, actual);
      taken.putInt("slot", slot);
      taken.putLong("amount", actual.getCount());
      return taken;
    }
    @Override public long insert(CompoundTag resource) throws Exception {
      var inventory = inventory();
      var left = ResourceSources.stack(owner, resource).copyWithCount(Math.toIntExact(resource.getLong("amount")));
      int original = resource.getInt("slot");
      if (original >= 0 && original < inventory.getSlots() && slotAvailable(original))
        left = inventory.insertItem(original, left, false);
      for (int slot = 0; slot < inventory.getSlots() && !left.isEmpty(); slot++)
        if (slot != original && slotAvailable(slot)) left = inventory.insertItem(slot, left, false);
      return left.getCount();
    }
    @Override public void prepareReturn(CompoundTag resource) throws Exception {
      inventory();
      ResourceSources.stack(owner, resource);
    }
  }

  private BoundContainers() {}
}
