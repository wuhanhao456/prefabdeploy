package io.github.prefabdeploy.mixin;

import io.github.prefabdeploy.compat.BackpackWrapperCache;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper", remap = false)
public abstract class ResourceBackpackWrapperMixin {
  @Inject(method = "<init>", at = @At("RETURN"))
  private void prefabTrack(CallbackInfo ci) { BackpackWrapperCache.track(this); }
}
