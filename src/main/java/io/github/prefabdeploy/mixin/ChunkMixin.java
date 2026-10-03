package io.github.prefabdeploy.mixin;

import io.github.prefabdeploy.server.RegionLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(LevelChunk.class)
public abstract class ChunkMixin {
  @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
  private void prefabDirectWrite(
      BlockPos p, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> ci) {
    var chunk = (LevelChunk) (Object) this;
    if (RegionLocks.denyWrite(chunk.getLevel(), p)) ci.setReturnValue(null);
  }
}
