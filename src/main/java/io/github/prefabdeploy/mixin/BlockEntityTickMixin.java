package io.github.prefabdeploy.mixin;

import io.github.prefabdeploy.server.RegionLocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BlockEntityTickMixin {
  @Shadow @Final private BlockEntity blockEntity;

  @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
  private void prefabPause(CallbackInfo ci) {
    if (blockEntity.getLevel() != null
        && RegionLocks.tickBlocked(blockEntity.getLevel(), blockEntity.getBlockPos())) ci.cancel();
  }
}
