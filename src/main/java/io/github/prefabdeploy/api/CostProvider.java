package io.github.prefabdeploy.api;

import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

public interface CostProvider {
  /** Providers in v1 must mutate only data saved in the player's own NBT file. */
  boolean atomicWithPlayerSave();

  CompoundTag quote(ServerPlayer player, JsonObject specification);

  void reserve(ServerPlayer player, UUID transaction, CompoundTag quote);

  void refund(ServerPlayer player, UUID transaction, CompoundTag quote);

  default void commit(ServerPlayer player, UUID transaction, CompoundTag quote) {}
}
