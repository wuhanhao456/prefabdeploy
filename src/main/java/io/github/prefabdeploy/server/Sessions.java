package io.github.prefabdeploy.server;

import io.github.prefabdeploy.*;
import io.github.prefabdeploy.compat.*;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.library.*;
import io.github.prefabdeploy.network.Network;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.Vec3;

public final class Sessions {
  private static final Map<UUID, Session> ACTIVE = new HashMap<>();
  private static final Map<UUID, Rate> RATE = new HashMap<>();

  private static final class Rate {
    long time = System.nanoTime();
    double tokens = 16;

    boolean allow() {
      long now = System.nanoTime();
      tokens = Math.min(16, tokens + (now - time) / 1e9 * 20);
      time = now;
      if (tokens < 1) return false;
      tokens--;
      return true;
    }
  }

  private static final Map<String, CompletableFuture<byte[]>> PREVIEWS = new ConcurrentHashMap<>();
  private static final ExecutorService WORKER =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "prefabdeploy-preview");
            t.setDaemon(true);
            return t;
          });

  private record Update(ServerLevel level, Iterator<BlockPos> positions) {}

  private static final ArrayDeque<Update> UPDATES = new ArrayDeque<>();
  private static final LinkedHashMap<UUID, Stream> STREAMS = new LinkedHashMap<>();

  private static final class Stream {
    final ServerPlayer player;
    final Prefab prefab;
    final byte[] bytes;
    int index;

    Stream(ServerPlayer p, Prefab f, byte[] bytes) {
      player = p;
      prefab = f;
      this.bytes = bytes;
    }
  }

  public static final class Session {
    final UUID token = UUID.randomUUID();
    final Prefab prefab;
    final boolean beacons;
    final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
    final List<BlockPos> markers = new ArrayList<>();
    GridTransform transform;
    UUID validation, deployment;
    boolean fixed, valid, deploying;
    int floatDistance = 8;
    int beaconRefundCount;

    Session(Prefab f, ServerPlayer p, boolean beacon) {
      prefab = f;
      beacons = beacon;
      dimension = p.level().dimension();
    }
  }

  public static boolean active(ServerPlayer p) {
    return ACTIVE.containsKey(p.getUUID());
  }

  public static void receive(ServerPlayer p, CompoundTag request) {
    String op = request.getString("op");
    if (p.isSpectator()
        || (!p.getMainHandItem().is(PrefabDeploy.TOOL.get())
            && !p.getMainHandItem().is(PrefabDeploy.BEACON_ITEM.get()))) return;
    if (op.equals("cancel")) {
      var s = ACTIVE.get(p.getUUID());
      if (s != null && request.hasUUID("token") && s.token.equals(request.getUUID("token")))
        requestCancel(p);
      return;
    }
    if (!RATE.computeIfAbsent(p.getUUID(), id -> new Rate()).allow()) {
      var n = Network.message("error");
      UiText.fromLegacy("Too many prefab requests; try again").message(n);
      Network.send(p, n);
      return;
    }
    try {
      if (op.equals("catalog")) {
        LocalBlueprints.refresh(p, () -> catalog(p));
        return;
      }
      if (op.equals("preview")) {
        var id = ResourceLocation.parse(request.getString("id"));
        if (!LocalBlueprints.accessible(p, id)) return;
        var f = PrefabLibrary.INSTANCE.get(id);
        var active = ACTIVE.get(p.getUUID());
        if (active != null && active.prefab.id().equals(id) && request.hasUUID("token")
            && active.token.equals(request.getUUID("token"))) f = active.prefab;
        if (f != null && f.valid() && Rules.visible(p, f)) preview(p, f);
        return;
      }
      if (op.equals("select")) {
        if (DeploymentManager.busy(p.getUUID()))
          throw new IllegalStateException("Deployment already active");
        var id = ResourceLocation.parse(request.getString("id"));
        if (!LocalBlueprints.accessible(p, id)) throw UiText.failure(
            UiText.tr("local.host_only", "Local blueprints are available only to the world host"));
        var f = PrefabLibrary.INSTANCE.get(id);
        if (f == null) throw new IllegalStateException("Unknown prefab");
        Rules.allowedResult(p, f, p.blockPosition()).require();
        cancel(p);
        var s = new Session(f, p, request.getBoolean("beacons"));
        ACTIVE.put(p.getUUID(), s);
        var n = Network.message("selected");
        n.putString("id", f.id().toString());
        n.putUUID("token", s.token);
        n.putBoolean("beacons", s.beacons);
        n.putInt("ground", f.groundY());
        n.putInt("distance", Config.RAY_DISTANCE.get());
        Network.send(p, n);
        preview(p, f);
        return;
      }
      var s = ACTIVE.get(p.getUUID());
      if (s == null
          || !request.hasUUID("token")
          || !s.token.equals(request.getUUID("token"))
          || !s.dimension.equals(p.level().dimension()))
        throw new IllegalStateException("Placement session expired");
      switch (op) {
        case "distance" ->
            s.floatDistance =
                Math.max(1, Math.min(Config.RAY_DISTANCE.get(), request.getInt("distance")));
        case "unfix" -> {
          if (DeploymentManager.busy(p.getUUID())) DeploymentManager.cancelValidation(p.getUUID());
          s.fixed = false;
          s.valid = false;
          s.validation = null;
        }
        case "fix" -> {
          if (s.fixed) return;
          if (s.beacons) {
            if (s.markers.size() != 3)
              throw new IllegalStateException("Place all three positioning beacons");
          } else {
            var anchor = BlockPos.of(request.getLong("anchor"));
            if (p.getEyePosition().distanceToSqr(Vec3.atCenterOf(anchor))
                > Math.pow(Config.RAY_DISTANCE.get() + 2, 2))
              throw new IllegalStateException("Placement beyond maximum distance");
            s.transform =
                new GridTransform(
                    anchor.getX(),
                    anchor.getY(),
                    anchor.getZ(),
                    request.getInt("turns"),
                    s.prefab.groundY());
          }
          s.fixed = true;
          s.valid = false;
          s.validation =
              DeploymentManager.submit(
                  p, s.prefab, s.transform, s.markers, true, s.beaconRefundCount);
          status(p, s, false, "Checking placement…");
        }
        case "deploy" -> {
          if (!s.fixed || !s.valid || s.transform == null)
            throw new IllegalStateException("Wait for successful placement validation");
          String denied = PrefabEvents.before(p, s.prefab);
          if (!denied.isEmpty()) throw UiText.failure(UiText.literal(denied));
          UUID id =
              DeploymentManager.submit(
                  p, s.prefab, s.transform, s.markers, false, s.beaconRefundCount);
          s.deployment = id;
          s.valid = false;
          s.deploying = true;
          var n = Network.message("deploying");
          n.putUUID("id", id);
          Network.send(p, n);
        }
        default -> throw new IllegalArgumentException("Unknown request");
      }
    } catch (Exception ex) {
      var n = Network.message("error");
      UiText.fromThrowable(ex).message(n);
      Network.send(p, n);
    }
  }

  public static void catalog(ServerPlayer p) {
    var list = new ListTag();
    for (var f : PrefabLibrary.INSTANCE.entries().values())
      if (LocalBlueprints.accessible(p, f.id()) && Rules.visible(p, f)) {
        var n = new CompoundTag();
        n.putString("id", f.id().toString());
        n.putString("name", f.name());
        n.putString("category", f.category());
        (f.id().getNamespace().equals(LocalBlueprints.NAMESPACE) && f.category().equals("local")
            ? UiText.tr("local.category", "Local blueprints") : UiText.literal(f.category()))
            .put(n, "category_display");
        n.putString("hash", f.hash());
        n.putString("error", f.error());
        n.putInt("ground", f.groundY());
        n.putString(
            "conditions",
            PrefabLibrary.string(
                f.metadata(),
                "requirements_text",
                Rules.describe(f.metadata().get("unlock"))
                    + "; "
                    + Rules.describe(f.metadata().get("conditions"))));
        (f.metadata().has("requirements_text")
                ? UiText.literal(n.getString("conditions"))
                : UiText.join(
                    List.of(
                        Rules.describeText(f.metadata().get("unlock")),
                        Rules.describeText(f.metadata().get("conditions"))),
                    false))
            .put(n, "conditions_display");
        if (f.valid()) {
          n.putInt("x", f.blueprint().width());
          n.putInt("y", f.blueprint().height());
          n.putInt("z", f.blueprint().depth());
          var locked = Rules.evaluate(f.metadata().get("unlock"), p, f, p.blockPosition());
          n.putBoolean("unlocked", locked.passed());
          var allowed = Rules.allowedResult(p, f, p.blockPosition());
          boolean canDeploy = allowed.passed();
          UiText reason = allowed.passed() ? UiText.literal("") : allowed.text();
          try {
            var quote = Costs.quoteUnchecked(p, f);
            n.putString("cost", Costs.describe(quote));
            Costs.describeText(quote).put(n, "cost_display");
            Costs.check(p, quote);
          } catch (Exception ex) {
            canDeploy = false;
            if (!n.contains("cost")) {
              n.putString("cost", ex.getMessage() == null ? "" : ex.getMessage());
              UiText.fromThrowable(ex).put(n, "cost_display");
            }
            if (allowed.passed()) reason = UiText.fromThrowable(ex);
          }
          n.putString("reason", reason.plain());
          reason.put(n, "reason_display");
          n.putBoolean("allowed", canDeploy);
        } else {
          n.putString("cost", "");
          n.putString("reason", f.error());
          UiText.fromLegacy(f.error()).put(n, "reason_display");
        }
        list.add(n);
      }
    for (var entry : list)
      if (entry.sizeInBytes() > 192 * 1024)
        throw new IllegalStateException(
            "A library entry is too large; shorten its metadata or material description");
    var start = Network.message("catalog_start");
    start.putBoolean("local_import", LocalBlueprints.allowed(p));
    Network.send(p, start);
    var page = new ListTag();
    long bytes = 0;
    for (var entry : list) {
      if (bytes + entry.sizeInBytes() > 192 * 1024 && !page.isEmpty()) {
        var message = Network.message("catalog_page");
        message.put("entries", page);
        Network.send(p, message);
        page = new ListTag();
        bytes = 0;
      }
      page.add(entry);
      bytes += entry.sizeInBytes();
    }
    if (!page.isEmpty()) {
      var message = Network.message("catalog_page");
      message.put("entries", page);
      Network.send(p, message);
    }
    Network.send(p, Network.message("catalog_end"));
  }

  private static void preview(ServerPlayer p, Prefab f) {
    var future =
        PREVIEWS.computeIfAbsent(
            f.hash(),
            hash ->
                CompletableFuture.supplyAsync(
                    () -> {
                      try {
                        var out = new ByteArrayOutputStream();
                        NbtIo.writeCompressed(f.blueprint().serialize(true), out);
                        if (out.size() > Config.MAX_BYTES.get())
                          throw new IllegalArgumentException("Preview too large");
                        return out.toByteArray();
                      } catch (IOException ex) {
                        throw new CompletionException(ex);
                      }
                    },
                    WORKER));
    future.whenComplete(
        (bytes, error) ->
            p.server.execute(
                () -> {
                  if (p.isRemoved() || !LocalBlueprints.accessible(p, f.id()) || !Rules.visible(p, f)) return;
                  if (error != null) {
                    var n = Network.message("error");
                    UiText.fromLegacy("Preview generation failed").message(n);
                    Network.send(p, n);
                    return;
                  }
                  STREAMS.put(p.getUUID(), new Stream(p, f, bytes));
                  long total =
                      PREVIEWS.values().stream()
                          .filter(v -> v.isDone() && !v.isCompletedExceptionally())
                          .mapToLong(v -> v.join().length)
                          .sum();
                  if (PREVIEWS.size() > 32 || total > 128L * 1024 * 1024)
                    PREVIEWS.keySet().removeIf(key -> !key.equals(f.hash()));
                }));
  }

  public static String canPlaceMarker(ServerPlayer p, BlockPos pos) {
    var s = ACTIVE.get(p.getUUID());
    if (s == null || !s.beacons) return "";
    if (s.fixed || s.markers.size() >= 3) return "Positioning is already complete";
    if (!s.dimension.equals(p.level().dimension())) return "Placement dimension changed";
    if (s.markers.isEmpty()) return "";
    var a = s.markers.getFirst();
    if (pos.getY() != a.getY()) return "Beacons must share the reference height";
    if (s.markers.size() == 1)
      return GridTransform.direction(
                  pos.getX() - a.getX(), pos.getZ() - a.getZ(), s.prefab.blueprint().width())
              < 0
          ? "B must be exactly " + s.prefab.blueprint().width() + " blocks along one axis"
          : "";
    var expected = s.transform.cornerC(s.prefab.blueprint().depth());
    return pos.equals(new BlockPos(expected.x(), expected.y(), expected.z()))
        ? ""
        : "C must mark the perpendicular Z boundary (distance "
            + s.prefab.blueprint().depth()
            + ")";
  }

  public static void markerPlaced(ServerPlayer p, BlockPos pos) {
    markerPlaced(p, pos, p.getAbilities().instabuild ? 0 : 1);
  }

  public static void markerPlaced(ServerPlayer p, BlockPos pos, int consumed) {
    var s = ACTIVE.get(p.getUUID());
    if (s == null || !s.beacons) return;
    s.markers.add(pos.immutable());
    s.beaconRefundCount += Math.max(0, Math.min(1, consumed));
    if (s.markers.size() == 2) {
      var a = s.markers.getFirst();
      int turns =
          GridTransform.direction(
              pos.getX() - a.getX(), pos.getZ() - a.getZ(), s.prefab.blueprint().width());
      s.transform = new GridTransform(a.getX(), a.getY(), a.getZ(), turns, s.prefab.groundY());
    }
    markers(p, s);
  }

  public static int floatDistance(ServerPlayer p) {
    var s = ACTIVE.get(p.getUUID());
    return s == null ? 8 : s.floatDistance;
  }

  public static void markerBroken(BlockPos pos, ServerLevel level) {
    for (var entry : new ArrayList<>(ACTIVE.entrySet())) {
      var s = entry.getValue();
      if (s.dimension.equals(level.dimension()) && s.markers.contains(pos)) {
        var p = level.getServer().getPlayerList().getPlayer(entry.getKey());
        if (p != null) {
          cancel(p);
          var n = Network.message("error");
          UiText.fromLegacy("A positioning beacon was removed; placement cancelled").message(n);
          Network.send(p, n);
        }
      }
    }
  }

  private static void markers(ServerPlayer p, Session s) {
    var n = Network.message("markers");
    n.putLongArray("positions", s.markers.stream().mapToLong(BlockPos::asLong).toArray());
    if (s.transform != null) {
      n.putInt("turns", s.transform.turns());
      n.putLong("anchor", new BlockPos(s.transform.x(), s.transform.y(), s.transform.z()).asLong());
    }
    Network.send(p, n);
  }

  public static void validation(UUID owner, UUID task, boolean valid, String text) {
    validation(owner, task, valid, UiText.fromLegacy(text));
  }

  public static void validation(UUID owner, UUID task, boolean valid, UiText text) {
    var s = ACTIVE.get(owner);
    if (s == null || !task.equals(s.validation)) return;
    s.valid = valid;
    var p =
        net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()
            .getPlayerList()
            .getPlayer(owner);
    if (p != null) status(p, s, valid, text);
  }

  private static void status(ServerPlayer p, Session s, boolean valid, String text) {
    status(p, s, valid, UiText.fromLegacy(text));
  }

  private static void status(ServerPlayer p, Session s, boolean valid, UiText text) {
    var n = Network.message("status");
    n.putBoolean("valid", valid);
    text.message(n);
    if (s.transform != null) {
      n.putLong("anchor", new BlockPos(s.transform.x(), s.transform.y(), s.transform.z()).asLong());
      n.putInt("turns", s.transform.turns());
    }
    Network.send(p, n);
  }

  public static void finished(UUID owner, UUID task, boolean success, String text) {
    finished(owner, task, success, UiText.fromLegacy(text));
  }

  public static void finished(UUID owner, UUID task, boolean success, UiText text) {
    var session = ACTIVE.get(owner);
    if (session != null && !task.equals(session.deployment)) {
      var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
      var p = server == null ? null : server.getPlayerList().getPlayer(owner);
      if (p != null) p.displayClientMessage(text.component(), false);
      return;
    }
    ACTIVE.remove(owner);
    var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
    if (server != null) {
      var p = server.getPlayerList().getPlayer(owner);
      if (p != null) {
        var n = Network.message("finished");
        n.putBoolean("success", success);
        text.message(n);
        Network.send(p, n);
      }
    }
  }

  public static void cancel(ServerPlayer p) {
    DeploymentManager.cancelValidation(p.getUUID());
    ACTIVE.remove(p.getUUID());
    Network.send(p, Network.message("cancelled"));
  }

  public static void requestCancel(ServerPlayer p) {
    var session = ACTIVE.get(p.getUUID());
    if (session == null) return;
    if (session.deploying) {
      var n = Network.message("notice");
      UiText.fromLegacy("Construction has started; cancellation is unavailable").message(n);
      Network.send(p, n);
      return;
    }
    cancel(p);
  }

  public static void logout(ServerPlayer p) {
    ACTIVE.remove(p.getUUID());
    RATE.remove(p.getUUID());
    STREAMS.remove(p.getUUID());
    DeploymentManager.cancelValidation(p.getUUID());
  }

  public static void clear() {
    ACTIVE.clear();
    RATE.clear();
    PREVIEWS.clear();
    UPDATES.clear();
    STREAMS.clear();
  }

  public static void pollMarkers(net.minecraft.server.MinecraftServer server) {
    if (server.getTickCount() % 20 != 0) return;
    for (var entry : new ArrayList<>(ACTIVE.entrySet())) {
      var s = entry.getValue();
      if (s.deploying || s.markers.isEmpty()) continue;
      var level = server.getLevel(s.dimension);
      if (level == null) continue;
      for (var pos : s.markers)
        if (level.hasChunkAt(pos) && !level.getBlockState(pos).is(PrefabDeploy.BEACON.get())) {
          var p = server.getPlayerList().getPlayer(entry.getKey());
          if (p != null) {
            cancel(p);
            var n = Network.message("error");
            UiText.fromLegacy("Positioning beacon missing; placement cancelled").message(n);
            Network.send(p, n);
          }
          break;
        }
    }
  }

  public static void queueUpdates(ServerLevel level, List<BlockPos> positions) {
    UPDATES.addLast(new Update(level, positions.iterator()));
  }

  public static boolean hasUpdates() {
    return !UPDATES.isEmpty();
  }

  public static void pumpUpdates(long deadline, int limit) {
    int sent = 0;
    for (var iterator = STREAMS.entrySet().iterator();
        iterator.hasNext() && sent < Math.min(4, limit) && System.nanoTime() < deadline; ) {
      var stream = iterator.next().getValue();
      if (stream.player.isRemoved() || !Rules.visible(stream.player, stream.prefab)) {
        iterator.remove();
        continue;
      }
      int pieces = (stream.bytes.length + 49151) / 49152, i = stream.index++;
      var n = Network.message("fragment");
      n.putString("id", stream.prefab.id().toString());
      n.putString("hash", stream.prefab.hash());
      n.putInt("index", i);
      n.putInt("count", pieces);
      n.putByteArray(
          "bytes",
          Arrays.copyOfRange(
              stream.bytes, i * 49152, Math.min(stream.bytes.length, (i + 1) * 49152)));
      Network.send(stream.player, n);
      sent++;
      if (stream.index == pieces) iterator.remove();
    }
    for (int i = sent; i < limit && System.nanoTime() < deadline && !UPDATES.isEmpty(); i++) {
      var update = UPDATES.getFirst();
      if (!update.positions.hasNext()) {
        UPDATES.removeFirst();
        continue;
      }
      var pos = update.positions.next();
      try {
        var state = update.level.getBlockState(pos);
        update.level.blockUpdated(pos, state.getBlock());
        state.updateNeighbourShapes(update.level, pos, 3);
      } catch (Exception ex) {
        PrefabDeploy.LOGGER.error("Post-deployment world update failed at {}", pos, ex);
      }
    }
  }

  private Sessions() {}
}
