package io.github.prefabdeploy.mixin.client;

import io.github.prefabdeploy.client.Client;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Never submit client-only prefab items to a server that cannot decode them. */
@Mixin(MultiPlayerGameMode.class)
public abstract class CreativeItemMixin {
  @Inject(method = "handleCreativeModeItemAdd", at = @At("HEAD"), cancellable = true)
  private void prefabGuardCreativeSlot(ItemStack stack, int slot, CallbackInfo ci) {
    if (Client.unavailableItem(stack)) ci.cancel();
  }

  @Inject(method = "handleCreativeModeItemDrop", at = @At("HEAD"), cancellable = true)
  private void prefabGuardCreativeDrop(ItemStack stack, CallbackInfo ci) {
    if (Client.unavailableItem(stack)) ci.cancel();
  }
}
