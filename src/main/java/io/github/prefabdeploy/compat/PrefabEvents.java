package io.github.prefabdeploy.compat;

import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.library.Prefab;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class PrefabEvents {
  public static void registry(Map<ResourceLocation, Prefab> definitions) {
    if (OptionalMods.loaded("kubejs")) KubeBridge.registry(definitions);
  }

  public static String before(ServerPlayer p, Prefab f) {
    return OptionalMods.loaded("kubejs") ? KubeBridge.before(p, f) : "";
  }

  public static void completed(ServerPlayer p, Prefab f, UUID id, boolean success) {
    try {
      if (OptionalMods.loaded("kubejs")) KubeBridge.completed(p, f, id, success);
    } catch (Exception ex) {
      PrefabDeploy.LOGGER.error("Prefab completion script failed", ex);
    }
  }

  private PrefabEvents() {}
}
