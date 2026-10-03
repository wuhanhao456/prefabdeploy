package io.github.prefabdeploy.client;

import com.google.gson.*;
import io.github.prefabdeploy.*;
import io.github.prefabdeploy.blueprint.*;
import io.github.prefabdeploy.network.Network;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Development-only real client smoke/benchmark runner; excluded from the distributable JAR. */
@EventBusSubscriber(modid = PrefabDeploy.ID, value = Dist.CLIENT)
public final class ClientSmoke {
  private static final Minecraft MC = Minecraft.getInstance();
  private static final JsonObject REPORT = new JsonObject();
  private static final JsonArray RUNS = new JsonArray();
  private static int stage, ticks, benchmark;
  private static int rotationClicks;
  private static int outsidePhase;
  private static long beaconTime;
  private static long started;
  private static CompletableFuture<Blueprint> pending;
  private static CompoundTag gallery;
  private static final int[] COUNTS = {10000, 50000, 100000};
  private static volatile java.util.UUID nativeJob;
  private static volatile JsonObject nativeResult;
  private static volatile RuntimeException serverFailure;
  private static long tickStart, firstTick, lastTick;
  private static final List<Long> SERVER_TICKS = new ArrayList<>();

  @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
  public static void beforeServer(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre event) {
    if (nativeJob != null) tickStart = System.nanoTime();
  }

  @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
  public static void afterServer(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
    if (nativeJob == null) return;
    long now = System.nanoTime();
    if (SERVER_TICKS.isEmpty()) firstTick = tickStart;
    lastTick = tickStart;
    SERVER_TICKS.add(now - tickStart);
    var outcome = io.github.prefabdeploy.server.DeploymentManager.outcome(nativeJob);
    if (outcome.isPresent() && !io.github.prefabdeploy.server.Sessions.hasUpdates()) {
      var n = io.github.prefabdeploy.server.DeploymentManager.metricData();
      n.addProperty("deployment_success", outcome.get());
      var ticks = SERVER_TICKS.stream().mapToLong(Long::longValue).sorted().toArray();
      n.addProperty(
          "whole_server_tick_p95_ms",
          ticks[Math.min(ticks.length - 1, (int) (ticks.length * .95))] / 1e6);
      n.addProperty(
          "observed_tps",
          Math.min(20, (SERVER_TICKS.size() - 1) * 1e9 / Math.max(1, lastTick - firstTick)));
      n.addProperty("server_ticks", SERVER_TICKS.size());
      n.addProperty("final_updates_drained", true);
      nativeResult = n;
      nativeJob = null;
    }
  }

  private static Path directory() {
    return Path.of(System.getProperty("prefabdeploy.reportDir", "reports"));
  }

  private static void screenshot(String name) {
    Screenshot.grab(
        directory().toFile(),
        name,
        MC.getMainRenderTarget(),
        c -> PrefabDeploy.LOGGER.info("Smoke screenshot: {}", c.getString()));
  }

  @SubscribeEvent
  public static void tick(ClientTickEvent.Post event) {
    if (!Boolean.getBoolean("prefabdeploy.clientSmoke")) return;
    try {
      if (serverFailure != null)
        throw new IllegalStateException("Server smoke step failed", serverFailure);
      if (MC.player == null || MC.level == null) {
        if (++ticks % 200 == 0)
          PrefabDeploy.LOGGER.info("Client smoke waiting for world: {}", MC.screen);
        return;
      }
      if (Boolean.getBoolean("prefabdeploy.modelReloadSmoke")) {
        reloadModels();
        return;
      }
      ticks++;
      if (stage > 0 && ticks > 6000)
        throw new IllegalStateException("Client smoke timed out in stage " + stage);
      if (stage >= 4 && stage <= 6 && MC.screen == null) {
        MC.player.setYRot(180);
        MC.player.setXRot(24);
        MC.player.yRotO = 180;
        MC.player.xRotO = 24;
        MC.getToasts().clear();
      }
      if (ticks % 100 == 0)
        PrefabDeploy.LOGGER.info(
            "CLIENT SMOKE stage={} screen={} hand={} text={}",
            stage,
            MC.screen,
            MC.player.getMainHandItem(),
            Client.text);
      switch (stage) {
        case 0 -> {
          Files.createDirectories(directory());
          REPORT.addProperty("java", System.getProperty("java.version"));
          REPORT.addProperty(
              "mod_version",
              net.neoforged.fml.ModList.get()
                  .getModContainerById(PrefabDeploy.ID)
                  .orElseThrow()
                  .getModInfo()
                  .getVersion()
                  .toString());
          REPORT.addProperty(
              "neoforge",
              net.neoforged.fml.ModList.get()
                  .getModContainerById("neoforge")
                  .orElseThrow()
                  .getModInfo()
                  .getVersion()
                  .toString());
          REPORT.addProperty(
              "renderer", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
          REPORT.addProperty("width", MC.getWindow().getWidth());
          REPORT.addProperty("height", MC.getWindow().getHeight());
          REPORT.addProperty("language", MC.options.languageCode);
          if (System.getProperty("prefabdeploy.shaderSmoke") != null) {
            var iris = Class.forName("net.irisshaders.iris.Iris");
            String expected = System.getProperty("prefabdeploy.shaderSmoke");
            boolean active = (boolean)iris.getMethod("isPackInUseQuick").invoke(null);
            if (!expected.equals("off") && (!active || (boolean)iris.getMethod("isFallback").invoke(null)))
              throw new IllegalStateException("Requested shader is inactive or using fallback");
            REPORT.addProperty("shader_pack", (String)iris.getMethod("getCurrentPackName").invoke(null));
            REPORT.addProperty("shader_active", active);
            REPORT.addProperty("iris", (String)iris.getMethod("getVersion").invoke(null));
            REPORT.addProperty("sodium", net.neoforged.fml.ModList.get().getModContainerById("sodium").orElseThrow().getModInfo().getVersion().toString());
          }
          REPORT.addProperty(
              "measurement",
              "400+ cached preview frames; CPU submission and asynchronous GPU timestamp duration");
          REPORT.add("runs", RUNS);
          MC.options.enableVsync().set(false);
          MC.options.framerateLimit().set(200);
          MC.options.pauseOnLostFocus = false;
          var owner = MC.player.getUUID();
          serverExecute(
              () -> {
                var p = MC.getSingleplayerServer().getPlayerList().getPlayer(owner);
                p.setGameMode(GameType.CREATIVE);
                p.getInventory().setItem(0, new ItemStack(PrefabDeploy.TOOL.get()));
                p.getInventory().setItem(1, new ItemStack(Items.EMERALD, 64));
                p.getInventory().selected = 0;
                p.getAbilities().flying = true;
                p.onUpdateAbilities();
                p.setYRot(180);
                p.setXRot(18);
                p.teleportTo(7, -53, 20);
                try {
                  var local = io.github.prefabdeploy.library.LocalBlueprints.directory();
                  Files.createDirectories(local.resolve("客户端"));
                  for (var name : List.of("cottage.nbt", "cellar.litematic")) {
                    try (var input = p.server.getResourceManager().getResourceOrThrow(
                        net.minecraft.resources.ResourceLocation.parse("prefabdeploy:blueprints/" + name)).open()) {
                      Files.write(local.resolve("客户端").resolve(name), input.readAllBytes());
                    }
                  }
                } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
              });
          stage = 1;
        }
        case 1 -> {
          if (MC.player.getMainHandItem().is(PrefabDeploy.TOOL.get())) {
            Network.request(Network.message("catalog"));
            stage = 2;
          }
        }
        case 2 -> {
          if (MC.screen instanceof LibraryScreen screen) {
            if (!Client.localImportAllowed || Client.catalog.stream()
                .filter(n -> n.getString("id").startsWith("prefabdeploy_local:")).count() != 2)
              throw new IllegalStateException("Local host catalog not imported");
            REPORT.addProperty("local_host_import_verified", true);
            gallery =
                Client.catalog.stream()
                    .filter(n -> n.getString("id").equals("prefabdeploy:nbt_gallery"))
                    .findFirst()
                    .orElseThrow();
            screen.select(gallery);
            stage = 3;
            ticks = 0;
          }
        }
        case 3 -> {
          var mesh = Client.mesh(gallery.getString("id"));
          if (mesh != null && mesh.ready() && ticks > 60) {
            MC.getToasts().clear();
            screenshot("gallery-library.png");
            if (!UiText.read(gallery, "cost_display", "cost")
                .key()
                .equals("prefabdeploy.cost.creative"))
              throw new IllegalStateException("Creative GUI did not receive a free quote");
            var screen = (LibraryScreen) MC.screen;
            screen.mouseDragged(400, 140, 0, 20, 8);
            screen.mouseScrolled(400, 140, 0, 1);
            var folder = screen.children().stream()
                .filter(c -> c instanceof net.minecraft.client.gui.components.Button b
                    && b.getMessage().getString().equals(net.minecraft.client.resources.language.I18n.get("prefabdeploy.local.open_folder")))
                .map(c -> (net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow();
            if (!folder.active || folder.getTooltip() == null) throw new IllegalStateException("Host folder button missing");
            Client.localImportAllowed = false;
            MC.setScreen(new LibraryScreen());
            var disabled = ((LibraryScreen)MC.screen).children().stream()
                .filter(c -> c instanceof net.minecraft.client.gui.components.Button b
                    && b.getMessage().getString().equals(net.minecraft.client.resources.language.I18n.get("prefabdeploy.local.open_folder")))
                .map(c -> (net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow();
            if (disabled.active || disabled.getTooltip() == null) throw new IllegalStateException("Server folder button enabled");
            stage = 50; ticks = 0;
          }
        }
        case 50 -> {
          if (ticks > 20) {
            screenshot("folder-button-disabled.png");
            Client.localImportAllowed = true;
            MC.setScreen(new LibraryScreen());
            ((LibraryScreen)MC.screen).select(gallery);
            REPORT.addProperty("folder_button_capability_verified", true);
            serverExecute(() -> {
              var server = MC.getSingleplayerServer();
              if (!server.publishServer(GameType.CREATIVE, false, 0)) throw new IllegalStateException("LAN publication failed");
              var host = server.getPlayerList().getPlayer(MC.player.getUUID());
              var guest = net.neoforged.neoforge.common.util.FakePlayerFactory.get(host.serverLevel(),
                  new com.mojang.authlib.GameProfile(UUID.randomUUID(), "012_lan_guest"));
              var localId = io.github.prefabdeploy.library.LocalBlueprints.id(Path.of("客户端/cottage.nbt"));
              if (!server.isPublished() || !io.github.prefabdeploy.library.LocalBlueprints.allowed(host)
                  || io.github.prefabdeploy.library.LocalBlueprints.accessible(guest, localId))
                throw new IllegalStateException("LAN local access policy failed");
              guest.getInventory().setItem(0, new ItemStack(PrefabDeploy.TOOL.get()));
              var select = Network.message("select"); select.putString("id", localId.toString());
              io.github.prefabdeploy.server.Sessions.receive(guest, select);
              if (io.github.prefabdeploy.server.Sessions.active(guest)) throw new IllegalStateException("Guest selected host blueprint");
              REPORT.addProperty("lan_host_and_guest_access_verified", true);
            });
            MC.options.guiScale().set(3);
            MC.resizeDisplay();
            stage = 28;
            ticks = 0;
          }
        }
        case 28 -> {
          if (ticks > 40) {
            screenshot("gallery-library-small.png");
            REPORT.addProperty("small_gui_width", MC.getWindow().getGuiScaledWidth());
            REPORT.addProperty("small_gui_height", MC.getWindow().getGuiScaledHeight());
            MC.options.guiScale().set(2);
            MC.resizeDisplay();
            Client.choose(gallery, false);
            rotationClicks = 0;
            stage = 29;
            ticks = 0;
          }
        }
        case 29 -> {
          if (Client.token != null && ticks > 20) {
            if (ticks % 10 == 1 && rotationClicks < 4) {
              KeyMapping.click(
                  com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(
                      org.lwjgl.glfw.GLFW.GLFW_KEY_R));
              rotationClicks++;
            }
            if (ticks % 10 == 5 && rotationClicks > 0) {
              if (Client.turns != rotationClicks % 4)
                throw new IllegalStateException("R did not cycle clockwise: " + Client.turns);
              if (rotationClicks == 4) {
                REPORT.addProperty("r_four_direction_cycle_verified", true);
                cancelInput();
                stage = 30;
                ticks = 0;
              }
            }
          }
        }
        case 30 -> {
          if (Client.token == null && ticks > 10) {
            REPORT.addProperty("shift_right_click_follow_cancel_verified", true);
            Client.choose(gallery, false);
            stage = 31;
            ticks = 0;
          }
        }
        case 31 -> {
          if (Client.token != null && ticks > 20) {
            Network.TOOL.accept("use");
            cancelInput();
            stage = 32;
            ticks = 0;
          }
        }
        case 32 -> {
          if (Client.token == null && ticks > 10) {
            REPORT.addProperty("shift_right_click_validation_cancel_verified", true);
            Client.choose(gallery, false);
            stage = 40;
            ticks = 0;
          }
        }
        case 40 -> {
          if (Client.token != null && ticks > 20) {
            Client.fixed = true;
            Client.anchor = new BlockPos(1000, -58, 1000);
            var previous = MC.hitResult;
            MC.hitResult = outsidePhase == 0
                ? net.minecraft.world.phys.BlockHitResult.miss(MC.player.getEyePosition(), net.minecraft.core.Direction.UP, MC.player.blockPosition())
                : outsidePhase == 1
                    ? new net.minecraft.world.phys.BlockHitResult(MC.player.getEyePosition(), net.minecraft.core.Direction.UP, MC.player.blockPosition(), false)
                    : new net.minecraft.world.phys.EntityHitResult(MC.player);
            useInput();
            MC.hitResult = previous;
            stage = 41; ticks = 0;
          }
        }
        case 41 -> {
          if (Client.token == null && ticks > 10) {
            if (++outsidePhase < 3) { Client.choose(gallery, false); stage = 40; }
            else { REPORT.addProperty("outside_right_click_air_block_entity_verified", true); Client.choose(gallery, false); stage = 4; }
            ticks = 0;
          }
        }
        case 4 -> {
          if (Client.token != null && !REPORT.has("loading_preview_blocks_confirmation_verified")) {
            var blueprint = Client.mesh(gallery.getString("id")).blueprint();
            Client.installAsset(gallery.getString("id"), blueprint);
            if (Client.mesh(gallery.getString("id")).ready()) throw new IllegalStateException("Loading fixture is already ready");
            useInput();
            if (Client.fixed || Client.checking) throw new IllegalStateException("Loading preview accepted confirmation");
            REPORT.addProperty("loading_preview_blocks_confirmation_verified", true);
          }
          if (Client.token != null && ticks > 30) {
            Client.anchor = new BlockPos(4, -58, 4);
            Client.turns = 0;
            aimAt(new net.minecraft.world.phys.Vec3(7.5, -55.5, 6.5));
            useInput();
            stage = 5;
            ticks = 0;
          }
        }
        case 5 -> {
          if (Client.valid && ticks > 60) {
            screenshot("gallery-world-preview.png");
            aimAt(new net.minecraft.world.phys.Vec3(7.5, -55.5, 6.5));
            useInput();
            stage = 6;
            ticks = 0;
          } else if (ticks > 200 && !Client.checking)
            throw new IllegalStateException("Placement validation failed: " + Client.text);
        }
        case 6 -> {
          if (Client.token == null && ticks > 60) {
            screenshot("gallery-deployed.png");
            REPORT.addProperty("normal_right_click_confirm_deploy_verified", true);
            stage = 12;
          }
        }
        case 12 -> {
          Client.choose(gallery, true);
          stage = 13;
          ticks = 0;
        }
        case 13 -> {
          if (Client.token != null && Client.beacons && ticks > 20) {
            serverExecute(
                () -> {
                  var p = MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID());
                  p.getInventory().setItem(2, new ItemStack(PrefabDeploy.BEACON_ITEM.get(), 3));
                  p.setGameMode(GameType.SURVIVAL);
                  placeBeacon(p, new BlockPos(4, -58, -10));
                });
            stage = 14;
            ticks = 0;
          }
        }
        case 14 -> {
          if (Client.markers.size() == 1 && ticks > 40) {
            screenshot("beacon-a-candidates.png");
            serverExecute(
                () ->
                    placeBeacon(
                        MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID()),
                        new BlockPos(11, -58, -10)));
            stage = 15;
            ticks = 0;
          }
        }
        case 15 -> {
          if (Client.markers.size() == 2 && ticks > 40) {
            screenshot("beacon-c-candidate.png");
            serverExecute(
                () -> {
                  var p = MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID());
                  placeBeacon(p, new BlockPos(4, -58, -5));
                  p.getInventory().selected = 0;
                });
            stage = 16;
            ticks = 0;
          }
        }
        case 16 -> {
          if (Client.markers.size() == 3 && ticks > 30) {
            Client.fixed = true;
            Client.checking = true;
            var n = Network.message("fix");
            n.putUUID("token", Client.token);
            Network.request(n);
            stage = 17;
            ticks = 0;
          }
        }
        case 17 -> {
          if (Client.valid && ticks > 40) {
            screenshot("beacon-complete-preview.png");
            var n = Network.message("deploy");
            n.putUUID("token", Client.token);
            Network.request(n);
            stage = 18;
            ticks = 0;
          } else if (ticks > 200 && !Client.checking)
            throw new IllegalStateException("Beacon validation failed: " + Client.text);
        }
        case 18 -> {
          if (Client.token == null && ticks > 40) {
            serverExecute(
                () -> {
                  var p = MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID());
                  int count =
                      p.getInventory().items.stream()
                          .filter(s -> s.is(PrefabDeploy.BEACON_ITEM.get()))
                          .mapToInt(ItemStack::getCount)
                          .sum();
                  if (count != 3) throw new IllegalStateException("Beacon refund count: " + count);
                  if (p.getInventory().getItem(1).getCount() != 63)
                    throw new IllegalStateException(
                        "Survival deployment did not charge one emerald");
                  p.setGameMode(GameType.CREATIVE);
                  p.getAbilities().flying = true;
                  p.onUpdateAbilities();
                  REPORT.addProperty("three_beacon_floating_flow_verified", true);
                  REPORT.addProperty("survival_material_charge_verified", true);
                });
            stage = 43;
            ticks = 0;
          }
        }
        case 43 -> {
          MC.player.setMainArm(net.minecraft.world.entity.HumanoidArm.RIGHT);
          MC.player.setXRot(70); MC.player.xRotO = 70;
          if (ticks > 40) {
            screenshot("tool-two-hands-right.png");
            MC.player.setMainArm(net.minecraft.world.entity.HumanoidArm.LEFT);
            stage = 44; ticks = 0;
          }
        }
        case 44 -> {
          MC.player.setXRot(70); MC.player.xRotO = 70;
          if (ticks > 40) {
            screenshot("tool-two-hands-left.png");
            serverExecute(() -> MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID())
                .getInventory().offhand.set(0, new ItemStack(Items.SHIELD)));
            stage = 45; ticks = 0;
          }
        }
        case 45 -> {
          MC.player.setXRot(55); MC.player.xRotO = 55;
          if (ticks > 40) {
            screenshot("tool-single-hand-shield.png");
            MC.player.setMainArm(net.minecraft.world.entity.HumanoidArm.RIGHT);
            MC.player.getInventory().selected = 2;
            MC.player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(2));
            serverExecute(() -> {
              var p = MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID());
              p.getInventory().offhand.set(0, ItemStack.EMPTY);
              p.getInventory().selected = 2;
              p.serverLevel().setBlock(new BlockPos(7,-53,2), PrefabDeploy.BEACON.get().defaultBlockState(), 3);
            });
            stage = 46; ticks = 0;
          }
        }
        case 46 -> {
          aimAt(new net.minecraft.world.phys.Vec3(7.5,-52.5,2.5));
          if (ticks > 40) {
            if (!MC.player.getMainHandItem().is(PrefabDeploy.BEACON.get().asItem()))
              throw new IllegalStateException("Beacon hand model was not selected");
            var entity = MC.level.getBlockEntity(new BlockPos(7,-53,2));
            if (entity == null || entity.getType() != PrefabDeploy.BEACON_VISUAL.get()
                || MC.getBlockEntityRenderDispatcher().getRenderer(entity) == null)
              throw new IllegalStateException("Visual beacon renderer missing");
            var field = io.github.beaconvisual.client.BeaconVisualClient.class.getDeclaredField("spawnedParticles");
            field.setAccessible(true);
            if (field.getLong(null) <= 0) throw new IllegalStateException("Beacon particles not emitted");
            beaconTime = MC.level.getGameTime();
            screenshot("beacon-model-motion-a.png");
            stage = 47; ticks = 0;
          }
        }
        case 47 -> {
          aimAt(new net.minecraft.world.phys.Vec3(7.5,-52.5,2.5));
          if (ticks > 20) {
            if (MC.level.getGameTime() <= beaconTime) throw new IllegalStateException("Beacon animation clock stopped");
            screenshot("beacon-model-motion-b.png");
            REPORT.addProperty("beacon_renderer_particles_verified", true);
            MC.player.getInventory().selected = 0;
            MC.player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(0));
            serverExecute(() -> MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID()).getInventory().selected = 0);
            MC.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
            MC.player.setXRot(0); MC.player.xRotO = 0;
            stage = 48; ticks = 0;
          }
        }
        case 48 -> {
          if (ticks > 40) {
            screenshot("tool-third-person.png");
            MC.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            MC.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(MC.player));
            stage = 49; ticks = 0;
          }
        }
        case 49 -> {
          if (ticks > 40) {
            screenshot("models-inventory.png");
            MC.setScreen(null);
            REPORT.addProperty("integrated_model_scenarios_captured", true);
            if (System.getProperty("prefabdeploy.shaderSmoke") != null) {
              Files.writeString(directory().resolve("shader-validation.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
              PrefabDeploy.LOGGER.info("SHADER SMOKE COMPLETE {}", REPORT);
              MC.stop(); stage = 11; return;
            }
            stage = 7; ticks = 0;
          }
        }
        case 7 -> {
          if (benchmark >= COUNTS.length) {
            Files.writeString(
                directory().resolve("client-benchmark.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            PrefabDeploy.LOGGER.info("CLIENT SMOKE COMPLETE {}", REPORT);
            MC.stop();
            stage = 11;
            return;
          }
          int count = COUNTS[benchmark];
          started = System.nanoTime();
          pending =
              CompletableFuture.supplyAsync(
                  () -> {
                    int width = 50,
                        depth = count == 10000 ? 25 : 50,
                        height = count / (width * depth);
                    var voxels = new ArrayList<Blueprint.Voxel>(count);
                    for (int y = 0; y < height; y++)
                      for (int z = 0; z < depth; z++)
                        for (int x = 0; x < width; x++) {
                          int i = (y * depth + z) * width + x;
                          voxels.add(
                              new Blueprint.Voxel(
                                  new BlockPos(x, y, z),
                                  i % 17 == 0
                                      ? Blocks.AIR.defaultBlockState()
                                      : i % 2 == 0
                                          ? Blocks.GLASS.defaultBlockState()
                                          : Blocks.STONE.defaultBlockState(),
                                  null));
                        }
                    return new Blueprint(width, height, depth, voxels, List.of(), List.of());
                  });
          stage = 8;
        }
        case 8 -> {
          if (pending.isDone()) {
            var bp = pending.join();
            String id = "prefabdeploy:benchmark_" + COUNTS[benchmark];
            Client.installAsset(id, bp);
            var n = new CompoundTag();
            n.putString("id", id);
            n.putString("name", "Benchmark " + COUNTS[benchmark]);
            n.putString("category", "performance");
            n.putInt("x", bp.width());
            n.putInt("y", bp.height());
            n.putInt("z", bp.depth());
            n.putString("cost", "Free; renderer and native 20 TPS deployment benchmark");
            n.putBoolean("unlocked", true);
            Client.catalog = List.of(n);
            MC.setScreen(new LibraryScreen());
            nativeResult = null;
            var owner = MC.player.getUUID();
            serverExecute(
                () -> {
                  var p = MC.getSingleplayerServer().getPlayerList().getPlayer(owner);
                  var f =
                      new io.github.prefabdeploy.library.Prefab(
                          net.minecraft.resources.ResourceLocation.parse(id),
                          id,
                          "performance",
                          0,
                          bp,
                          JsonParser.parseString("{\"cost\":{\"mode\":\"free\"}}")
                              .getAsJsonObject(),
                          id,
                          "");
                  SERVER_TICKS.clear();
                  io.github.prefabdeploy.server.DeploymentManager.resetMetrics();
                  nativeJob =
                      io.github.prefabdeploy.server.DeploymentManager.submit(
                          p,
                          f,
                          new io.github.prefabdeploy.core.GridTransform(20, 70, 20, 0, 0),
                          List.of(),
                          false);
                });
            stage = 9;
          }
        }
        case 9 -> {
          var mesh = Client.mesh("prefabdeploy:benchmark_" + COUNTS[benchmark]);
          if (mesh != null && mesh.ready()) {
            var result = new JsonObject();
            result.addProperty("positions", COUNTS[benchmark]);
            result.addProperty("cold_build_elapsed_ms", (System.nanoTime() - started) / 1e6);
            RUNS.add(result);
            Client.resetMetrics();
            PreviewProfile.reset();
            stage = 10;
            ticks = 0;
          }
        }
        case 10 -> {
          if (MC.screen instanceof LibraryScreen screen)
            screen.mouseDragged(400, 140, 0, 1.25, .15);
          if (Client.metricData().get("samples").getAsInt() >= 400 && nativeResult != null) {
            if (!nativeResult.get("deployment_success").getAsBoolean())
              throw new IllegalStateException("Native deployment benchmark failed");
            var result = RUNS.get(benchmark).getAsJsonObject();
            for (var entry : Client.metricData().entrySet())
              result.add(entry.getKey(), entry.getValue());
            result.addProperty("gpu_p95_ms", PreviewProfile.p95());
            result.addProperty(
                "heap_used_bytes",
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            result.addProperty("last_frame_ms", MC.getFrameTimeNs() / 1e6);
            result.add("native_server", nativeResult);
            PrefabDeploy.LOGGER.info("CLIENT BENCHMARK {}", result);
            if (benchmark == 2) screenshot("100000-preview.png");
            Client.token = UUID.randomUUID();
            Client.selected = "prefabdeploy:benchmark_" + COUNTS[benchmark];
            Client.beacons = false;
            Client.fixed = true;
            Client.valid = true;
            Client.ground = 0;
            MC.setScreen(null);
            var owner = MC.player.getUUID();
            serverExecute(
                () ->
                    MC.getSingleplayerServer()
                        .getPlayerList()
                        .getPlayer(owner)
                        .teleportTo(45, 100, 110));
            stage = 19;
            ticks = 0;
          }
        }
        case 19 -> {
          if (ticks > 20) {
            Client.resetMetrics();
            PreviewProfile.reset();
            stage = 20;
            ticks = 0;
          }
        }
        case 20 -> {
          MC.player.setYRot(180);
          MC.player.setXRot(24);
          MC.player.yRotO = 180;
          MC.player.xRotO = 24;
          Client.turns = (ticks / 20) % 4;
          Client.anchor = new BlockPos(20 + ticks % 5, 70, 20);
          if (ticks > 80 && Client.metricData().get("samples").getAsInt() >= 400) {
            var world = Client.metricData();
            world.addProperty("gpu_p95_ms", PreviewProfile.p95());
            world.addProperty(
                "movement", "anchor shifted every tick; four rotations; frustum culling active");
            RUNS.get(benchmark).getAsJsonObject().add("world_preview", world);
            if (benchmark == 2) screenshot("100000-world-preview.png");
            Client.token = null;
            Client.selected = "";
            Client.fixed = false;
            Client.valid = false;
            benchmark++;
            stage = 7;
          }
        }
        default -> {}
      }
    } catch (Exception ex) {
      PrefabDeploy.LOGGER.error("CLIENT SMOKE FAILED", ex);
      REPORT.addProperty("failure", ex.toString());
      try {
        Files.writeString(
            directory().resolve("client-benchmark.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
      } catch (Exception ignored) {
      }
      MC.stop();
      stage = 11;
    }
  }

  private static void placeBeacon(net.minecraft.server.level.ServerPlayer p, BlockPos pos) {
    p.getInventory().selected = 2;
    p.setPos(pos.getX() + .5 + 8, pos.getY() + .5 - p.getEyeHeight(), pos.getZ() + .5);
    p.setYRot(90);
    p.setXRot(0);
    p.setShiftKeyDown(false);
    var result =
        PrefabDeploy.BEACON_ITEM
            .get()
            .use(p.level(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
    if (!result.getResult().consumesAction())
      throw new IllegalStateException("Floating beacon failed at " + pos);
    p.teleportTo(7, -53, 5);
  }

  private static void cancelInput() {
    // LocalPlayer reads crouching from Input, rather than Entity's shared flag.
    boolean previousShift = MC.player.input.shiftKeyDown;
    MC.player.input.shiftKeyDown = true;
    MC.player.setShiftKeyDown(true);
    var event =
        new net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered(
            1, MC.options.keyUse, net.minecraft.world.InteractionHand.MAIN_HAND);
    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
    MC.player.setShiftKeyDown(false);
    MC.player.input.shiftKeyDown = previousShift;
    if (!event.isCanceled() || event.shouldSwingHand())
      throw new IllegalStateException(
          "Shift-right-click was not intercepted before vanilla interaction");
  }

  private static void useInput() {
    MC.player.input.shiftKeyDown = false;
    MC.player.setShiftKeyDown(false);
    var event = new net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered(
        1, MC.options.keyUse, net.minecraft.world.InteractionHand.MAIN_HAND);
    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
    if (!event.isCanceled() || event.shouldSwingHand()) throw new IllegalStateException("Tool input was not intercepted");
  }

  private static void reloadModels() throws Exception {
    if (stage == 0) {
      Files.createDirectories(directory());
      serverExecute(() -> {
        var p = MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID());
        p.teleportTo(7,-53,5);
        p.getInventory().selected = 2;
        var e = p.serverLevel().getBlockEntity(new BlockPos(7,-53,2));
        if (e == null || e.getType() != PrefabDeploy.BEACON_VISUAL.get())
          throw new IllegalStateException("Saved beacon visual entity did not reload");
      });
      stage = 1; ticks = 0;
    } else if (stage == 1 && ++ticks > 100) {
      var e = MC.level.getBlockEntity(new BlockPos(7,-53,2));
      if (e == null || MC.getBlockEntityRenderDispatcher().getRenderer(e) == null)
        throw new IllegalStateException("Reloaded client beacon has no renderer");
      aimAt(new net.minecraft.world.phys.Vec3(7.5,-52.5,2.5));
      screenshot("beacon-reloaded.png");
      Files.writeString(directory().resolve("model-reload.json"), "{\"mod_version\":\"0.1.2\",\"saved_beacon_reloaded\":true,\"client_renderer_available\":true}");
      PrefabDeploy.LOGGER.info("MODEL RELOAD SMOKE COMPLETE");
      MC.stop(); stage = 11;
    }
  }

  private static void aimAt(net.minecraft.world.phys.Vec3 target) {
    var delta = target.subtract(MC.player.getEyePosition());
    MC.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
    MC.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x*delta.x+delta.z*delta.z))));
  }

  private static void serverExecute(Runnable action) {
    MC.getSingleplayerServer()
        .execute(
            () -> {
              try {
                action.run();
              } catch (RuntimeException ex) {
                serverFailure = ex;
              }
            });
  }
}
