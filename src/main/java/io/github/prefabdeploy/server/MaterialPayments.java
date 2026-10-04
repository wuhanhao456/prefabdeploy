package io.github.prefabdeploy.server;

import io.github.prefabdeploy.UiText;
import io.github.prefabdeploy.compat.ResourceSources;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;

/** Allocation is read-only. Each external participant atomically saves resources plus its escrow. */
public final class MaterialPayments {
  public record Allocation(ResourceSources.Source source, ListTag resources) {}
  public record Plan(ListTag local, List<Allocation> external) {
    public ListTag snapshot() {
      var out = new ListTag();
      for (var a : external) {
        var entry = new CompoundTag();
        entry.put("source", a.source.identity.copy());
        entry.put("resources", a.resources.copy());
        out.add(entry);
      }
      return out;
    }
  }

  private static final class Candidate {
    final ResourceSources.Source source;
    final CompoundTag resource;
    long available, taken;
    Candidate(ResourceSources.Source source, CompoundTag resource, long available) {
      this.source = source; this.resource = resource; this.available = available;
    }
  }

  private static BiConsumer<UUID, String> testHook;
  public static void testHook(BiConsumer<UUID, String> hook) { testHook = hook; }
  public static void fault(UUID id, String stage) { if (testHook != null) testHook.accept(id, stage); }

  public static Plan plan(ServerPlayer p, CompoundTag quote) {
    var needed = new LinkedHashMap<Item, Long>();
    for (var tag : quote.getList("items", Tag.TAG_COMPOUND)) {
      var n = (CompoundTag) tag;
      var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(n.getString("id")));
      if (item == Items.AIR || n.getInt("count") <= 0)
        throw new IllegalStateException("Invalid saved material cost");
      needed.merge(item, (long) n.getInt("count"), Math::addExact);
    }
    int version = quote.getInt("materialVersion");
    if (version != 0 && version != 1) throw new IllegalStateException("Unknown material quote version");
    long lava = version == 1 ? quote.getInt("autoLava") : 0;
    if (lava < 0) throw new IllegalStateException("Invalid saved lava cost");
    if (needed.isEmpty() && lava == 0) return new Plan(new ListTag(), List.of());
    var candidates = new ArrayList<Candidate>();
    for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) {
      var stack = p.getInventory().getItem(slot);
      if (stack.isEmpty() || (!needed.containsKey(stack.getItem())
          && !(lava > 0 && stack.is(Items.LAVA_BUCKET)))) continue;
      var n = ResourceSources.item(p, stack);
      n.putInt("slot", slot);
      candidates.add(new Candidate(null, n, stack.getCount()));
    }
    allocate(p, candidates, needed);
    lava = take(p, candidates, Items.LAVA_BUCKET, lava);
    if (version == 1 && (missing(needed) || lava > 0)) {
      try {
        var bags = new ArrayList<Candidate>();
        for (var source : ResourceSources.backpacks(p)) add(bags, source);
        candidates.addAll(bags);
        allocate(p, bags, needed);
        lava = take(p, bags, Items.LAVA_BUCKET, lava);
        if (missing(needed) || lava > 0) {
          var net = ResourceSources.network(p);
          if (net != null) {
            var network = new ArrayList<Candidate>();
            add(network, net);
            candidates.addAll(network);
            allocate(p, network, needed);
            lava = take(p, network, Items.LAVA_BUCKET, lava);
            long mb = Math.multiplyExact(lava, 1000L);
            // Only whole blocks are payable. Combined component variants may supply one bucketful.
            long available = 0;
            for (var c : network) if (c.resource.contains("fluid"))
              available = Math.min(mb, available + Math.min(mb - available, c.available));
            long payable = available / 1000 * 1000;
            for (var c : network) if (c.resource.contains("fluid") && payable > 0) {
              long n = Math.min(c.available, payable);
              c.taken += n; c.available -= n; payable -= n;
            }
            lava -= available / 1000;
          }
        }
      } catch (Exception ex) {
        if (ex instanceof UiText.Failure failure) throw failure;
        throw UiText.failure(UiText.tr("resource.unavailable", "Resource source unavailable: %s",
            UiText.fromLegacy(rootMessage(ex))));
      }
    }
    for (var e : needed.entrySet()) if (e.getValue() > 0) {
      String id = BuiltInRegistries.ITEM.getKey(e.getKey()).toString();
      long total = 0;
      for (var tag : quote.getList("items", Tag.TAG_COMPOUND)) {
        var n = (CompoundTag) tag;
        if (n.getString("id").equals(id)) total += n.getInt("count");
      }
      throw new IllegalStateException("Missing material: " + id + " " + (total - e.getValue()) + "/" + total);
    }
    if (lava > 0) throw UiText.failure(UiText.tr("resource.missing_lava",
        "Missing lava: %s blocks; each needs a lava bucket or 1000 mB from the bound network", lava));
    var local = new ListTag();
    var allocations = new LinkedHashMap<String, Allocation>();
    for (var c : candidates) if (c.taken > 0) {
      var resource = c.resource.copy();
      resource.putLong("amount", c.taken);
      if (c.source == null) local.add(resource);
      else allocations.computeIfAbsent(c.source.key(), k -> new Allocation(c.source, new ListTag()))
          .resources.add(resource);
    }
    return new Plan(local, List.copyOf(allocations.values()));
  }

  private static boolean missing(Map<Item, Long> needed) {
    return needed.values().stream().anyMatch(n -> n > 0);
  }

  private static void add(List<Candidate> out, ResourceSources.Source source) throws Exception {
    for (var entry : source.entries()) if (entry.available() > 0)
      out.add(new Candidate(source, entry.resource(), entry.available()));
  }

  private static void allocate(ServerPlayer p, List<Candidate> pool, Map<Item, Long> needed) {
    needed.replaceAll((item, amount) -> take(p, pool, item, amount));
  }

  private static long take(ServerPlayer p, List<Candidate> pool, Item item, long amount) {
    for (var c : pool) {
      if (amount == 0) break;
      if (!c.resource.contains("item") || !ResourceSources.stack(p, c.resource).is(item)) continue;
      long n = Math.min(amount, c.available);
      c.available -= n; c.taken += n; amount -= n;
    }
    return amount;
  }

  public static void reserveLocal(ServerPlayer p, Plan plan, ListTag escrow) {
    for (var tag : plan.local) {
      var n = (CompoundTag) tag;
      var stack = p.getInventory().getItem(n.getInt("slot"));
      int amount = Math.toIntExact(n.getLong("amount"));
      if (stack.getCount() < amount || !ItemStack.isSameItemSameComponents(stack, ResourceSources.stack(p, n)))
        throw new IllegalStateException("Material changed before reservation");
      escrow.add(stack.split(amount).save(p.registryAccess()));
    }
  }

  public static void reserveExternal(ServerPlayer p, UUID id, Plan plan) throws Exception {
    for (var a : plan.external) {
      var source = a.source;
      source.validateFunding(id);
      ResourceReceiptStorage.verify(source.data, source.path);
      var ledger = ResourceReceiptStorage.receipts(source.data);
      String key = receiptKey(id, source);
      if (ledger.contains(key)) {
        var existing = ledger.getCompound(key);
        validate(p, existing, a.resources);
        if (!existing.getString("state").equals("RESERVED"))
          throw new IllegalStateException("Resource transaction is not reservable");
        continue;
      }
      var receipt = new CompoundTag();
      receipt.putInt("version", 1);
      receipt.putUUID("owner", p.getUUID());
      receipt.put("requested", a.resources.copy());
      receipt.put("escrow", new ListTag());
      receipt.putString("state", "UNCERTAIN");
      ledger.put(key, receipt);
      try {
        for (var resource : a.resources) {
          source.extract((CompoundTag) resource);
          receipt.getList("escrow", Tag.TAG_COMPOUND).add(resource.copy());
        }
        source.changed();
        receipt.putString("state", "RESERVED");
      } catch (Exception ex) {
        // A failed pre-extraction recheck has a known result: only prior successful rows were paid.
        receipt.putString("state", ex instanceof ResourceSources.Changed ? "RESERVED" : "UNCERTAIN");
        persist(p, source);
        throw ex;
      }
      sourceFault(id, source, "source_before_save");
      persist(p, source);
      sourceFault(id, source, "source_saved");
    }
  }

  public static void finish(ServerPlayer p, UUID id, ListTag allocations, boolean commit) throws Exception {
    for (var tag : allocations) {
      var allocation = (CompoundTag) tag;
      var source = ResourceSources.resolve(p, allocation.getCompound("source"));
      ResourceReceiptStorage.verify(source.data, source.path);
      var ledger = ResourceReceiptStorage.receipts(source.data);
      String key = receiptKey(id, source);
      if (!ledger.contains(key)) {
        if (commit) throw new IllegalStateException("Reserved resource receipt is missing");
        continue; // The player intent was saved but this participant never durably deducted.
      }
      var receipt = ledger.getCompound(key);
      validate(p, receipt, allocation.getList("resources", Tag.TAG_COMPOUND));
      String state = receipt.getString("state");
      if (state.equals(commit ? "COMMITTED" : "REFUNDED")) continue;
      if (state.equals("UNCERTAIN"))
        throw new IllegalStateException("Resource result is uncertain; recovery is pending");
      if (!(state.equals("RESERVED") || (!commit && state.equals("REFUND_PENDING"))))
        throw new IllegalStateException("Resource transaction state differs");
      if (commit) {
        receipt.putString("state", "COMMITTED");
        receipt.put("escrow", new ListTag());
        persist(p, source);
        sourceFault(id, source, "source_commit_saved");
      } else {
        var escrow = receipt.getList("escrow", Tag.TAG_COMPOUND);
        var pending = new ListTag();
        try {
          for (var resourceTag : escrow) {
            var resource = (CompoundTag) resourceTag;
            long left = source.insert(resource);
            if (left > 0) {
              var n = resource.copy(); n.putLong("amount", left); pending.add(n);
            }
          }
          source.changed();
          receipt.put("escrow", pending);
          receipt.putString("state", pending.isEmpty() ? "REFUNDED" : "REFUND_PENDING");
        } catch (Exception ex) {
          receipt.putString("state", "UNCERTAIN");
          persist(p, source);
          throw ex;
        }
        sourceFault(id, source, "source_refund_before_save");
        persist(p, source);
        sourceFault(id, source, "source_refund_saved");
        if (!pending.isEmpty()) throw UiText.failure(UiText.tr("resource.refund_pending",
            "Original resource storage is full; refund is pending"));
      }
    }
  }

  private static void validate(ServerPlayer p, CompoundTag receipt, ListTag requested) {
    if (receipt.getInt("version") != 1 || !receipt.hasUUID("owner")
        || !receipt.getUUID("owner").equals(p.getUUID()) || !receipt.getList("requested", Tag.TAG_COMPOUND).equals(requested))
      throw new IllegalStateException("Resource receipt owner, version or quote differs");
  }

  /** Read-only administrator view; an unavailable original source never disappears from the audit. */
  public static ListTag audit(ServerPlayer p, UUID id, ListTag allocations) {
    var out = new ListTag();
    for (var tag : allocations) {
      var allocation = (CompoundTag) tag;
      var row = new CompoundTag(); row.put("source", allocation.getCompound("source").copy());
      try {
        var source = ResourceSources.resolve(p, allocation.getCompound("source"));
        ResourceReceiptStorage.verify(source.data, source.path);
        var ledger = ResourceReceiptStorage.receipts(source.data);
        row.putString("file", source.path.toString());
        row.put("receipt", ledger.getCompound(receiptKey(id, source)).copy());
      } catch (Exception ex) { row.putString("error", rootMessage(ex)); }
      out.add(row);
    }
    return out;
  }

  private static String receiptKey(UUID id, ResourceSources.Source source) { return id + "/" + source.key(); }
  private static void sourceFault(UUID id, ResourceSources.Source source, String stage) {
    fault(id, stage);
    fault(id, source.identity.getString("kind") + ":" + stage);
  }
  private static void persist(ServerPlayer p, ResourceSources.Source source) {
    source.data.setDirty();
    ResourceReceiptStorage.save(source.data, source.path, p.registryAccess());
  }
  private static String rootMessage(Throwable ex) {
    while (ex.getCause() != null) ex = ex.getCause();
    return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
  }

  private MaterialPayments() {}
}
