package io.github.prefabdeploy.blueprint;

import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

/** Reject missing registry content in conventional saved ItemStacks before any world changes. */
public final class NbtContentCheck {
  private static final Set<String> STACK_FIELDS =
      Set.of(
          "Items",
          "Inventory",
          "ArmorItems",
          "HandItems",
          "Item",
          "item",
          "SelectedItem",
          "minecraft:bundle_contents",
          "minecraft:charged_projectiles");

  public static void validate(CompoundTag source) {
    visit(source, false, 0);
  }

  private static void visit(Tag tag, boolean stack, int depth) {
    if (depth > 64) throw new IllegalArgumentException("NBT content nesting exceeds 64 levels");
    if (tag instanceof CompoundTag n) {
      if (stack
          && n.contains("id", Tag.TAG_STRING)
          && (n.contains("count", Tag.TAG_ANY_NUMERIC)
              || n.contains("Count", Tag.TAG_ANY_NUMERIC)
              || n.contains("Slot", Tag.TAG_ANY_NUMERIC))) {
        var id = ResourceLocation.parse(n.getString("id"));
        if (!BuiltInRegistries.ITEM.containsKey(id))
          throw new IllegalArgumentException("Unknown stored item: " + id);
        var components = n.getCompound("components");
        for (var key : components.getAllKeys()) {
          var component = ResourceLocation.parse(key.startsWith("!") ? key.substring(1) : key);
          if (!BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(component))
            throw new IllegalArgumentException("Unknown stored item component: " + component);
        }
      }
      for (var key : n.getAllKeys()) visit(n.get(key), STACK_FIELDS.contains(key), depth + 1);
    } else if (tag instanceof ListTag list) for (var child : list) visit(child, stack, depth + 1);
  }

  private NbtContentCheck() {}
}
