package io.github.prefabdeploy.mixin;

import io.github.prefabdeploy.server.RegionLocks;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ContainerClickMixin {
  @Shadow public ServerPlayer player;

  @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
  private void prefabDenyInventoryMutation(
      ServerboundContainerClickPacket packet, CallbackInfo ci) {
    if (player.server.isSameThread() && RegionLocks.menuBlocked(player)) {
      player.closeContainer();
      ci.cancel();
    }
  }
}
