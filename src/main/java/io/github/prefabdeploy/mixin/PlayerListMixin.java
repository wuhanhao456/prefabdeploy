package io.github.prefabdeploy.mixin;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Integrated servers normally prefer level.dat Player, which may predate a durable receipt. */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
  @Inject(method = "load", at = @At("HEAD"))
  private void prefabCanonicalPlayerFile(
      ServerPlayer player, CallbackInfoReturnable<Optional<CompoundTag>> ci) {
    if (!player.server.isSingleplayerOwner(player.getGameProfile())) return;
    var world = player.server.getWorldData().getLoadedPlayerTag();
    if (world == null) return;
    var path =
        player
            .server
            .getWorldPath(LevelResource.ROOT)
            .resolve("playerdata")
            .resolve(player.getUUID() + ".dat");
    if (!java.nio.file.Files.exists(path)) return;
    try {
      var n = NbtIo.readCompressed(path, NbtAccounter.create(64L * 1024 * 1024));
      if (n.getBoolean("prefabdeploy_transaction_file")
          || n.getCompound("NeoForgeData").contains("prefabdeploy_receipts")) {
        for (String key : new ArrayList<>(world.getAllKeys())) world.remove(key);
        world.merge(n);
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot load canonical prefab transaction player data", ex);
    }
  }
}
