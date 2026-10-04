package io.github.prefabdeploy.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.UiText;
import io.github.prefabdeploy.blueprint.Blueprint;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.network.Network;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.*;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

public final class Client {
  private static final Minecraft MC = Minecraft.getInstance();
  public static List<CompoundTag> catalog = List.of();
  public static boolean localImportAllowed;
  private static final LinkedHashMap<String, PreviewMesh> ASSETS =
      new LinkedHashMap<>(16, .75f, true);
  private static final Map<String, Transfer> TRANSFERS = new HashMap<>();
  private static final Map<String, String> HASHES = new HashMap<>();
  private static int assetEpoch;
  private static final ExecutorService DECODER =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "prefabdeploy-client-decode");
            t.setDaemon(true);
            return t;
          });

  private record Transfer(String id, byte[][] pieces, long started) {}

  public static String selected = "", text = "";
  private static Component displayText = Component.empty();
  public static UUID token;
  public static boolean beacons, fixed, valid, checking, deploying;
  public static int ground, turns, maxDistance = 32, floatDistance = 8;
  public static BlockPos anchor = BlockPos.ZERO;
  public static List<BlockPos> markers = List.of();
  private static KeyMapping rotate;
  private static final ArrayDeque<Long> FRAME_SAMPLES = new ArrayDeque<>();

  public static void init(IEventBus bus) {
    Network.SERVER_SUPPORTED = Client::supported;
    Network.CLIENT = Client::receive;
    Network.PLACEMENT = () -> supported() && token != null;
    Network.TOOL =
        a -> {
          if (!supported()) return;
          if (a.equals("cancel")) cancelPlacement();
          else tool();
        };
    bus.addListener(
        (RegisterKeyMappingsEvent e) -> {
          rotate =
              new KeyMapping(
                  "key.prefabdeploy.rotate", GLFW.GLFW_KEY_R, "key.categories.prefabdeploy");
          e.register(rotate);
        });
    bus.addListener(
        (RegisterClientReloadListenersEvent e) ->
            e.registerReloadListener(
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                    manager ->
                        MC.execute(
                            () -> {
                              clearAssets();
                              if (token != null) requestPreview(selected);
                            })));
    NeoForge.EVENT_BUS.addListener(Client::tick);
    NeoForge.EVENT_BUS.addListener(Client::renderWorld);
    NeoForge.EVENT_BUS.addListener(Client::hud);
    NeoForge.EVENT_BUS.addListener(
        (InputEvent.InteractionKeyMappingTriggered e) -> {
          if (supported()
              && e.isUseItem()
              && e.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
              && token != null
              && MC.player != null
              && (MC.player.getMainHandItem().is(PrefabDeploy.TOOL.get())
                  || (MC.player.isShiftKeyDown()
                      && MC.player.getMainHandItem().is(PrefabDeploy.BEACON_ITEM.get())))) {
            tool();
            e.setCanceled(true);
            e.setSwingHand(false);
          }
        });
    NeoForge.EVENT_BUS.addListener(
        (InputEvent.MouseScrollingEvent e) -> {
          if (supported()
              && token != null
              && beacons
              && MC.player != null
              && MC.player.isShiftKeyDown()
              && MC.player.getMainHandItem().is(PrefabDeploy.BEACON_ITEM.get())) {
            floatDistance =
                Math.max(
                    1,
                    Math.min(maxDistance, floatDistance + (int) Math.signum(e.getScrollDeltaY())));
            var n = request("distance");
            n.putInt("distance", floatDistance);
            Network.request(n);
            e.setCanceled(true);
          }
        });
    NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> clearConnection());
    NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> clearConnection());
  }

  public static boolean supported() {
    var connection = MC.getConnection();
    return connection != null && connection.getConnection().isConnected()
        && connection.hasChannel(Network.Message.TYPE);
  }

  public static boolean unavailableItem(net.minecraft.world.item.ItemStack stack) {
    return !supported() && containsPrefabItem(stack);
  }

  private static boolean containsPrefabItem(net.minecraft.world.item.ItemStack stack) {
    if (stack.isEmpty()) return false;
    if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
        .getNamespace().equals(PrefabDeploy.ID)) return true;
    var container = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
    if (container != null && container.stream().anyMatch(Client::containsPrefabItem)) return true;
    var bundle = stack.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS);
    if (bundle != null)
      for (var item : bundle.items()) if (containsPrefabItem(item)) return true;
    var projectiles = stack.get(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES);
    return projectiles != null && projectiles.getItems().stream().anyMatch(Client::containsPrefabItem);
  }

  private static void clearConnection() {
    reset();
    clearAssets();
    catalog = List.of();
    localImportAllowed = false;
    if (MC.screen instanceof LibraryScreen) MC.setScreen(null);
  }

  public static PreviewMesh mesh(String id) {
    return ASSETS.get(id);
  }

  static void installAsset(String id, Blueprint bp) {
    var old = ASSETS.put(id, new PreviewMesh(bp));
    if (old != null) old.close();
    while (ASSETS.size() > 8) {
      var key = ASSETS.keySet().iterator().next();
      ASSETS.remove(key).close();
    }
  }

  static void recordFrame(long start) {
    FRAME_SAMPLES.addLast(System.nanoTime() - start);
    if (FRAME_SAMPLES.size() > 1200) FRAME_SAMPLES.removeFirst();
  }

  static void resetMetrics() {
    FRAME_SAMPLES.clear();
  }

  static com.google.gson.JsonObject metricData() {
    var n = new com.google.gson.JsonObject();
    var a = FRAME_SAMPLES.stream().mapToLong(Long::longValue).sorted().toArray();
    n.addProperty("samples", a.length);
    if (a.length > 0) {
      n.addProperty("cpu_p95_ms", a[Math.min(a.length - 1, (int) (a.length * .95))] / 1e6);
      n.addProperty("cpu_max_ms", a[a.length - 1] / 1e6);
    }
    return n;
  }

  private static CompoundTag request(String op) {
    var n = Network.message(op);
    if (token != null) n.putUUID("token", token);
    return n;
  }

  private static void send(String op) {
    Network.request(request(op));
  }

  public static void choose(CompoundTag entry, boolean beacon) {
    if (!supported()) return;
    var n = Network.message("select");
    n.putString("id", entry.getString("id"));
    n.putBoolean("beacons", beacon);
    Network.request(n);
  }

  public static void requestPreview(String id) {
    if (!supported()) return;
    if (!ASSETS.containsKey(id)) {
      var n = Network.message("preview");
      n.putString("id", id);
      if (token != null && id.equals(selected)) n.putUUID("token", token);
      Network.request(n);
    }
  }

  private static void tool() {
    if (!supported()) return;
    if (MC.player != null && MC.player.isShiftKeyDown() && token != null) {
      cancelPlacement();
      return;
    }
    if (token == null) {
      Network.request(Network.message("catalog"));
      return;
    }
    if (beacons && markers.size() < 3) { cancelPlacement(); return; }
    var mesh = mesh(selected);
    if (mesh == null || !mesh.ready()) return;
    var bp = mesh.blueprint();
    var transform = new GridTransform(anchor.getX(), anchor.getY(), anchor.getZ(), turns, ground);
    var start = MC.player.getEyePosition();
    if (!io.github.prefabdeploy.core.PreviewTarget.hit(
        io.github.prefabdeploy.core.PreviewTarget.bounds(transform, bp.width(), bp.height(), bp.depth()),
        start, start.add(MC.player.getLookAngle().scale(maxDistance)))) {
      cancelPlacement();
      return;
    }
    if (deploying) return;
    if (!fixed) {
      if (beacons && markers.size() != 3) return;
      fixed = true;
      checking = true;
      valid = false;
      var n = request("fix");
      n.putLong("anchor", anchor.asLong());
      n.putInt("turns", turns);
      Network.request(n);
    } else if (valid) {
      send("deploy");
      deploying = true;
    } else if (!checking && !beacons) {
      fixed = false;
      text = "";
      displayText = Component.empty();
      send("unfix");
    }
  }

  private static void cancelPlacement() {
    if (token == null) return;
    send("cancel");
  }

  private static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
    if (MC.player == null) return;
    if (rotate != null)
      while (rotate.consumeClick())
        if (supported() && token != null && !fixed && !beacons && MC.screen == null)
          turns = Math.floorMod(turns + 1, 4);
    if (!supported()) return;
    if (token != null && !fixed && !beacons) anchor = rayAnchor();
    TRANSFERS.entrySet().removeIf(e -> System.nanoTime() - e.getValue().started > 30_000_000_000L);
  }

  public static BlockPos rayAnchor() {
    var start = MC.player.getEyePosition();
    var end = start.add(MC.player.getLookAngle().scale(maxDistance));
    var hit =
        MC.level.clip(
            new ClipContext(
                start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
    return hit.getType() == HitResult.Type.MISS
        ? BlockPos.containing(end)
        : hit.getBlockPos().relative(hit.getDirection());
  }

  public static BlockPos beaconCandidate() {
    return MC.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
        ? hit.getBlockPos().relative(hit.getDirection())
        : BlockPos.containing(
            MC.player.getEyePosition().add(MC.player.getLookAngle().scale(floatDistance)));
  }

  private static void receive(CompoundTag n) {
    if (!supported()) return;
    switch (n.getString("op")) {
      case "catalog_start" -> {
        catalog = List.of();
        localImportAllowed = n.getBoolean("local_import");
      }
      case "catalog", "catalog_page" -> {
        var entries = n.getList("entries", Tag.TAG_COMPOUND);
        var list = new ArrayList<CompoundTag>(catalog);
        for (int i = 0; i < entries.size(); i++) {
          var entry = entries.getCompound(i);
          String id = entry.getString("id"), hash = entry.getString("hash");
          if (HASHES.containsKey(id) && !HASHES.get(id).equals(hash)) {
            var old = ASSETS.remove(id);
            if (old != null) old.close();
          }
          HASHES.put(id, hash);
          list.add(entry);
        }
        catalog = List.copyOf(list);
        if (n.getString("op").equals("catalog")) MC.setScreen(new LibraryScreen());
      }
      case "catalog_end" -> MC.setScreen(new LibraryScreen());
      case "selected" -> {
        reset();
        selected = n.getString("id");
        token = n.getUUID("token");
        beacons = n.getBoolean("beacons");
        ground = n.getInt("ground");
        maxDistance = n.getInt("distance");
        MC.setScreen(null);
      }
      case "fragment" -> fragment(n);
      case "markers" -> {
        markers = Arrays.stream(n.getLongArray("positions")).mapToObj(BlockPos::of).toList();
        if (!markers.isEmpty()) anchor = markers.getFirst();
        if (n.contains("anchor")) {
          anchor = BlockPos.of(n.getLong("anchor"));
          turns = n.getInt("turns");
        }
      }
      case "status" -> {
        valid = n.getBoolean("valid");
        checking = false;
        text = n.getString("text");
        displayText = UiText.read(n, "text_display", "text").component();
        if (n.contains("anchor")) {
          anchor = BlockPos.of(n.getLong("anchor"));
          turns = n.getInt("turns");
        }
      }
      case "deploying" -> {
        deploying = true;
        text = net.minecraft.client.resources.language.I18n.get("prefabdeploy.deploying");
        displayText = Component.translatable("prefabdeploy.deploying");
      }
      case "cancelled" -> reset();
      case "notice" -> {
        if (MC.player != null)
          MC.player.displayClientMessage(UiText.read(n, "text_display", "text").component(), true);
      }
      case "finished" -> {
        if (MC.player != null)
          MC.player.displayClientMessage(
              Component.translatable(
                  n.getBoolean("success") ? "prefabdeploy.completed" : "prefabdeploy.failed",
                  UiText.read(n, "text_display", "text").component()),
              false);
        reset();
      }
      case "error" -> {
        text = n.getString("text");
        displayText = UiText.read(n, "text_display", "text").component();
        checking = false;
        deploying = false;
        if (MC.player != null) MC.player.displayClientMessage(displayText, true);
      }
      default -> {}
    }
  }

  private static void fragment(CompoundTag n) {
    String id = n.getString("id"), hash = n.getString("hash");
    int count = n.getInt("count"), index = n.getInt("index");
    if (count < 1 || count > 1366 || index < 0 || index >= count) return;
    byte[] bytes = n.getByteArray("bytes");
    if (bytes.length > 49152) return;
    if (HASHES.containsKey(id) && !HASHES.get(id).equals(hash)) return;
    if (TRANSFERS.size() >= 8 && !TRANSFERS.containsKey(hash)) return;
    var transfer =
        TRANSFERS.computeIfAbsent(
            hash, h -> new Transfer(id, new byte[count][], System.nanoTime()));
    if (!transfer.id.equals(id) || transfer.pieces.length != count) return;
    transfer.pieces[index] = bytes;
    for (byte[] part : transfer.pieces) if (part == null) return;
    TRANSFERS.remove(hash);
    int total = Arrays.stream(transfer.pieces).mapToInt(a -> a.length).sum();
    if (total > 64 * 1024 * 1024) return;
    int epoch = assetEpoch;
    var decoded =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                var out = new ByteArrayOutputStream(total);
                for (byte[] part : transfer.pieces) out.write(part);
                try (var in = new ByteArrayInputStream(out.toByteArray())) {
                  return Blueprint.deserialize(
                      NbtIo.readCompressed(in, NbtAccounter.create(64L * 1024 * 1024)), 5_000_000);
                }
              } catch (IOException ex) {
                throw new CompletionException(ex);
              }
            },
            DECODER);
    decoded.whenComplete(
        (bp, error) ->
            MC.execute(
                () -> {
                  if (epoch != assetEpoch || MC.level == null) return;
                  if (error != null) {
                    text = "Preview decode failed: " + error.getMessage();
                    displayText =
                        Component.translatable(
                            "prefabdeploy.preview_decode_failed",
                            UiText.fromThrowable(error).component());
                    return;
                  }
                  installAsset(id, bp);
                }));
  }

  private static void renderWorld(RenderLevelStageEvent e) {
    if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
        || !supported()
        || token == null
        || MC.level == null) return;
    long started = System.nanoTime();
    var mesh = mesh(selected);
    if (mesh != null) mesh.build(started + 2_000_000L);
    var camera = e.getCamera().getPosition();
    var pose = new PoseStack();
    // RenderSystem already supplies the camera view to both VBO and line shaders.
    var buffers = MC.renderBuffers().bufferSource();
    float r = valid ? .2f : fixed && !checking ? 1f : 1f,
        g = valid ? 1f : fixed && !checking ? .2f : .8f,
        b = .2f;
    if (!beacons || markers.size() == 3) {
      pose.pushPose();
      pose.translate(anchor.getX() - camera.x, anchor.getY() - camera.y, anchor.getZ() - camera.z);
      pose.mulPose(Axis.YP.rotationDegrees(-90 * turns));
      pose.translate(0, -ground, 0);
      if (mesh != null) {
        var t = new GridTransform(anchor.getX(), anchor.getY(), anchor.getZ(), turns, ground);
        mesh.draw(
            pose,
            e.getProjectionMatrix(),
            .35f,
            box -> e.getFrustum().isVisible(transformed(box, t)));
        var bp = mesh.blueprint();
        LevelRenderer.renderLineBox(
            pose,
            buffers.getBuffer(RenderType.lines()),
            new AABB(0, 0, 0, bp.width(), bp.height(), bp.depth()),
            r,
            g,
            b,
            1);
        LevelRenderer.renderLineBox(
            pose,
            buffers.getBuffer(RenderType.lines()),
            new AABB(0, ground, 0, bp.width(), ground + .015, bp.depth()),
            .25f,
            .65f,
            1,
            1);
        float arrow = Math.max(2, Math.min(6, bp.width() / 2f));
        line(pose, 0, ground + .05f, 0, arrow, ground + .05f, 0, 1, .35f, .3f);
        line(pose, arrow, ground + .05f, 0, arrow - 1, ground + .05f, 1, 1, .35f, .3f);
        line(pose, arrow, ground + .05f, 0, arrow - 1, ground + .05f, -1, 1, .35f, .3f);
        line(pose, 0, ground + .05f, 0, 0, ground + .05f, Math.min(6, bp.depth()), .3f, .65f, 1);
      }
      pose.popPose();
    }
    if (beacons && !markers.isEmpty() && mesh != null) {
      var a = markers.getFirst();
      int[] directions = markers.size() == 1 ? new int[] {0, 1, 2, 3} : new int[] {turns};
      for (int direction : directions) {
        var t = new GridTransform(a.getX(), a.getY(), a.getZ(), direction, ground);
        var c =
            markers.size() == 1
                ? t.cornerB(mesh.blueprint().width())
                : t.cornerC(mesh.blueprint().depth());
        pose.pushPose();
        pose.translate(a.getX() - camera.x, a.getY() - camera.y, a.getZ() - camera.z);
        float cx = c.x() - a.getX(), cz = c.z() - a.getZ();
        LevelRenderer.renderLineBox(
            pose,
            buffers.getBuffer(RenderType.lines()),
            new AABB(cx, 0, cz, cx + 1, 1, cz + 1),
            .2f,
            1,
            .8f,
            1);
        line(pose, .5f, .5f, .5f, cx + .5f, .5f, cz + .5f, .2f, 1, .8f);
        pose.popPose();
      }
    }
    buffers.endBatch(RenderType.lines());
    FRAME_SAMPLES.addLast(System.nanoTime() - started);
    if (FRAME_SAMPLES.size() > 1200) FRAME_SAMPLES.removeFirst();
  }

  private static void line(
      PoseStack pose,
      float x1,
      float y1,
      float z1,
      float x2,
      float y2,
      float z2,
      float r,
      float g,
      float b) {
    var normal = new org.joml.Vector3f(x2 - x1, y2 - y1, z2 - z1).normalize();
    var vertex = MC.renderBuffers().bufferSource().getBuffer(RenderType.lines());
    vertex
        .addVertex(pose.last(), x1, y1, z1)
        .setColor(r, g, b, 1)
        .setNormal(pose.last(), normal.x, normal.y, normal.z);
    vertex
        .addVertex(pose.last(), x2, y2, z2)
        .setColor(r, g, b, 1)
        .setNormal(pose.last(), normal.x, normal.y, normal.z);
  }

  private static AABB transformed(AABB box, GridTransform t) {
    var a = t.point(box.minX, box.minY, box.minZ);
    var b = t.point(box.maxX, box.maxY, box.maxZ);
    return new AABB(
        Math.min(a.x(), b.x()),
        Math.min(a.y(), b.y()),
        Math.min(a.z(), b.z()),
        Math.max(a.x(), b.x()),
        Math.max(a.y(), b.y()),
        Math.max(a.z(), b.z()));
  }

  private static void hud(RenderGuiEvent.Post e) {
    if (!supported() || token == null || MC.player == null) return;
    var gui = e.getGuiGraphics();
    int width = MC.getWindow().getGuiScaledWidth();
    record HudLine(Component text, int color) {}
    var messages = new ArrayList<HudLine>();
    String message =
        deploying
            ? "prefabdeploy.deploying"
            : beacons && markers.size() < 3
                ? "prefabdeploy.beacon_help"
                : mesh(selected) == null || !mesh(selected).ready() ? "prefabdeploy.loading_preview"
                : fixed ? "prefabdeploy.fixed_help" : "prefabdeploy.follow_help";
    messages.add(new HudLine(Component.translatable(message), 0xffffffff));
    messages.add(
        new HudLine(
            Component.translatable("prefabdeploy.anchor", anchor.toShortString(), turns * 90),
            0xffffa34b));
    if (!text.isEmpty()) messages.add(new HudLine(displayText, valid ? 0xff8cffae : 0xffffffff));
    if (beacons && markers.size() > 0 && markers.size() < 3) {
      var a = markers.getFirst();
      var pos = beaconCandidate();
      var mesh = mesh(selected);
      if (mesh != null) {
        int wanted = markers.size() == 1 ? mesh.blueprint().width() : mesh.blueprint().depth();
        int distance = Math.abs(pos.getX() - a.getX()) + Math.abs(pos.getZ() - a.getZ());
        boolean axis =
            markers.size() == 1
                ? GridTransform.direction(pos.getX() - a.getX(), pos.getZ() - a.getZ(), wanted) >= 0
                : new GridTransform(a.getX(), a.getY(), a.getZ(), turns, ground)
                    .cornerC(wanted)
                    .equals(new GridTransform.Cell(pos.getX(), pos.getY(), pos.getZ()));
        boolean ok = axis && pos.getY() == a.getY();
        messages.add(
            new HudLine(
                Component.translatable(
                    "prefabdeploy.beacon_metrics",
                    markers.size() == 1 ? "B" : "C",
                    distance,
                    wanted,
                    pos.getY(),
                    a.getY(),
                    Component.translatable(
                        ok ? "prefabdeploy.position_valid" : "prefabdeploy.position_invalid")),
                ok ? 0xff8cffae : 0xffff8080));
      }
    }
    record WrappedLine(net.minecraft.util.FormattedCharSequence text, int color) {}
    var lines = new ArrayList<WrappedLine>();
    for (var row : messages) {
      var wrapped = MC.font.split(row.text(), Math.max(1, width - 40));
      // Detailed errors remain in chat; keep the placement overlay compact.
      for (int i = 0; i < Math.min(3, wrapped.size()); i++)
        lines.add(new WrappedLine(wrapped.get(i), row.color()));
    }
    int boxHeight = lines.size() * 11 + 16;
    int top = Math.max(8, MC.getWindow().getGuiScaledHeight() - 36 - boxHeight);
    LibraryScreen.rounded(gui, 8, top, width - 16, boxHeight, 0xac8b949f);
    gui.fill(13, top + 8, 15, top + boxHeight - 8, 0xffffa34b);
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      gui.drawString(MC.font, line.text(), 21, top + 8 + i * 11, line.color(), true);
    }
  }

  public static String metrics() {
    if (FRAME_SAMPLES.isEmpty()) return "";
    var samples = FRAME_SAMPLES.stream().mapToLong(Long::longValue).sorted().toArray();
    return String.format(
        Locale.ROOT,
        "Preview p95 %.3f ms",
        samples[Math.min(samples.length - 1, (int) (samples.length * .95))] / 1e6);
  }

  private static void reset() {
    token = null;
    selected = "";
    beacons = false;
    fixed = false;
    valid = false;
    checking = false;
    deploying = false;
    markers = List.of();
    turns = 0;
    ground = 0;
    anchor = BlockPos.ZERO;
    maxDistance = 32;
    floatDistance = 8;
    text = "";
    displayText = Component.empty();
  }

  private static void clearAssets() {
    assetEpoch++;
    ASSETS.values().forEach(PreviewMesh::close);
    ASSETS.clear();
    TRANSFERS.clear();
    HASHES.clear();
  }

  private Client() {}
}
