package io.github.prefabdeploy;

import static net.minecraft.commands.Commands.*;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import io.github.prefabdeploy.api.PrefabApi;
import io.github.prefabdeploy.blueprint.*;
import io.github.prefabdeploy.item.*;
import io.github.prefabdeploy.library.PrefabLibrary;
import io.github.prefabdeploy.network.Network;
import io.github.prefabdeploy.server.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.*;
import net.neoforged.neoforge.event.entity.player.*;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.*;
import org.slf4j.Logger;

@Mod(PrefabDeploy.ID)
public final class PrefabDeploy {
  public static final String ID = "prefabdeploy";
  public static final Logger LOGGER = LogUtils.getLogger();
  private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
  private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
  private static final DeferredRegister<CreativeModeTab> TABS =
      DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, ID);
  private static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BE_TYPES =
      DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, ID);
  public static final DeferredBlock<io.github.beaconvisual.BeaconVisualBlock> BEACON =
      BLOCKS.register(
          "positioning_beacon",
          () -> new io.github.beaconvisual.BeaconVisualBlock(
              BlockBehaviour.Properties.of().strength(0.5f).noOcclusion().lightLevel(s -> 12),
              () -> PrefabDeploy.BEACON_VISUAL.get()));
  public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
      net.minecraft.world.level.block.entity.BlockEntityType<io.github.beaconvisual.BeaconVisualBlockEntity>> BEACON_VISUAL =
      BE_TYPES.register("positioning_beacon_visual", () ->
          net.minecraft.world.level.block.entity.BlockEntityType.Builder.of(
              (pos, state) -> new io.github.beaconvisual.BeaconVisualBlockEntity(PrefabDeploy.BEACON_VISUAL.get(), pos, state),
              BEACON.get()).build(null));
  public static final DeferredItem<DeploymentTool> TOOL =
      ITEMS.register(
          "deployment_tool", () -> new DeploymentTool(new Item.Properties().stacksTo(1)));
  public static final DeferredItem<PositioningBeaconItem> BEACON_ITEM =
      ITEMS.register(
          "positioning_beacon",
          () -> new PositioningBeaconItem(BEACON.get(), new Item.Properties()));
  public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
      TABS.register(
          "prefabs",
          () ->
              CreativeModeTab.builder()
                  .title(Component.translatable("creativetab.prefabdeploy"))
                  .icon(() -> new ItemStack(TOOL.get()))
                  .displayItems(
                      (parameters, output) -> {
                        output.accept(TOOL.get());
                        output.accept(BEACON_ITEM.get());
                      })
                  .build());

  public PrefabDeploy(IEventBus modBus, ModContainer container) {
    BLOCKS.register(modBus);
    ITEMS.register(modBus);
    TABS.register(modBus);
    BE_TYPES.register(modBus);
    container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
    modBus.addListener(Network::register);
    modBus.addListener(
        (BuildCreativeModeTabContentsEvent e) -> {
          if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            e.accept(TOOL);
            e.accept(BEACON_ITEM);
          }
        });
    PrefabApi.IMPORTERS.add(new StructureImporter());
    PrefabApi.IMPORTERS.add(new LitematicImporter());
    NeoForge.EVENT_BUS.addListener(
        (AddReloadListenerEvent e) -> e.addListener(PrefabLibrary.INSTANCE));
    NeoForge.EVENT_BUS.addListener(
        (ServerStartedEvent e) -> DeploymentManager.start(e.getServer()));
    NeoForge.EVENT_BUS.addListener(
        (ServerStoppingEvent e) -> {
          DeploymentManager.stop();
          Sessions.clear();
          io.github.prefabdeploy.library.LocalBlueprints.clear();
        });
    NeoForge.EVENT_BUS.addListener(
        (ServerTickEvent.Post e) -> {
          DeploymentManager.tick();
          Sessions.pollMarkers(e.getServer());
        });
    NeoForge.EVENT_BUS.addListener(
        (PlayerEvent.PlayerChangedDimensionEvent e) -> {
          if (e.getEntity() instanceof ServerPlayer p) Sessions.cancel(p);
        });
    NeoForge.EVENT_BUS.addListener(
        (PlayerEvent.PlayerLoggedOutEvent e) -> {
          if (e.getEntity() instanceof ServerPlayer p) Sessions.logout(p);
        });
    NeoForge.EVENT_BUS.addListener(
        (PlayerEvent.PlayerLoggedInEvent e) -> {
          if (e.getEntity() instanceof ServerPlayer p) Costs.claim(p);
        });
    NeoForge.EVENT_BUS.addListener(
        (PlayerInteractEvent.RightClickBlock e) -> {
          if (!e.getLevel().isClientSide() && RegionLocks.locked(e.getLevel(), e.getPos()))
            e.setCanceled(true);
        });
    NeoForge.EVENT_BUS.addListener(
        (PlayerInteractEvent.EntityInteract e) -> {
          if (!e.getLevel().isClientSide()
              && RegionLocks.locked(e.getLevel(), e.getTarget().blockPosition()))
            e.setCanceled(true);
        });
    NeoForge.EVENT_BUS.addListener(
        (BlockEvent.BreakEvent e) -> {
          if (e.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            if (RegionLocks.locked(level, e.getPos())) e.setCanceled(true);
            else if (e.getState().is(BEACON.get())) Sessions.markerBroken(e.getPos(), level);
          }
        });
    NeoForge.EVENT_BUS.addListener(this::commands);
    NeoForge.EVENT_BUS.addListener(io.github.prefabdeploy.server.BeaconMigration::onChunkLoad);
    if (FMLEnvironment.dist == Dist.CLIENT) {
      io.github.prefabdeploy.client.Client.init(modBus);
      io.github.beaconvisual.client.BeaconVisualClient.register(modBus, BEACON_VISUAL);
      modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) ->
          e.enqueueWork(() -> io.github.blueprintitem.client.BlueprintHandRenderer.register(
              stack -> stack.is(TOOL.get()))));
    }
  }

  private void commands(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            literal("prefab")
                .then(
                    literal("reload")
                        .requires(s -> s.hasPermission(2))
                        .executes(
                            c -> PrefabReload.run(c.getSource())))
                .then(
                    literal("tasks")
                        .requires(s -> s.hasPermission(2))
                        .executes(
                            c -> {
                              DeploymentManager.status()
                                  .forEach(
                                      line ->
                                          c.getSource()
                                              .sendSuccess(() -> Component.literal(line), false));
                              return 1;
                            }))
                .then(
                    literal("metrics")
                        .requires(s -> s.hasPermission(2))
                        .executes(
                            c -> {
                              c.getSource()
                                  .sendSuccess(
                                      () -> Component.literal(DeploymentManager.metrics()), false);
                              return 1;
                            }))
                .then(
                    literal("audit")
                        .requires(s -> s.hasPermission(2))
                        .then(
                            argument("transaction", StringArgumentType.word())
                                .executes(
                                    c -> {
                                      var id =
                                          java.util.UUID.fromString(
                                              StringArgumentType.getString(c, "transaction"));
                                      DeploymentManager.audit(id)
                                          .whenComplete(
                                              (n, error) ->
                                                  c.getSource()
                                                      .getServer()
                                                      .execute(
                                                          () -> {
                                                            if (error != null)
                                                              c.getSource()
                                                                  .sendFailure(
                                                                      Component.literal(
                                                                          "Audit failed: "
                                                                              + error
                                                                                  .getMessage()));
                                                            else
                                                              c.getSource()
                                                                  .sendSuccess(
                                                                      () ->
                                                                          Component.literal(
                                                                              id
                                                                                  + " durable="
                                                                                  + n.getString(
                                                                                      "durable")
                                                                                  + " success="
                                                                                  + n.getBoolean(
                                                                                      "success")
                                                                                  + " owner="
                                                                                  + n.getUUID(
                                                                                      "owner")
                                                                                  + " prefab="
                                                                                  + n.getString(
                                                                                      "prefab")
                                                                                  + " price="
                                                                                  + Costs.describe(
                                                                                      n.getCompound(
                                                                                          "price"))
                                                                                  + " error="
                                                                                  + n.getString(
                                                                                      "error")),
                                                                      false);
                                                          }));
                                      return 1;
                                    })))
                .then(
                    literal("recover")
                        .requires(s -> s.hasPermission(2))
                        .then(
                            argument("transaction", StringArgumentType.word())
                                .executes(
                                    c -> {
                                      DeploymentManager.retry(
                                          java.util.UUID.fromString(
                                              StringArgumentType.getString(c, "transaction")));
                                      return 1;
                                    })))
                .then(
                    literal("receipt")
                        .requires(s -> s.hasPermission(2))
                        .then(
                            argument(
                                    "player",
                                    net.minecraft.commands.arguments.EntityArgument.player())
                                .then(
                                    argument("transaction", StringArgumentType.word())
                                        .executes(
                                            c -> {
                                              var p =
                                                  net.minecraft.commands.arguments.EntityArgument
                                                      .getPlayer(c, "player");
                                              var id =
                                                  java.util.UUID.fromString(
                                                      StringArgumentType.getString(
                                                          c, "transaction"));
                                              c.getSource()
                                                  .sendSuccess(
                                                      () ->
                                                          Component.literal(
                                                              Costs.auditReceipt(p, id).toString()),
                                                      false);
                                              return 1;
                                            }))))
                .then(
                    literal("reconcile_currency")
                        .requires(s -> s.hasPermission(2))
                        .then(
                            argument(
                                    "player",
                                    net.minecraft.commands.arguments.EntityArgument.player())
                                .then(
                                    argument("transaction", StringArgumentType.word())
                                        .then(
                                            argument(
                                                    "still_owed",
                                                    com.mojang.brigadier.arguments
                                                        .DoubleArgumentType.doubleArg(0))
                                                .executes(
                                                    c -> {
                                                      var p =
                                                          net.minecraft.commands.arguments
                                                              .EntityArgument.getPlayer(
                                                              c, "player");
                                                      var id =
                                                          java.util.UUID.fromString(
                                                              StringArgumentType.getString(
                                                                  c, "transaction"));
                                                      Costs.reconcileCurrency(
                                                          p,
                                                          id,
                                                          com.mojang.brigadier.arguments
                                                              .DoubleArgumentType.getDouble(
                                                              c, "still_owed"));
                                                      c.getSource()
                                                          .sendSuccess(
                                                              () ->
                                                                  Component.literal(
                                                                      "Recorded remaining currency"
                                                                          + " refund; run /prefab"
                                                                          + " recover "
                                                                          + id),
                                                              true);
                                                      return 1;
                                                    })))))
                .then(
                    literal("claim")
                        .executes(
                            c -> {
                              Costs.claim(c.getSource().getPlayerOrException());
                              return 1;
                            }))
                .then(
                    literal("grant")
                        .requires(s -> s.hasPermission(2))
                        .then(
                            argument("owner", StringArgumentType.word())
                                .then(
                                    argument("flag", StringArgumentType.word())
                                        .executes(
                                            c -> {
                                              ServerState.flag(
                                                  c.getSource().getServer(),
                                                  StringArgumentType.getString(c, "owner")
                                                      + ":"
                                                      + StringArgumentType.getString(c, "flag"),
                                                  true);
                                              return 1;
                                            }))))
                .then(
                    literal("revoke")
                        .requires(s -> s.hasPermission(2))
                        .then(
                            argument("owner", StringArgumentType.word())
                                .then(
                                    argument("flag", StringArgumentType.word())
                                        .executes(
                                            c -> {
                                              ServerState.flag(
                                                  c.getSource().getServer(),
                                                  StringArgumentType.getString(c, "owner")
                                                      + ":"
                                                      + StringArgumentType.getString(c, "flag"),
                                                  false);
                                              return 1;
                                            })))));
  }
}
