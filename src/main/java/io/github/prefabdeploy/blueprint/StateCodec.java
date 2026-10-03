package io.github.prefabdeploy.blueprint;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public final class StateCodec {
  public static BlockState read(CompoundTag nbt) {
    var id = ResourceLocation.parse(nbt.getString("Name"));
    if (!BuiltInRegistries.BLOCK.containsKey(id))
      throw new IllegalArgumentException("Unknown block: " + id);
    var block = BuiltInRegistries.BLOCK.get(id);
    var state = block.defaultBlockState();
    var props = nbt.getCompound("Properties");
    for (var name : props.getAllKeys()) {
      var property = block.getStateDefinition().getProperty(name);
      if (property == null)
        throw new IllegalArgumentException("Unknown property: " + id + "." + name);
      state = set(state, property, props.getString(name));
    }
    return state;
  }

  private static <T extends Comparable<T>> BlockState set(
      BlockState state, Property<T> property, String value) {
    return state.setValue(
        property,
        property
            .getValue(value)
            .orElseThrow(() -> new IllegalArgumentException("Invalid state value: " + value)));
  }

  public static CompoundTag write(BlockState state) {
    return NbtUtils.writeBlockState(state);
  }

  private StateCodec() {}
}
