package io.github.prefabdeploy.mixin;

import io.github.prefabdeploy.server.RegionLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Containers.class)
public abstract class ContainersMixin {
  @Inject(
      method = "dropContents(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/Container;)V",
      at = @At("HEAD"),
      cancellable = true)
  private static void prefabInventoryDrops(
      Level level, double x, double y, double z, Container inventory, CallbackInfo ci) {
    if (RegionLocks.locked(level, BlockPos.containing(x, y, z))) ci.cancel();
  }
}
