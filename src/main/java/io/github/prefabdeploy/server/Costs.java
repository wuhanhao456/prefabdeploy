package io.github.prefabdeploy.server;

import com.google.gson.*;
import io.github.prefabdeploy.UiText;
import io.github.prefabdeploy.api.PrefabApi;
import io.github.prefabdeploy.compat.OptionalMods;
import io.github.prefabdeploy.library.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;

public final class Costs {
  private static final String RECEIPTS = "prefabdeploy_receipts";
  private static final Map<UUID, Map<String, io.github.prefabdeploy.api.CostProvider>> PINNED =
      new HashMap<>();

  public static void pin(UUID transaction, CompoundTag quote) {
    if (PINNED.containsKey(transaction)) return;
    var providers = new HashMap<String, io.github.prefabdeploy.api.CostProvider>();
    var custom = quote.getList("custom", Tag.TAG_COMPOUND);
    for (int i = 0; i < custom.size(); i++) {
      String id = custom.getCompound(i).getString("id");
      var provider = PrefabApi.COSTS.get(id);
      if (provider == null) throw new IllegalStateException("Cost provider unavailable: " + id);
      providers.put(id, provider);
    }
    PINNED.put(transaction, Map.copyOf(providers));
  }

  public static void unpin(UUID transaction) {
    PINNED.remove(transaction);
  }

  public static void clearPins() {
    PINNED.clear();
  }

  private static io.github.prefabdeploy.api.CostProvider provider(UUID transaction, String id) {
    var pinned = PINNED.get(transaction);
    return pinned == null ? PrefabApi.COSTS.get(id) : pinned.get(id);
  }

  private record Materials(Map<Item, Integer> items, int lava, String error) {}

  private static final Map<io.github.prefabdeploy.blueprint.Blueprint, Materials> MATERIALS =
      new IdentityHashMap<>();

  public static void warm(io.github.prefabdeploy.blueprint.Blueprint bp) {
    materials(bp);
  }

  private static Materials materials(io.github.prefabdeploy.blueprint.Blueprint bp) {
    synchronized (MATERIALS) {
      var cached = MATERIALS.get(bp);
      if (cached != null) return cached;
    }
    var needed = new LinkedHashMap<Item, Integer>();
    int lava = 0;
    String error = "";
    try {
      for (var v : bp.voxels()) {
        var s = v.state();
        if (s.isAir() || s.is(Blocks.WATER)) continue;
        if (s.is(Blocks.LAVA)) { lava = Math.addExact(lava, 1); continue; }
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
          continue;
        if (s.hasProperty(BlockStateProperties.BED_PART)
            && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) continue;
        if (s.is(Blocks.PISTON_HEAD)
            || s.is(Blocks.MOVING_PISTON)
            || s.is(Blocks.BUBBLE_COLUMN)
            || s.is(Blocks.FIRE)) continue;
        Item item = s.getBlock().asItem();
        if (s.is(Blocks.REDSTONE_WIRE)) item = Items.REDSTONE;
        if (item == Items.AIR)
          throw new IllegalArgumentException(
              "Cannot price block automatically: "
                  + BuiltInRegistries.BLOCK.getKey(s.getBlock())
                  + "; use manual pricing");
        int count =
            s.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && s.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
                ? 2
                : 1;
        needed.merge(item, count, Math::addExact);
      }
    } catch (Exception ex) {
      error = ex.getMessage();
    }
    var result = new Materials(Map.copyOf(needed), lava, error);
    synchronized (MATERIALS) {
      if (MATERIALS.size() >= 256) MATERIALS.remove(MATERIALS.keySet().iterator().next());
      MATERIALS.put(bp, result);
    }
    return result;
  }

  public static CompoundTag quote(ServerPlayer player, Prefab prefab) {
    return quote(player, prefab, Sessions.materialBinding(player), null);
  }

  public static CompoundTag quote(ServerPlayer player, Prefab prefab, CompoundTag binding, UUID transaction) {
    var q = quoteUnchecked(player, prefab, binding);
    if (transaction != null) q.putUUID("materialTransaction", transaction);
    check(player, q);
    return q;
  }

  public static CompoundTag quoteUnchecked(ServerPlayer player, Prefab prefab) {
    return quoteUnchecked(player, prefab, Sessions.materialBinding(player));
  }

  private static CompoundTag quoteUnchecked(ServerPlayer player, Prefab prefab, CompoundTag binding) {
    if (player.gameMode.isCreative()) {
      var free = new CompoundTag();
      free.putBoolean("creativeExempt", true);
      free.put("items", new ListTag());
      free.put("custom", new ListTag());
      free.putInt("xp", 0);
      free.putDouble("money", 0);
      return free;
    }
    var spec = prefab.metadata().getAsJsonObject("cost");
    if (spec == null)
      throw new IllegalArgumentException("Explicit cost definition required (use mode: free)");
    var out = new CompoundTag();
    out.putInt("materialVersion", 2);
    if (!binding.isEmpty()) out.put("boundContainer", binding.copy());
    var needed = new LinkedHashMap<Item, Integer>();
    String mode = PrefabLibrary.string(spec, "mode", "manual");
    if (!Set.of("free", "manual", "auto", "combined").contains(mode))
      throw new IllegalArgumentException("Unknown cost mode");
    if (mode.equals("auto") || mode.equals("combined")) {
      var basis = materials(prefab.blueprint());
      if (!basis.error.isEmpty()) throw new IllegalArgumentException(basis.error);
      needed.putAll(basis.items);
      out.putInt("autoLava", basis.lava);
    }
    if (!mode.equals("free") && spec.has("items"))
      for (var element : spec.getAsJsonArray("items")) {
        var entry = element.getAsJsonObject();
        var id = ResourceLocation.parse(entry.get("id").getAsString());
        if (!BuiltInRegistries.ITEM.containsKey(id) || BuiltInRegistries.ITEM.get(id) == Items.AIR)
          throw new IllegalArgumentException("Unknown cost item: " + id);
        int count = entry.get("count").getAsInt();
        if (count <= 0) throw new IllegalArgumentException("Item cost must be positive");
        needed.merge(BuiltInRegistries.ITEM.get(id), count, Math::addExact);
      }
    var items = new ListTag();
    for (var e : needed.entrySet()) {
      var n = new CompoundTag();
      n.putString("id", BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
      n.putInt("count", e.getValue());
      items.add(n);
    }
    out.put("items", items);
    int xp = mode.equals("free") ? 0 : spec.has("xp") ? spec.get("xp").getAsInt() : 0;
    if (xp < 0) throw new IllegalArgumentException("Negative XP cost");
    out.putInt("xp", xp);
    double money =
        mode.equals("free")
            ? 0
            : spec.has("viscriptshop") ? spec.get("viscriptshop").getAsDouble() : 0;
    if (!Double.isFinite(money)
        || money < 0
        || java.math.BigDecimal.valueOf(money).stripTrailingZeros().scale() > 2)
      throw new IllegalArgumentException(
          "Currency amount must be nonnegative with at most two decimals");
    out.putDouble("money", money);
    var custom = new ListTag();
    if (!mode.equals("free") && spec.has("custom"))
      for (var entry : spec.getAsJsonArray("custom")) {
        var s = entry.getAsJsonObject();
        String id = s.get("provider").getAsString();
        var provider = PrefabApi.COSTS.get(id);
        if (provider == null || !provider.atomicWithPlayerSave())
          throw new IllegalArgumentException("Missing recoverable cost provider: " + id);
        var n = new CompoundTag();
        n.putString("id", id);
        try {
          n.put("quote", provider.quote(player, s));
        } catch (Exception ex) {
          throw UiText.failure(
              UiText.literal(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
        }
        custom.add(n);
      }
    out.put("custom", custom);
    return out;
  }

  public static void check(ServerPlayer p, CompoundTag quote) {
    MaterialPayments.plan(p, quote);
    if (p.totalExperience < quote.getInt("xp"))
      throw new IllegalStateException("Not enough XP points");
    try {
      if (quote.getDouble("money") > 0 && OptionalMods.money(p) + 1e-7 < quote.getDouble("money"))
        throw new IllegalStateException("Not enough ViScriptShop currency");
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("ViScriptShop adapter unavailable", ex);
    }
  }

  public static String describe(CompoundTag q) {
    var parts = new ArrayList<String>();
    var list = q.getList("items", Tag.TAG_COMPOUND);
    for (int i = 0; i < list.size(); i++) {
      var n = list.getCompound(i);
      parts.add(n.getInt("count") + " × " + n.getString("id"));
    }
    if (q.getInt("autoLava") > 0) parts.add(q.getInt("autoLava") + " lava buckets or "
        + ((long) q.getInt("autoLava") * 1000) + " mB bound-network lava");
    if (q.getInt("xp") > 0) parts.add(q.getInt("xp") + " XP");
    if (q.getDouble("money") > 0) parts.add(q.getDouble("money") + " VSS");
    var custom = q.getList("custom", Tag.TAG_COMPOUND);
    for (int i = 0; i < custom.size(); i++) parts.add(custom.getCompound(i).getString("id"));
    return parts.isEmpty() ? "Free" : String.join(", ", parts);
  }

  public static String state(ServerPlayer p, UUID id) {
    return receipts(p).getCompound(id.toString()).getString("state");
  }

  public static UiText describeText(CompoundTag q) {
    if (q.getBoolean("creativeExempt")) return UiText.tr("cost.creative", "Creative mode — free");
    var parts = new ArrayList<UiText>();
    var items = q.getList("items", Tag.TAG_COMPOUND);
    for (int i = 0; i < items.size(); i++) {
      var n = items.getCompound(i);
      var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(n.getString("id")));
      var name =
          new UiText(
              item.getDescriptionId(), item.getName(new ItemStack(item)).getString(), List.of());
      parts.add(UiText.tr("cost.material", "%s × %s", n.getInt("count"), name));
    }
    if (q.getInt("autoLava") > 0) parts.add(UiText.tr("cost.lava",
        "%s lava buckets (or %s mB from the bound network)", q.getInt("autoLava"),
        (long) q.getInt("autoLava") * 1000));
    if (q.getInt("xp") > 0) parts.add(UiText.tr("cost.xp", "%s XP points", q.getInt("xp")));
    if (q.getDouble("money") > 0)
      parts.add(UiText.tr("cost.money", "%s ViScriptShop currency", q.getDouble("money")));
    var custom = q.getList("custom", Tag.TAG_COMPOUND);
    for (int i = 0; i < custom.size(); i++)
      parts.add(UiText.tr("cost.custom", "Custom cost: %s", custom.getCompound(i).getString("id")));
    return parts.isEmpty() ? UiText.tr("cost.free", "Free") : UiText.join(parts, false);
  }

  public static void reserve(ServerPlayer p, UUID id, CompoundTag quote) {
    if (!state(p, id).isEmpty()) {
      var existing = receipts(p).getCompound(id.toString());
      if (existing.contains("resources") && !existing.getBoolean("resourcesReady")
          && existing.getString("state").equals("RESERVED"))
        throw new IllegalStateException("Resource reservation is incomplete; recovery is pending");
      return;
    }
    check(p, quote);
    var materials = MaterialPayments.plan(p, quote);
    pin(id, quote);
    var receipt = new CompoundTag();
    receipt.put("quote", quote.copy());
    receipt.putString("state", "RESERVED");
    var escrow = new ListTag();
    receipt.put("escrow", escrow);
    receipt.put("resources", materials.snapshot());
    receipts(p).put(id.toString(), receipt);
    try {
      MaterialPayments.reserveLocal(p, materials, escrow);
      if (quote.getInt("xp") > 0) p.giveExperiencePoints(-quote.getInt("xp"));
      receipt.putBoolean("xpPaid", true);
      if (quote.getDouble("money") > 0) {
        receipt.putDouble("moneyBefore", OptionalMods.money(p));
        receipt.putBoolean("moneyUncertain", true);
        double paid = OptionalMods.debitMoney(p, quote.getDouble("money"));
        if (!Double.isFinite(paid) || paid < 0)
          throw new IllegalStateException("Currency API returned an unknown payment state");
        receipt.putDouble("moneyPaidAmount", paid);
        receipt.putBoolean("moneyPaid", paid > 0);
        receipt.putBoolean("moneyUncertain", false);
        if (Math.abs(paid - quote.getDouble("money")) > 1e-7)
          throw new IllegalStateException("Currency deduction mismatch");
      }
      var custom = quote.getList("custom", Tag.TAG_COMPOUND);
      var paid = new ListTag();
      receipt.put("paidCustom", paid);
      for (int i = 0; i < custom.size(); i++) {
        var n = custom.getCompound(i);
        paid.add(n.copy());
        provider(id, n.getString("id")).reserve(p, id, n.getCompound("quote"));
      }
      save(p);
      MaterialPayments.fault(id, "player_saved");
      MaterialPayments.reserveExternal(p, id, materials);
      receipt.putBoolean("resourcesReady", true);
      MaterialPayments.fault(id, "resources_before_confirmation");
      save(p);
      MaterialPayments.fault(id, "resources_ready");
    } catch (Exception ex) {
      try {
        refund(p, id);
      } catch (Exception refund) {
        ex.addSuppressed(refund);
        try {
          save(p);
        } catch (Exception persistence) {
          ex.addSuppressed(persistence);
        }
      }
      if (!materials.external().isEmpty()) {
        var failure = UiText.failure(UiText.tr("resource.reservation_failed",
            "Resource reservation failed: %s", UiText.fromThrowable(ex)));
        failure.initCause(ex);
        throw failure;
      }
      throw new IllegalStateException("Cost reservation failed", ex);
    }
  }

  public static void refund(ServerPlayer p, UUID id) {
    var receipt = receipts(p).getCompound(id.toString());
    String state = receipt.getString("state");
    if (state.isEmpty()) return;
    if (state.equals("REFUNDED")) {
      save(p);
      return;
    }
    if (state.equals("COMMITTED"))
      throw new IllegalStateException("Cannot refund committed transaction");
    if (receipt.getBoolean("moneyUncertain"))
      throw new IllegalStateException(
          "Currency result is uncertain; audit and reconcile before retrying");
    var q = receipt.getCompound("quote");
    var refundItems = readEscrow(p, receipt);
    try {
      MaterialPayments.finish(p, id, receipt.getList("resources", Tag.TAG_COMPOUND), false);
    } catch (Exception ex) {
      throw UiText.failure(UiText.tr("resource.recovery_pending",
          "Resource recovery is pending; retain the receipt and retry recovery: %s", UiText.fromThrowable(ex)));
    }
    var custom = receipt.getList("paidCustom", Tag.TAG_COMPOUND);
    for (int i = custom.size() - 1; i >= 0; i--) {
      var n = custom.getCompound(i);
      var provider = provider(id, n.getString("id"));
      if (provider == null) throw new IllegalStateException("Recovery cost provider missing");
      provider.refund(p, id, n.getCompound("quote"));
      custom.remove(i);
    }
    try {
      if (receipt.getBoolean("moneyPaid")) {
        double
            amount =
                receipt.contains("moneyPaidAmount")
                    ? receipt.getDouble("moneyPaidAmount")
                    : q.getDouble("money"),
            before = OptionalMods.money(p);
        receipt.putDouble("moneyRefundBefore", before);
        receipt.putBoolean("moneyUncertain", true);
        OptionalMods.changeMoney(p, amount);
        if (Math.abs(OptionalMods.money(p) - before - amount) > 1e-7)
          throw new IllegalStateException("Currency refund mismatch");
        receipt.putBoolean("moneyPaid", false);
        receipt.putBoolean("moneyUncertain", false);
      }
    } catch (Exception ex) {
      save(p);
      throw new IllegalStateException("Currency refund unavailable or uncertain", ex);
    }
    if (receipt.getBoolean("xpPaid")) {
      p.giveExperiencePoints(q.getInt("xp"));
      receipt.putBoolean("xpPaid", false);
    }
    var escrow = receipt.getList("escrow", Tag.TAG_COMPOUND);
    var pending = new ListTag();
    for (int i = 0; i < escrow.size(); i++) {
      var stack = refundItems.get(i);
      p.getInventory().add(stack);
      if (!stack.isEmpty()) pending.add(stack.save(p.registryAccess()));
    }
    receipt.put("escrow", pending);
    receipt.putString("state", "REFUNDED");
    save(p);
    MaterialPayments.fault(id, "player_refund_saved");
  }

  public static void commit(ServerPlayer p, UUID id) {
    var receipt = receipts(p).getCompound(id.toString());
    if (receipt.getString("state").equals("COMMITTED")) {
      save(p);
      return;
    }
    if (!receipt.getString("state").equals("RESERVED"))
      throw new IllegalStateException("Cannot settle a missing or refunded cost receipt");
    if (receipt.contains("resources") && !receipt.getBoolean("resourcesReady"))
      throw new IllegalStateException("Resource reservation is incomplete; recovery is pending");
    try {
      MaterialPayments.finish(p, id, receipt.getList("resources", Tag.TAG_COMPOUND), true);
    } catch (Exception ex) {
      throw UiText.failure(UiText.tr("resource.recovery_pending",
          "Resource recovery is pending; retain the receipt and retry recovery: %s", UiText.fromThrowable(ex)));
    }
    var custom = receipt.getList("paidCustom", Tag.TAG_COMPOUND);
    for (int i = 0; i < custom.size(); i++) {
      var n = custom.getCompound(i);
      var provider = provider(id, n.getString("id"));
      if (provider == null) throw new IllegalStateException("Settlement provider missing");
      provider.commit(p, id, n.getCompound("quote"));
    }
    receipt.putString("state", "COMMITTED");
    receipt.put("escrow", new ListTag());
    save(p);
    MaterialPayments.fault(id, "player_commit_saved");
  }

  public static void claim(ServerPlayer p) {
    var all = receipts(p);
    for (String key : all.getAllKeys()) {
      var r = all.getCompound(key);
      if (!r.getString("state").equals("REFUNDED")) continue;
      var claimItems = readEscrow(p, r);
      var remaining = new ListTag();
      var escrow = r.getList("escrow", Tag.TAG_COMPOUND);
      for (int i = 0; i < escrow.size(); i++) {
        var stack = claimItems.get(i);
        p.getInventory().add(stack);
        if (!stack.isEmpty()) remaining.add(stack.save(p.registryAccess()));
      }
      r.put("escrow", remaining);
    }
    var mail = p.getPersistentData().getList("prefabdeploy_mail", Tag.TAG_COMPOUND);
    var left = new ListTag();
    for (int i = 0; i < mail.size(); i++) {
      var stack = ItemStack.parseOptional(p.registryAccess(), mail.getCompound(i));
      p.getInventory().add(stack);
      if (!stack.isEmpty()) left.add(stack.save(p.registryAccess()));
    }
    p.getPersistentData().put("prefabdeploy_mail", left);
    save(p);
  }

  private static CompoundTag receipts(ServerPlayer p) {
    var root = p.getPersistentData();
    if (!root.contains(RECEIPTS)) root.put(RECEIPTS, new CompoundTag());
    return root.getCompound(RECEIPTS);
  }

  private static List<ItemStack> readEscrow(ServerPlayer p, CompoundTag receipt) {
    var items = new ArrayList<ItemStack>();
    boolean strict = receipt.getCompound("quote").getInt("materialVersion") >= 1;
    for (var tag : receipt.getList("escrow", Tag.TAG_COMPOUND)) {
      var n = (CompoundTag) tag;
      if (!strict) items.add(ItemStack.parseOptional(p.registryAccess(), n));
      else {
        try {
          items.add(ItemStack.CODEC.parse(p.registryAccess().createSerializationContext(NbtOps.INSTANCE), n)
              .getOrThrow(IllegalStateException::new));
        } catch (Exception ex) {
          throw UiText.failure(UiText.tr("resource.recovery_pending",
              "Resource recovery is pending; retain the receipt and retry recovery: %s", UiText.fromThrowable(ex)));
        }
      }
    }
    return items;
  }

  public static CompoundTag auditReceipt(ServerPlayer p, UUID id) {
    var receipt = receipts(p).getCompound(id.toString()).copy();
    if (receipt.contains("resources")) receipt.put("externalReceipts",
        MaterialPayments.audit(p, id, receipt.getList("resources", Tag.TAG_COMPOUND)));
    return receipt;
  }

  public static void reconcileCurrency(ServerPlayer p, UUID id, double stillOwed) {
    var receipt = receipts(p).getCompound(id.toString());
    if (!receipt.getString("state").equals("RESERVED") || !receipt.getBoolean("moneyUncertain"))
      throw new IllegalStateException("No uncertain reserved currency transaction");
    if (!Double.isFinite(stillOwed) || stillOwed < 0)
      throw new IllegalArgumentException("Invalid remaining refund");
    receipt.putDouble("moneyPaidAmount", stillOwed);
    receipt.putBoolean("moneyPaid", stillOwed > 0);
    receipt.putBoolean("moneyUncertain", false);
    save(p);
  }

  public static void save(ServerPlayer p) {
    p.getInventory().setChanged();
    try {
      var out = new java.io.ByteArrayOutputStream();
      var n = p.saveWithoutId(new CompoundTag());
      n.putInt("DataVersion", 3955);
      n.putBoolean("prefabdeploy_transaction_file", true);
      NbtIo.writeCompressed(n, out);
      AtomicFile.writeRaw(
          p.server
              .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
              .resolve("playerdata")
              .resolve(p.getUUID() + ".dat"),
          out.toByteArray());
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("Player transaction could not be saved", ex);
    }
  }

  private Costs() {}
}
