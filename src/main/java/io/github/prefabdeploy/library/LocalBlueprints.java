package io.github.prefabdeploy.library;

import com.google.gson.JsonObject;
import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.UiText;
import io.github.prefabdeploy.network.Network;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

/** Local disk access belongs to the integrated server owner, including the LAN host. */
public final class LocalBlueprints {
  public static final String NAMESPACE = "prefabdeploy_local";
  private static final ExecutorService READER = Executors.newSingleThreadExecutor(r -> {
    var t = new Thread(r, "prefabdeploy-local-blueprints");
    t.setDaemon(true);
    return t;
  });
  private record Cached(String digest, Prefab prefab) {}
  public record Snapshot(Map<ResourceLocation, Prefab> entries, String fingerprint) {}
  private static final Map<ResourceLocation, Cached> CACHE = new HashMap<>();
  private static long cachedRevision = -1;
  private static volatile long epoch;
  private static String published = "";
  private static long publishedRevision = -1;
  private static CompletableFuture<Snapshot> pending;
  private static long pendingRevision = -1;

  public static Path directory() {
    return FMLPaths.GAMEDIR.get().resolve("prefabdeploy/blueprints").toAbsolutePath().normalize();
  }

  public static boolean allowed(ServerPlayer player) {
    return allowed(player.server.isDedicatedServer(), player.server.isSingleplayerOwner(player.getGameProfile()));
  }

  public static boolean allowed(boolean dedicated, boolean owner) { return !dedicated && owner; }

  public static boolean accessible(ServerPlayer player, ResourceLocation id) {
    return !id.getNamespace().equals(NAMESPACE) || allowed(player);
  }

  public static ResourceLocation id(Path relative) {
    return ResourceLocation.fromNamespaceAndPath(NAMESPACE, digest(relative.normalize().toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8)));
  }

  private static String digest(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }

  /** Executes only on READER; callers used by tests also run it serially. */
  public static Snapshot scan(Path root, long revision) throws IOException {
    if (revision != cachedRevision) { CACHE.clear(); cachedRevision = revision; }
    Files.createDirectories(root);
    Path realRoot = root.toRealPath();
    var entries = new LinkedHashMap<ResourceLocation, Prefab>();
    var seen = new HashSet<ResourceLocation>();
    var files = new ArrayList<Path>();
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        return Files.exists(dir.resolve("pack.mcmeta"), LinkOption.NOFOLLOW_LINKS)
            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
      }
      @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (attrs.isRegularFile()) files.add(file);
        return FileVisitResult.CONTINUE;
      }
    });
    for (var file : files.stream().sorted().toList()) {
      String filename = file.getFileName().toString();
      int dot = filename.lastIndexOf('.');
      if (dot < 0) continue;
      String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
      if (!Set.of("nbt", "litematic").contains(extension)) continue;
      try { if (!file.toRealPath().startsWith(realRoot)) continue; }
      catch (IOException ex) { PrefabDeploy.LOGGER.warn("Local blueprint disappeared while scanning: {}", file); continue; }
      var id = id(root.relativize(file));
      var meta = new JsonObject();
      meta.addProperty("name", filename.substring(0, dot));
      meta.addProperty("category", "local");
      meta.addProperty("source", NAMESPACE + ":blueprints/" + id.getPath() + "." + extension);
      meta.addProperty("ground_y", 0);
      meta.addProperty("ignore_air", false);
      var cost = new JsonObject(); cost.addProperty("mode", "auto"); meta.add("cost", cost);
      Prefab prefab;
      try (var input = Files.newInputStream(file)) {
        byte[] bytes = input.readNBytes(PrefabLibrary.byteLimit() + 1);
        String hash = digest(bytes);
        var cached = CACHE.get(id);
        if (cached != null && cached.digest.equals(hash)) prefab = cached.prefab;
        else {
          try { prefab = PrefabLibrary.decode(id, meta, bytes); }
          catch (Exception ex) {
            PrefabDeploy.LOGGER.warn("Unable to import local blueprint {}", file, ex);
            String reason = ex instanceof IOException ? "Unable to read blueprint data" : ex.getMessage();
            prefab = new Prefab(id, meta.get("name").getAsString(), "local", 0, null, meta, hash,
                reason == null ? "Unable to read blueprint data" : reason);
          }
          CACHE.put(id, new Cached(hash, prefab));
        }
      } catch (IOException ex) {
        PrefabDeploy.LOGGER.warn("Unable to read local blueprint {}", file, ex);
        prefab = new Prefab(id, meta.get("name").getAsString(), "local", 0, null, meta, "", "Unable to read blueprint data");
      }
      seen.add(id); entries.put(id, prefab);
    }
    CACHE.keySet().retainAll(seen);
    String fingerprint = digest(entries.values().stream().map(f -> f.id() + ":" + f.hash() + ":" + f.error()).reduce("", (a,b) -> a + "\n" + b).getBytes(StandardCharsets.UTF_8));
    return new Snapshot(Collections.unmodifiableMap(entries), fingerprint);
  }

  /** Request and publication happen on the server thread; parsing never runs there. */
  public static void refresh(ServerPlayer player, Runnable ready) {
    refresh(player, ready, error -> {
      if (player.isRemoved()) return;
      var n = Network.message("notice");
      UiText.tr("local.folder_read_failed", "Unable to read the blueprint folder").message(n);
      Network.send(player, n);
      ready.run();
    });
  }

  public static void refresh(ServerPlayer player, Runnable ready, java.util.function.Consumer<Throwable> failed) {
    if (!allowed(player)) { ready.run(); return; }
    MinecraftServer server = player.server;
    long currentEpoch = epoch, revision = PrefabLibrary.INSTANCE.revision();
    if (pending == null || pending.isDone() || pendingRevision != revision) {
      pendingRevision = revision;
      pending = CompletableFuture.supplyAsync(() -> {
        try { return scan(directory(), revision); }
        catch (IOException ex) { throw new CompletionException(ex); }
      }, READER);
    }
    pending.whenComplete((snapshot, error) -> server.execute(() -> {
      if (epoch != currentEpoch || player.isRemoved() || !allowed(player)) {
        failed.accept(new CancellationException("Local blueprint owner is no longer available"));
        return;
      }
      if (revision != PrefabLibrary.INSTANCE.revision()) { refresh(player, ready, failed); return; }
      if (error != null) {
        PrefabDeploy.LOGGER.warn("Unable to scan local blueprint folder", error);
        failed.accept(error);
        return;
      } else if (publishedRevision != revision || !published.equals(snapshot.fingerprint)) {
        try {
          PrefabLibrary.INSTANCE.publishLocal(snapshot.entries);
          published = snapshot.fingerprint; publishedRevision = revision;
        } catch (Exception ex) {
          PrefabDeploy.LOGGER.error("Unable to publish local blueprint definitions", ex);
          failed.accept(UiText.failure(UiText.tr("local.registry_failed", "Unable to apply blueprint scripts; check the server log")));
          return;
        }
      }
      ready.run();
    }));
  }

  public static void clear() {
    epoch++; pending = null; pendingRevision = -1; published = ""; publishedRevision = -1;
    READER.execute(() -> { CACHE.clear(); cachedRevision = -1; });
  }

  private LocalBlueprints() {}
}
