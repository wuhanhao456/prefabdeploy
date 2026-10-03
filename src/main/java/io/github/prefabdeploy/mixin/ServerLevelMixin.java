package io.github.prefabdeploy.mixin;

import io.github.prefabdeploy.server.RegionLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
  @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
  private void prefabRandom(LevelChunk chunk, int speed, CallbackInfo ci) {
    if (RegionLocks.locked((ServerLevel) (Object) this, chunk.getPos().toLong())) ci.cancel();
  }

  @Inject(method = "tickBlock", at = @At("HEAD"), cancellable = true)
  private void prefabBlock(BlockPos pos, Block block, CallbackInfo ci) {
    var level = (ServerLevel) (Object) this;
    if (RegionLocks.locked(level, pos)) {
      level.scheduleTick(pos, block, 1);
      ci.cancel();
    }
  }

  @Inject(method = "tickFluid", at = @At("HEAD"), cancellable = true)
  private void prefabFluid(BlockPos pos, Fluid fluid, CallbackInfo ci) {
    var level = (ServerLevel) (Object) this;
    if (RegionLocks.locked(level, pos)) {
      level.scheduleTick(pos, fluid, 1);
      ci.cancel();
    }
  }

  @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
  private void prefabEntity(Entity entity, CallbackInfo ci) {
    if (!(entity instanceof Player)
        && RegionLocks.locked((ServerLevel) (Object) this, entity.blockPosition())) ci.cancel();
  }
}
