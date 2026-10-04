package io.github.prefabdeploy.mixin;

import io.github.prefabdeploy.server.*;
import java.io.File;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No optional Mod classes are linked. Only the three resource storage classes are intercepted. */
@Mixin(SavedData.class)
public abstract class ResourceSavedDataMixin implements ResourceReceiptAccess {
  @Unique private CompoundTag prefabReceipts = new CompoundTag();

  public CompoundTag prefabResourceReceipts() { return prefabReceipts; }
  public void prefabResourceReceipts(CompoundTag receipts) { prefabReceipts = receipts; }

  @Inject(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V",
      at = @At("HEAD"), cancellable = true)
  private void prefabSave(File file, HolderLookup.Provider registries, CallbackInfo ci) {
    var data = (SavedData) (Object) this;
    if (!ResourceReceiptStorage.supported(data)) return;
    if (data.isDirty()) ResourceReceiptStorage.save(data, file.toPath(), registries);
    ci.cancel();
  }
}
