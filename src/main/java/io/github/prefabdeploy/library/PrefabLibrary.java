package io.github.prefabdeploy.library;

import com.google.gson.*;
import io.github.prefabdeploy.Config;
import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.api.PrefabApi;
import io.github.prefabdeploy.blueprint.*;
import io.github.prefabdeploy.compat.PrefabEvents;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;

public final class PrefabLibrary
    extends SimplePreparableReloadListener<PrefabLibrary.Prepared> {
  public record Prepared(Map<ResourceLocation, Prefab> external, Map<ResourceLocation, Prefab> defaults) {}
  public static final PrefabLibrary INSTANCE = new PrefabLibrary();
  private volatile Map<ResourceLocation, Prefab> entries = Map.of();
  private Map<ResourceLocation, Prefab> baseEntries = Map.of(), localEntries = Map.of();
  private Map<ResourceLocation, Prefab> defaultEntries = Map.of();
  private volatile long revision;


  public Map<ResourceLocation, Prefab> entries() {
    return entries;
  }

  public Prefab get(ResourceLocation id) {
    return entries.get(id);
  }

  @Override
  protected Prepared prepare(
      ResourceManager manager, ProfilerFiller profiler) {
    var result = new LinkedHashMap<ResourceLocation, Prefab>();
    var defaults = new LinkedHashMap<ResourceLocation, Prefab>();
    manager
        .listResources("prefabs", p -> p.getPath().endsWith(".json"))
        .forEach(
            (path, resource) -> {
              var target = resource.sourcePackId().equals(BlueprintPacks.BUILTIN_ID) ? defaults : result;
              var id =
                  ResourceLocation.fromNamespaceAndPath(
                      path.getNamespace(),
                      path.getPath().substring(8, path.getPath().length() - 5));
              JsonObject meta = new JsonObject();
              try (var reader = resource.openAsReader()) {
                meta = JsonParser.parseReader(reader).getAsJsonObject();
                var source = ResourceLocation.parse(meta.get("source").getAsString());
                try (var stream = manager.getResourceOrThrow(source).open()) {
                  target.put(id, decode(id, meta, stream.readNBytes(byteLimit() + 1)));
                }
              } catch (Exception ex) {
                PrefabDeploy.LOGGER.error("Unable to load prefab {}", id, ex);
                target.put(
                    id,
                    new Prefab(
                        id,
                        string(meta, "name", id.toString()),
                        string(meta, "category", "general"),
                        0,
                        null,
                        meta,
                        "",
                        ex.getMessage() == null ? ex.toString() : ex.getMessage()));
              }
            });
    return new Prepared(Map.copyOf(result), Map.copyOf(defaults));
  }

  static int byteLimit() {
    return Config.SPEC.isLoaded() ? Config.MAX_BYTES.get() : 256 * 1024 * 1024;
  }

  static Prefab decode(ResourceLocation id, JsonObject meta, byte[] bytes) throws Exception {
    var source = ResourceLocation.parse(meta.get("source").getAsString());
    String extension =
        source.getPath().substring(source.getPath().lastIndexOf('.') + 1);
    var importer =
        PrefabApi.IMPORTERS.stream()
            .filter(i -> i.accepts(extension))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Unsupported blueprint format: " + extension));
    CompoundTag root;
    int byteLimit = Config.SPEC.isLoaded() ? Config.MAX_BYTES.get() : 256 * 1024 * 1024;
    int positionLimit = Config.SPEC.isLoaded() ? Config.MAX_POSITIONS.get() : 5_000_000;
    if (bytes.length > byteLimit)
      throw new IllegalArgumentException("Compressed blueprint too large");
    try (var stream = new ByteArrayInputStream(bytes)) {
      root = NbtIo.readCompressed(stream, NbtAccounter.create(byteLimit));
    }
    int version =
        root.contains("MinecraftDataVersion")
            ? root.getInt("MinecraftDataVersion")
            : root.getInt("DataVersion");
    if (version != 3955)
      throw new IllegalArgumentException(
          "Blueprint must use Minecraft 1.21.1 DataVersion 3955; found " + version);
    var bp = importer.read(root, positionLimit);
    for (var voxel : bp.voxels())
      if (voxel.nbt() != null) NbtContentCheck.validate(voxel.nbt());
    for (var actor : bp.entities()) NbtContentCheck.validate(actor.nbt());
    if (meta.has("ignore_air") && meta.get("ignore_air").getAsBoolean())
      bp =
          new Blueprint(
              bp.width(),
              bp.height(),
              bp.depth(),
              bp.voxels().stream().filter(v -> !v.state().isAir()).toList(),
              bp.entities(),
              bp.ticks());
    if (bp.voxels().isEmpty())
      throw new IllegalArgumentException("Blueprint has no affected positions");
    if (bp.entities().size() > 4096 || bp.ticks().size() > positionLimit)
      throw new IllegalArgumentException("Entity/tick limit exceeded");
    var entityIds = new HashSet<UUID>();
    var entityCount = new int[1];
    for (var e : bp.entities()) validateEntity(e.nbt(), entityIds, entityCount, 0);
    for (var v : bp.voxels())
      if (v.nbt() != null) {
        var type =
            net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                .getOptional(ResourceLocation.parse(v.nbt().getString("id")))
                .orElseThrow(
                    () -> new IllegalArgumentException("Unknown block entity"));
        if (!type.isValid(v.state()))
          throw new IllegalArgumentException(
              "Block entity is incompatible with block at " + v.pos());
      }
    for (var tick : bp.ticks()) {
      if (tick.pos().getX() < 0
          || tick.pos().getY() < 0
          || tick.pos().getZ() < 0
          || tick.pos().getX() >= bp.width()
          || tick.pos().getY() >= bp.height()
          || tick.pos().getZ() >= bp.depth())
        throw new IllegalArgumentException("Tick outside blueprint bounds");
      var type = ResourceLocation.parse(tick.type());
      if (tick.fluid()
          ? !net.minecraft.core.registries.BuiltInRegistries.FLUID.containsKey(type)
          : !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(type))
        throw new IllegalArgumentException("Unknown scheduled tick type");
    }
    meta.addProperty("_source_bytes", bytes.length);
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update(bytes);
    digest.update(meta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String hash = HexFormat.of().formatHex(digest.digest());
    io.github.prefabdeploy.server.Costs.warm(bp);
    return new Prefab(
            id,
            string(meta, "name", id.toString()),
            string(meta, "category", "general"),
            meta.has("ground_y") ? meta.get("ground_y").getAsInt() : 0,
            bp,
            meta,
            hash,
            "");
  }

  public long revision() { return revision; }

  void publishLocal(Map<ResourceLocation, Prefab> local) {
    localEntries = Map.copyOf(local);
    rebuild();
  }

  private void rebuild() {
    var mutable = new LinkedHashMap<ResourceLocation, Prefab>();
    // Initial world resource loading precedes SERVER config loading. Never publish defaults early.
    if (Config.SPEC.isLoaded() && Config.ENABLE_DEFAULT_TEST_BUILDINGS.get()) mutable.putAll(defaultEntries);
    mutable.putAll(baseEntries);
    localEntries.forEach(mutable::putIfAbsent);
    PrefabEvents.registry(mutable);
    entries = Collections.unmodifiableMap(mutable);
  }

  @Override
  protected void apply(
      Prepared prepared, ResourceManager manager, ProfilerFiller profiler) {
    baseEntries = prepared.external();
    defaultEntries = prepared.defaults();
    localEntries = Map.of();
    revision++;
    rebuild();
    PrefabDeploy.LOGGER.info("Loaded {} prefab definitions", entries.size());
  }

  /** Called on the server thread after the world's SERVER config has loaded, before players join. */
  public void configurationReady() {
    revision++;
    rebuild();
    PrefabDeploy.LOGGER.info("Loaded {} prefab definitions after server configuration", entries.size());
  }

  public static String string(JsonObject o, String key, String fallback) {
    return o.has(key) ? o.get(key).getAsString() : fallback;
  }

  private static void validateEntity(CompoundTag n, Set<UUID> ids, int[] count, int depth) {
    if (depth > 16 || ++count[0] > 4096)
      throw new IllegalArgumentException("Entity hierarchy limit exceeded");
    if (!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(
        ResourceLocation.parse(n.getString("id"))))
      throw new IllegalArgumentException("Unknown entity: " + n.getString("id"));
    if (n.hasUUID("UUID") && !ids.add(n.getUUID("UUID")))
      throw new IllegalArgumentException("Duplicate entity UUID in blueprint");
    var passengers = n.getList("Passengers", Tag.TAG_COMPOUND);
    for (int i = 0; i < passengers.size(); i++)
      validateEntity(passengers.getCompound(i), ids, count, depth + 1);
  }
}
