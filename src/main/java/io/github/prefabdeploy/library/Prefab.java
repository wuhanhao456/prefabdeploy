package io.github.prefabdeploy.library;

import com.google.gson.JsonObject;
import io.github.prefabdeploy.blueprint.Blueprint;
import net.minecraft.resources.ResourceLocation;

public record Prefab(
    ResourceLocation id,
    String name,
    String category,
    int groundY,
    Blueprint blueprint,
    JsonObject metadata,
    String hash,
    String error) {
  public boolean valid() {
    return blueprint != null && error.isEmpty();
  }

  public Prefab withMetadata(JsonObject data) {
    return new Prefab(
        id,
        PrefabLibrary.string(data, "name", name),
        PrefabLibrary.string(data, "category", category),
        data.has("ground_y") ? data.get("ground_y").getAsInt() : groundY,
        blueprint,
        data.deepCopy(),
        hash,
        error);
  }
}
