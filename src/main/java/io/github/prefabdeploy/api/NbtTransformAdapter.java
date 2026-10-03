package io.github.prefabdeploy.api;

import io.github.prefabdeploy.core.GridTransform;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public interface NbtTransformAdapter {
  boolean accepts(BlockState state);

  void transform(BlockState state, CompoundTag nbt, GridTransform transform);

  /** Called for each transformed entity, including passengers. Check its id before editing. */
  default void transformEntity(CompoundTag nbt, GridTransform transform) {}

  default CompoundTag previewData(BlockState state, CompoundTag source) {
    return new CompoundTag();
  }
}
