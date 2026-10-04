package io.github.prefabdeploy.mixin;

import io.github.prefabdeploy.server.ResourceReceiptStorage;
import java.util.function.BiFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(DimensionDataStorage.class)
public abstract class ResourceDataStorageMixin {
  @Redirect(method = "readSavedData", at = @At(value = "INVOKE",
      target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
  private Object prefabLoad(BiFunction<CompoundTag, HolderLookup.Provider, SavedData> reader,
      Object contents, Object registries) {
    var data = reader.apply((CompoundTag) contents, (HolderLookup.Provider) registries);
    ResourceReceiptStorage.loaded(data, (CompoundTag) contents);
    return data;
  }
}
