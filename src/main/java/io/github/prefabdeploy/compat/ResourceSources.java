package io.github.prefabdeploy.compat;

import io.github.prefabdeploy.UiText;
import io.github.prefabdeploy.server.ResourceReceiptStorage;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Version-checked optional APIs. The core never links optional Mod classes. */
public final class ResourceSources {
  private static final String SB = "net.p3pp3rf1y.sophisticatedbackpacks.";
  private static final String SC = "net.p3pp3rf1y.sophisticatedcore.";
  private static final String BD = "com.wintercogs.beyonddimensions.api.";

  public record Entry(CompoundTag resource, long available) {}
  public static final class Changed extends IllegalStateException {
    private Changed() { super("Material changed before reservation"); }
  }

  public static final class Source {
    public final CompoundTag identity;
    public final SavedData data;
    public final Path path;
    private final ServerPlayer player;
    private final Object backpackWrapper;
    private final Object network, storage;

    private Source(ServerPlayer p, CompoundTag id, SavedData data, String filename,
        Object backpackWrapper, Object network) throws Exception {
      this.player = p;
      this.identity = id;
      this.data = data;
      this.path = p.server.getWorldPath(LevelResource.ROOT).resolve("data/" + filename + ".dat");
      this.backpackWrapper = backpackWrapper;
      this.network = network;
      this.storage = network == null ? null : call(network, "getUnifiedStorage");
      ResourceReceiptStorage.verify(data, path);
    }

    public String key() {
      return identity.getString("kind") + ":" + (identity.hasUUID("uuid")
          ? identity.getUUID("uuid") : identity.getInt("net"));
    }

    public void changed() throws Exception {
      if (identity.getString("kind").equals("backpack"))
        BackpackWrapperCache.changed(identity.getUUID("uuid"));
      data.setDirty();
    }

    public void validateFunding(UUID transaction) throws Exception {
      if (network != null) {
        Object current = call(Class.forName(BD + "dimensionnet.DimensionsNet"),
            "getPrimaryNetFromPlayer", player);
        if (current == null || ((Number) call(current, "getId")).intValue() != identity.getInt("net")
            || !member(network, player.getUUID()))
          throw new IllegalStateException("Bound network changed or permission was revoked");
      } else {
        boolean found = false;
        for (var source : backpacks(player)) if (source.key().equals(key())) { found = true; break; }
        // A backpack may itself be an explicit item fee. Its shell has then moved into our
        // player escrow, while its contents remain in the same SavedData identity.
        if (!found) {
          var escrow = player.getPersistentData().getCompound("prefabdeploy_receipts")
              .getCompound(transaction.toString()).getList("escrow", Tag.TAG_COMPOUND);
          var backpackItem = Class.forName(SB + "backpack.BackpackItem");
          for (var tag : escrow) {
            var stack = decodeItem(player, (CompoundTag) tag);
            if (!backpackItem.isInstance(stack.getItem())) continue;
            var owned = backpackIdentity(player, stack);
            if (owned != null && owned.getString("kind").equals(identity.getString("kind"))
                && owned.getUUID("uuid").equals(identity.getUUID("uuid"))) { found = true; break; }
          }
        }
        if (!found) throw new IllegalStateException("Funding backpack is no longer carried");
      }
    }

    public List<Entry> entries() throws Exception {
      var out = new ArrayList<Entry>();
      var inventory = currentInventory();
      if (inventory != null) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
          // The direct handler excludes Inception inventories and upgrade slots.
          if ((boolean) call(inventory, "isInfinite", slot)
              || !(boolean) call(inventory, "isSlotAccessible", slot)) continue;
          var stack = inventory.getStackInSlot(slot);
          if (stack.isEmpty()) continue;
          var resource = item(player, stack);
          resource.putInt("slot", slot);
          var extracted = inventory.extractItem(slot, stack.getCount(), true);
          if (ItemStack.isSameItemSameComponents(stack, extracted))
            out.add(new Entry(resource, extracted.getCount()));
        }
      } else {
        for (Object value : (List<?>) call(storage, "getStorage")) {
          var key = call(value, "key");
          long amount = ((Number) call(value, "amount")).longValue();
          if (amount <= 0) continue;
          Object stack = call(key, "copyStackWithCount", 1L);
          CompoundTag resource;
          if (stack instanceof ItemStack s) resource = item(player, s);
          else if (stack instanceof FluidStack f && f.is(Fluids.LAVA)) {
            resource = new CompoundTag();
            resource.put("fluid", f.save(player.registryAccess()));
          } else continue;
          var simulated = call(storage, "extract", key, amount, true, false);
          if ((boolean) call(key, "isSameTypeSameComponents", call(simulated, "key")))
            out.add(new Entry(resource, ((Number) call(simulated, "amount")).longValue()));
        }
      }
      return out;
    }

    public void extract(CompoundTag resource) throws Exception {
      long amount = resource.getLong("amount");
      if (amount <= 0) throw new IllegalStateException("Invalid resource quantity");
      var inventory = currentInventory();
      if (inventory != null) {
        int slot = resource.getInt("slot");
        var expected = stack(player, resource);
        var before = inventory.getStackInSlot(slot);
        if (!ItemStack.isSameItemSameComponents(before, expected)
            || inventory.extractItem(slot, Math.toIntExact(amount), true).getCount() != amount)
          throw new Changed();
        var actual = inventory.extractItem(slot, Math.toIntExact(amount), false);
        if (!ItemStack.isSameItemSameComponents(actual, expected) || actual.getCount() != amount)
          throw new IllegalStateException("Resource extraction returned an unknown result");
      } else {
        Object key = networkKey(resource);
        var simulated = call(storage, "extract", key, amount, true, false);
        if (((Number) call(simulated, "amount")).longValue() != amount
            || !(boolean) call(key, "isSameTypeSameComponents", call(simulated, "key")))
          throw new Changed();
        var actual = call(storage, "extract", key, amount, false, false);
        if (((Number) call(actual, "amount")).longValue() != amount
            || !(boolean) call(key, "isSameTypeSameComponents", call(actual, "key")))
          throw new IllegalStateException("Resource extraction returned an unknown result");
      }
    }

    /** Returns the uninserted quantity. Never voids overflow and never spills into another source. */
    public long insert(CompoundTag resource) throws Exception {
      long amount = resource.getLong("amount");
      var inventory = currentInventory();
      if (inventory != null) {
        var left = stack(player, resource).copyWithCount(Math.toIntExact(amount));
        int original = resource.getInt("slot");
        if (original >= 0 && original < inventory.getSlots() && refundableSlot(inventory, original))
          left = inventory.insertItem(original, left, false);
        for (int slot = 0; slot < inventory.getSlots() && !left.isEmpty(); slot++) {
          if (slot != original && refundableSlot(inventory, slot))
            left = inventory.insertItem(slot, left, false);
        }
        return left.getCount();
      }
      Object key = networkKey(resource);
      var left = call(storage, "insert", key, amount, false);
      long remaining = ((Number) call(left, "amount")).longValue();
      if (remaining < 0 || remaining > amount || (remaining > 0
          && !(boolean) call(key, "isSameTypeSameComponents", call(left, "key"))))
        throw new IllegalStateException("Resource insertion returned an unknown result");
      return remaining;
    }

    private IItemHandler currentInventory() throws Exception {
      if (backpackWrapper == null) return null;
      // Normal wrappers cache deserialized slots. Read current canonical NBT before each action,
      // so a change made through another wrapper between planning and extraction is observed.
      if (identity.getString("kind").equals("backpack")) call(backpackWrapper, "onContentsNbtUpdated");
      return (IItemHandler) call(backpackWrapper, "getInventoryHandler");
    }

    private boolean refundableSlot(IItemHandler inventory, int slot) throws Exception {
      return !(boolean) call(inventory, "isInfinite", slot)
          && (boolean) call(inventory, "isSlotAccessible", slot);
    }

    private Object networkKey(CompoundTag resource) throws Exception {
      if (resource.contains("item"))
        return Class.forName(BD + "storage.key.impl.ItemStackKey")
            .getConstructor(ItemStack.class).newInstance(stack(player, resource));
      var fluid = FluidStack.CODEC.parse(player.registryAccess().createSerializationContext(NbtOps.INSTANCE),
          resource.getCompound("fluid")).getOrThrow(IllegalStateException::new);
      if (fluid.isEmpty() || !fluid.is(Fluids.LAVA))
        throw new IllegalStateException("Saved fluid resource is unavailable");
      return Class.forName(BD + "storage.key.impl.FluidStackKey")
          .getConstructor(FluidStack.class).newInstance(fluid);
    }
  }

  public static List<Source> backpacks(ServerPlayer player) throws Exception {
    if (!OptionalMods.loaded("sophisticatedbackpacks")) return List.of();
    version("sophisticatedbackpacks", "3.26.3");
    version("sophisticatedcore", "1.5.1");
    var found = new LinkedHashMap<String, Source>();
    var backpackItem = Class.forName(SB + "backpack.BackpackItem");
    // Only top-level main, chest and offhand slots. Curios cosmetic inventories are excluded.
    var ordered = new ArrayList<ItemStack>();
    for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
      if (slot >= 36 && slot != 38 && slot != 40) continue;
      var s = player.getInventory().getItem(slot);
      if (backpackItem.isInstance(s.getItem())) ordered.add(s);
    }
    if (OptionalMods.loaded("curios")) {
      version("curios", "9.5.1+1.21.1");
      var cap = (Optional<?>) call(Class.forName("top.theillusivec4.curios.api.CuriosApi"),
          "getCuriosInventory", player);
      if (cap.isPresent()) {
        var slots = (Map<?, ?>) call(cap.get(), "getCurios");
        for (var name : slots.keySet().stream().map(Object::toString).sorted().toList()) {
          var handler = (IItemHandler) call(slots.get(name), "getStacks");
          for (int slot = 0; slot < handler.getSlots(); slot++) {
            var s = handler.getStackInSlot(slot);
            if ((boolean) call(cap.get(), "isSlotActive", name, slot)
                && backpackItem.isInstance(s.getItem())) ordered.add(s);
          }
        }
      }
    }
    for (var s : ordered) {
      var id = backpackIdentity(player, s);
      if (id == null) continue;
      String key = id.getString("kind") + ":" + id.getUUID("uuid");
      if (!found.containsKey(key)) found.put(key, resolve(player, id));
    }
    return List.copyOf(found.values());
  }

  private static CompoundTag backpackIdentity(ServerPlayer player, ItemStack stack) throws Exception {
    // The canonical linked host has no physical endpoint component; inspect the carried shell.
    var wrapper = Class.forName(SB + "backpack.wrapper.BackpackWrapper")
        .getConstructor(ItemStack.class).newInstance(stack);
    var endpoint = (Optional<?>) call(wrapper, "getLinkedStorageEndpoint");
    var id = new CompoundTag();
    if (endpoint.isPresent()) {
      id.putString("kind", "linked_backpack");
      id.putUUID("uuid", (UUID) call(endpoint.get(), "groupId"));
    } else {
      var uuid = (Optional<?>) call(wrapper, "getContentsUuid");
      if (uuid.isEmpty()) return null; // Quoting must not initialize an untouched empty backpack.
      id.putString("kind", "backpack"); id.putUUID("uuid", (UUID) uuid.get());
      id.put("carrier", stack.copyWithCount(1).save(player.registryAccess()));
    }
    return id;
  }

  public static Source network(ServerPlayer p) throws Exception {
    if (!OptionalMods.loaded("beyonddimensions")) return null;
    version("beyonddimensions", "0.7.30");
    var net = call(Class.forName(BD + "dimensionnet.DimensionsNet"), "getPrimaryNetFromPlayer", p);
    if (net == null) return null;
    if (!member(net, p.getUUID())) throw new IllegalStateException("Bound network permission unavailable");
    var id = new CompoundTag();
    id.putString("kind", "network");
    id.putInt("net", ((Number) call(net, "getId")).intValue());
    return resolve(p, id);
  }

  public static Source resolve(ServerPlayer p, CompoundTag id) throws Exception {
    String kind = id.getString("kind");
    if (kind.equals("network")) {
      version("beyonddimensions", "0.7.30");
      if (id.getInt("net") < 0) throw new IllegalStateException("Invalid saved network identity");
      var net = call(Class.forName(BD + "dimensionnet.DimensionsNet"), "getNetFromId", id.getInt("net"));
      if (net == null) throw new IllegalStateException("Original network is unavailable; recovery is pending");
      return new Source(p, id.copy(), (SavedData) net, "BDNet_" + id.getInt("net"), null, net);
    }
    version("sophisticatedbackpacks", "3.26.3");
    version("sophisticatedcore", "1.5.1");
    Object wrapper;
    SavedData data;
    String filename;
    if (kind.equals("linked_backpack")) {
      data = (SavedData) call(Class.forName(SC + "linkedstorage.LinkedStorageGroupsSavedData"),
          "get", p.server.overworld());
      var host = (Optional<?>) call(call(data, "manager"), "resolveVirtualHost", id.getUUID("uuid"));
      if (host.isEmpty()) throw new IllegalStateException("Original backpack is unavailable; recovery is pending");
      wrapper = host.get();
      filename = "sophisticatedcore_linked_storage_groups";
    } else if (kind.equals("backpack")) {
      data = (SavedData) call(Class.forName(SB + "backpack.BackpackStorage"), "get", p.server.overworld());
      var contents = (Optional<?>) call(data, "getBackpackContents", id.getUUID("uuid"));
      if (contents.isEmpty()) throw new IllegalStateException("Original backpack is unavailable; recovery is pending");
      var carrier = decodeItem(p, id.getCompound("carrier"));
      if (carrier.isEmpty()) throw new IllegalStateException("Saved backpack item is unavailable");
      wrapper = call(Class.forName(SB + "backpack.wrapper.BackpackWrapper"), "fromStack", carrier);
      if (!BackpackWrapperCache.tracked(wrapper))
        throw new IllegalStateException("Resource persistence adapter unavailable");
      var actual = (Optional<?>) call(wrapper, "getContentsUuid");
      if (actual.isEmpty() || !actual.get().equals(id.getUUID("uuid")))
        throw new IllegalStateException("Saved backpack identity differs");
      filename = "sophisticatedbackpacks";
    } else throw new IllegalStateException("Unknown saved resource source");
    return new Source(p, id.copy(), data, filename,
        wrapper, null);
  }

  private static boolean member(Object net, UUID player) throws Exception {
    return player.equals(call(net, "getOwner"))
        || ((Set<?>) call(net, "getManagers")).contains(player)
        || ((Set<?>) call(net, "getPlayers")).contains(player);
  }

  private static void version(String id, String expected) {
    var mod = ModList.get().getModContainerById(id);
    if (mod.isEmpty() || !mod.get().getModInfo().getVersion().toString().equals(expected))
      throw UiText.failure(UiText.tr("resource.adapter_version",
          "Resource adapter requires %s %s", id, expected));
  }

  private static Object call(Object receiver, String method, Object... args) throws Exception {
    return OptionalMods.call(receiver, method, args);
  }

  public static CompoundTag item(ServerPlayer p, ItemStack stack) {
    var tag = new CompoundTag();
    tag.put("item", stack.copyWithCount(1).save(p.registryAccess()));
    return tag;
  }

  public static ItemStack stack(ServerPlayer p, CompoundTag resource) {
    var stack = decodeItem(p, resource.getCompound("item"));
    if (stack.isEmpty()) throw new IllegalStateException("Saved material item is unavailable");
    return stack;
  }

  private static ItemStack decodeItem(ServerPlayer p, CompoundTag tag) {
    // Vanilla parseOptional accepts partial codec results; recovery must never strip missing components.
    return ItemStack.CODEC.parse(p.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
        .getOrThrow(IllegalStateException::new);
  }

  private ResourceSources() {}
}
