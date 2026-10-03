package io.github.prefabdeploy.mixin;

import io.github.prefabdeploy.server.RegionLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Block.class)
public abstract class DropMixin {
  @Inject(method = "popResource", at = @At("HEAD"), cancellable = true)
  private static void prefabNoDrop(Level level, BlockPos pos, ItemStack stack, CallbackInfo ci) {
    if (RegionLocks.locked(level, pos)) ci.cancel();
  }
}
