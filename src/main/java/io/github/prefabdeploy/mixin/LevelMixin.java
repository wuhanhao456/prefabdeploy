package io.github.prefabdeploy.mixin;

import io.github.prefabdeploy.server.RegionLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(Level.class)
public abstract class LevelMixin {
  @Inject(
      method =
          "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
      at = @At("HEAD"),
      cancellable = true)
  private void prefabWrite(
      BlockPos p, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> ci) {
    if (RegionLocks.denyWrite((Level) (Object) this, p)) ci.setReturnValue(false);
  }

  @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
  private void prefabBlockEntity(BlockEntity be, CallbackInfo ci) {
    if (RegionLocks.denyWrite((Level) (Object) this, be.getBlockPos())) ci.cancel();
  }

  @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
  private void prefabRemove(BlockPos p, CallbackInfo ci) {
    if (RegionLocks.denyWrite((Level) (Object) this, p)) ci.cancel();
  }
}
