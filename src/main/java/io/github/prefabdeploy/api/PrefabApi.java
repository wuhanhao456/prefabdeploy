package io.github.prefabdeploy.api;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PrefabApi {
  public static final List<BlueprintImporter> IMPORTERS = new CopyOnWriteArrayList<>();
  public static final Map<String, PlacementRule> RULES =
      new java.util.concurrent.ConcurrentHashMap<>();
  public static final Map<String, CostProvider> COSTS =
      new java.util.concurrent.ConcurrentHashMap<>();
  public static final List<NbtTransformAdapter> NBT_ADAPTERS = new CopyOnWriteArrayList<>();

  private PrefabApi() {}
}
