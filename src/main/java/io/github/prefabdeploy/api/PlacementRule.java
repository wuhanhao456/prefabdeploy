package io.github.prefabdeploy.api;

import io.github.prefabdeploy.library.Prefab;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

public interface PlacementRule {
  /** Empty text allows the action; nonempty text is a player-facing failure reason. */
  String check(ServerPlayer player, Prefab prefab, BlockPos anchor);
}
