package io.github.prefabdeploy.mixin.client;

import io.github.prefabdeploy.client.Client;
import java.util.List;
import net.minecraft.client.player.inventory.Hotbar;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Hotbar.class)
public abstract class HotbarMixin {
  @Inject(method = "load", at = @At("RETURN"), cancellable = true)
  private void prefabFilterHotbar(
      HolderLookup.Provider registries, CallbackInfoReturnable<List<ItemStack>> ci) {
    if (Client.supported()) return;
    // Filter the decoded view, preserving saved slots for the next supported connection.
    ci.setReturnValue(ci.getReturnValue().stream()
        .map(stack -> Client.unavailableItem(stack) ? ItemStack.EMPTY : stack).toList());
  }
}
