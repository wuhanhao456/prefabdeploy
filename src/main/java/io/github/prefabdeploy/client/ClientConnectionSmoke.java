package io.github.prefabdeploy.client;

import com.google.gson.*;
import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.network.Network;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

/** Development-only, one real client reconnecting to multiple dedicated servers. */
@EventBusSubscriber(modid = PrefabDeploy.ID, value = Dist.CLIENT)
public final class ClientConnectionSmoke {
  private static final Minecraft MC = Minecraft.getInstance();
  private static final JsonArray RESULTS = new JsonArray();
  private static String[] servers;
  private static int index, stage, ticks, totalTicks;
  private static JsonObject result;
  private static boolean expected, savedHotbar;
  private static Vec3 beforeMove;
  private static BlockPos base;
  private static io.github.prefabdeploy.blueprint.Blueprint blueprint;
  private static final BlockPos ANCHOR = new BlockPos(0, -59, 0);

  @SubscribeEvent
  public static void tick(ClientTickEvent.Post event) {
    if (!Boolean.getBoolean("prefabdeploy.connectionSmoke") || stage == 100) return;
    try {
      if (++totalTicks > 18000 || ++ticks > 2400)
        throw new IllegalStateException("Connection smoke timed out: " + index + "/" + stage);
      if (servers == null) servers = System.getProperty("prefabdeploy.connectionServers").split(",");
      if (ticks % 200 == 0) PrefabDeploy.LOGGER.info("CONNECTION SMOKE server={} stage={} screen={}", index, stage, MC.screen);
      if (stage == 0) {
        if (!(MC.screen instanceof TitleScreen) || ticks < 20) return;
        if (index == servers.length) {
          writeReport(true, "");
          PrefabDeploy.LOGGER.info("CLIENT CONNECTION SMOKE COMPLETE");
          stage = 100;
          MC.stop();
          return;
        }
        var target = servers[index].split("@", 2);
        expected = target[0].equals("supported");
        result = new JsonObject();
        result.addProperty("server", target[0]);
        result.addProperty("address", target[1]);
        ConnectScreen.startConnecting(new TitleScreen(), MC, ServerAddress.parseString(target[1]),
            new ServerData(target[0], target[1], ServerData.Type.OTHER), false, null);
        next(1);
        return;
      }
      if (MC.player == null || MC.level == null) return;
      switch (stage) {
        case 1 -> {
          if (ticks < 80) return;
          check(Client.supported() == expected, "Wrong server capability");
          check(Client.token == null && Client.catalog.isEmpty() && !Client.localImportAllowed,
              "Connection retained previous prefab state");
          result.addProperty("joined", true);
          result.addProperty("capability_correct", true);
          result.addProperty("connection_state_cleared", true);
          command("gamemode creative");
          command("tp @s 4 -59 12");
          next(2);
        }
        case 2 -> {
          if (ticks < 40 || !MC.gameMode.hasInfiniteItems()) return;
          CreativeModeTabs.tryRebuildTabContents(MC.level.enabledFeatures(), true, MC.level.registryAccess());
          check(PrefabDeploy.TAB.get().shouldDisplay() == expected, "Creative tab visibility is wrong");
          boolean found = CreativeModeTabs.searchTab().getDisplayItems().stream()
              .anyMatch(stack -> stack.is(PrefabDeploy.TOOL.get()));
          check(found == expected, "Creative search contains wrong prefab items");
          result.addProperty("creative_tab_and_search_correct", true);
          MC.setScreen(new CreativeModeInventoryScreen(MC.player, MC.level.enabledFeatures(), true));
          if (expected) {
            var selectTab = CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", CreativeModeTab.class);
            selectTab.setAccessible(true);
            selectTab.invoke(MC.screen, PrefabDeploy.TAB.get());
          }
          next(20);
        }
        case 20 -> {
          if (ticks < 10) return;
          check(MC.screen instanceof CreativeModeInventoryScreen, "Creative inventory did not open");
          screenshot("creative");
          MC.setScreen(null);
          result.addProperty("creative_inventory_opened", true);
          if (expected) {
            if (!savedHotbar) {
              var inventory = MC.player.getInventory();
              var old = new ArrayList<ItemStack>();
              for (int i = 0; i < 9; i++) old.add(inventory.getItem(i).copy());
              inventory.setItem(0, new ItemStack(PrefabDeploy.TOOL.get()));
              inventory.setItem(1, new ItemStack(PrefabDeploy.BEACON_ITEM.get(), 3));
              inventory.setItem(2, new ItemStack(Items.STONE, 16));
              var box = new ItemStack(Items.SHULKER_BOX);
              box.set(net.minecraft.core.component.DataComponents.CONTAINER,
                  net.minecraft.world.item.component.ItemContainerContents.fromItems(
                      List.of(new ItemStack(PrefabDeploy.TOOL.get()))));
              inventory.setItem(3, box);
              MC.getHotbarManager().get(8).storeFrom(inventory, MC.level.registryAccess());
              for (int i = 0; i < 9; i++) inventory.setItem(i, old.get(i));
              savedHotbar = true;
            }
            CreativeModeInventoryScreen.handleHotbarLoadOrSave(MC, 8, true, false);
            check(MC.player.getMainHandItem().is(PrefabDeploy.TOOL.get()), "Supported hotbar lost tool");
            check(MC.player.getInventory().getItem(1).is(PrefabDeploy.BEACON_ITEM.get()), "Supported hotbar lost beacon");
            check(MC.player.getInventory().getItem(3).is(Items.SHULKER_BOX), "Supported hotbar lost nested prefab item");
            result.addProperty("saved_hotbar_restored", true);
            Network.TOOL.accept("use");
            next(10);
          } else {
            CreativeModeInventoryScreen.handleHotbarLoadOrSave(MC, 8, true, false);
            check(MC.player.getInventory().getItem(0).isEmpty()
                && MC.player.getInventory().getItem(1).isEmpty()
                && MC.player.getInventory().getItem(3).isEmpty(), "Unsupported hotbar restored prefab items");
            check(MC.player.getInventory().getItem(2).is(Items.STONE), "Hotbar removed vanilla item");
            MC.gameMode.handleCreativeModeItemAdd(new ItemStack(PrefabDeploy.TOOL.get()), 36);
            MC.gameMode.handleCreativeModeItemDrop(new ItemStack(PrefabDeploy.BEACON_ITEM.get()));
            Network.request(Network.message("catalog"));
            Client.requestPreview("prefabdeploy:cottage");
            Client.choose(new CompoundTag(), false);
            Network.TOOL.accept("use");
            var input = new InputEvent.InteractionKeyMappingTriggered(1, MC.options.keyUse, InteractionHand.MAIN_HAND);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(input);
            check(!input.isCanceled(), "Unsupported connection intercepted right-click");
            result.addProperty("unsupported_requests_and_creative_submissions_blocked", true);
            result.addProperty("saved_hotbar_filtered_with_vanilla_items_preserved", true);
            result.addProperty("nested_prefab_container_filtered", true);
            result.addProperty("vanilla_input_preserved", true);
            beforeMove = MC.player.position();
            MC.options.keyUp.setDown(true);
            next(3);
          }
        }
        case 3 -> {
          if (ticks < 30) return;
          MC.options.keyUp.setDown(false);
          check(MC.player.position().distanceToSqr(beforeMove) > 1, "Player did not move");
          check(Client.token == null && Client.catalog.isEmpty() && !(MC.screen instanceof LibraryScreen), "Unsupported functionality activated");
          result.addProperty("movement_and_disabled_features", true);
          base = MC.player.blockPosition().offset(0, -1, -2);
          command("setblock " + base.getX() + " " + base.getY() + " " + base.getZ() + " minecraft:stone");
          command("setblock " + base.getX() + " " + (base.getY() + 1) + " " + base.getZ() + " minecraft:air");
          MC.player.getInventory().selected = 2;
          next(4);
        }
        case 4 -> {
          if (ticks < 30) return;
          check(MC.level.getBlockState(base.above()).isAir(), "Placement target was not empty");
          var hit = new BlockHitResult(Vec3.atBottomCenterOf(base.above()), Direction.UP, base, false);
          MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hit);
          next(5);
        }
        case 5 -> {
          if (ticks < 30) return;
          check(MC.level.getBlockState(base.above()).is(Blocks.STONE), "Vanilla placement failed");
          MC.gameMode.startDestroyBlock(base.above(), Direction.UP);
          next(6);
        }
        case 6 -> {
          if (ticks < 30) return;
          check(MC.level.getBlockState(base.above()).isAir(), "Vanilla breaking failed");
          result.addProperty("vanilla_block_placement_and_breaking", true);
          command("execute in minecraft:the_nether run tp @s 0 100 0");
          next(7);
        }
        case 7 -> {
          if (!MC.level.dimension().equals(Level.NETHER)) return;
          check(!Client.supported(), "Capability changed in Nether");
          command("execute in minecraft:overworld run tp @s 4 -59 12");
          next(8);
        }
        case 8 -> {
          if (!MC.level.dimension().equals(Level.OVERWORLD) || ticks < 30) return;
          result.addProperty("dimension_round_trip", true);
          finishServer();
        }
        case 10 -> {
          if (!(MC.screen instanceof LibraryScreen) || ticks < 10) return;
          screenshot("library");
          var entry = Client.catalog.stream().filter(n -> n.getString("id").equals("prefabdeploy:cottage")).findFirst().orElseThrow();
          result.addProperty("remote_library_loaded", true);
          Client.choose(entry, false);
          next(11);
        }
        case 11 -> {
          var mesh = Client.mesh("prefabdeploy:cottage");
          if (Client.token == null || mesh == null || !mesh.ready()) return;
          blueprint = mesh.blueprint();
          result.addProperty("remote_preview_loaded", true);
          Client.anchor = ANCHOR;
          Client.fixed = true;
          Client.checking = true;
          var request = Network.message("fix");
          request.putUUID("token", Client.token);
          request.putLong("anchor", ANCHOR.asLong());
          request.putInt("turns", 0);
          Network.request(request);
          next(12);
        }
        case 12 -> {
          if (!Client.valid) return;
          var request = Network.message("deploy");
          request.putUUID("token", Client.token);
          Network.request(request);
          next(13);
        }
        case 13 -> {
          if (Client.token != null || ticks < 40) return;
          var transform = new GridTransform(ANCHOR.getX(), ANCHOR.getY(), ANCHOR.getZ(), 0, 1);
          check(blueprint.voxels().stream().filter(v -> !v.state().isAir()).allMatch(v -> {
            var c = transform.cell(v.pos().getX(), v.pos().getY(), v.pos().getZ());
            return MC.level.getBlockState(new BlockPos(c.x(), c.y(), c.z())).equals(v.state());
          }), "Remote deployed blocks do not match blueprint");
          result.addProperty("remote_validation_and_deployment", true);
          finishServer();
        }
        default -> throw new IllegalStateException("Unknown smoke stage " + stage);
      }
    } catch (Throwable failure) {
      PrefabDeploy.LOGGER.error("CLIENT CONNECTION SMOKE FAILED", failure);
      try { writeReport(false, failure.toString()); } catch (Exception reportFailure) { failure.addSuppressed(reportFailure); }
      stage = 100;
      MC.stop();
    }
  }

  private static void finishServer() {
    result.addProperty("passed", true);
    RESULTS.add(result);
    PrefabDeploy.LOGGER.info("CONNECTION SMOKE passed: {}", result);
    // Seed state that must be discarded by the actual disconnect/login events.
    Client.token = UUID.randomUUID();
    Client.catalog = List.of(new CompoundTag());
    Client.localImportAllowed = true;
    MC.disconnect(new TitleScreen());
    index++;
    next(0);
  }

  private static void command(String command) {
    MC.player.connection.sendCommand(command);
  }

  private static void screenshot(String name) {
    Screenshot.grab(Path.of(System.getProperty("prefabdeploy.reportDir")).toFile(),
        "connection-" + index + "-" + name + ".png", MC.getMainRenderTarget(),
        text -> PrefabDeploy.LOGGER.info("Connection screenshot: {}", text.getString()));
  }

  private static void next(int value) { stage = value; ticks = 0; }
  private static void check(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private static void writeReport(boolean passed, String error) throws Exception {
    var report = new JsonObject();
    report.addProperty("passed", passed);
    report.addProperty("error", error);
    report.addProperty("minecraft", "1.21.1");
    report.addProperty("neoforge", "21.1.248");
    report.addProperty("single_client_process", true);
    report.add("connections", RESULTS);
    if (!passed && result != null) report.add("failed_connection", result);
    var directory = Path.of(System.getProperty("prefabdeploy.reportDir"));
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("client-connections.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
  }

  private ClientConnectionSmoke() {}
}
