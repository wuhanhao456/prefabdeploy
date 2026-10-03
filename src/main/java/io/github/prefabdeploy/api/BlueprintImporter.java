package io.github.prefabdeploy.api;

import io.github.prefabdeploy.blueprint.Blueprint;
import net.minecraft.nbt.CompoundTag;

public interface BlueprintImporter {
  boolean accepts(String extension);

  Blueprint read(CompoundTag source, int positionLimit);
}
