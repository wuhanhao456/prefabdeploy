package io.github.prefabdeploy.mixin.client;

import io.github.prefabdeploy.client.Client;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CreativeModeTabs.class)
public abstract class CreativeTabsMixin {
  @Shadow private static CreativeModeTab.ItemDisplayParameters CACHED_PARAMETERS;
  @Unique private static Boolean prefabSupported;

  @Inject(method = "tryRebuildTabContents", at = @At("HEAD"))
  private static void prefabRefreshAvailability(
      FeatureFlagSet features, boolean permissions, HolderLookup.Provider registries,
      CallbackInfoReturnable<Boolean> ci) {
    boolean supported = Client.supported();
    if (prefabSupported == null || prefabSupported != supported) {
      CACHED_PARAMETERS = null;
      prefabSupported = supported;
    }
  }
}
