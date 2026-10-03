package io.github.prefabdeploy.testing;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import io.github.prefabdeploy.*;
import io.github.prefabdeploy.api.*;
import io.github.prefabdeploy.blueprint.Blueprint;
import io.github.prefabdeploy.compat.OptionalMods;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.library.*;
import io.github.prefabdeploy.network.Network;
import io.github.prefabdeploy.server.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

/** Real server regressions for the 0.1.1 changes; excluded from the installation JAR. */
@GameTestHolder(PrefabDeploy.ID)
@PrefixGameTestTemplate(false)
public final class Release011GameTests {
  private static ServerPlayer player(GameTestHelper h, String name, GameType mode) {
    var p =
        FakePlayerFactory.get(
            h.getLevel(),
            new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
    p.getInventory().clearContent();
    p.getInventory().selected = 0;
    p.setGameMode(mode);
    p.setShiftKeyDown(false);
    p.giveExperiencePoints(100 - p.totalExperience);
    var pos = h.absolutePos(new BlockPos(1, 12, 1));
    p.setPos(pos.getX(), pos.getY(), pos.getZ());
    return p;
  }

  private static Prefab building(String name, String cost) {
    return new Prefab(
        ResourceLocation.parse("prefabdeploy:" + name),
        name,
        "test",
        0,
        new Blueprint(
            1,
            1,
            1,
            List.of(new Blueprint.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null)),
            List.of(),
            List.of()),
        JsonParser.parseString("{\"visible\":true,\"unlock\":true,\"cost\":" + cost + "}")
            .getAsJsonObject(),
        name,
        "");
  }

  private static final class SpyCost implements CostProvider {
    int calls;

    public boolean atomicWithPlayerSave() {
      calls++;
      return true;
    }

    public CompoundTag quote(ServerPlayer p, JsonObject spec) {
      calls++;
      return new CompoundTag();
    }

    public void reserve(ServerPlayer p, UUID id, CompoundTag q) {
      calls++;
    }

    public void commit(ServerPlayer p, UUID id, CompoundTag q) {
      calls++;
    }

    public void refund(ServerPlayer p, UUID id, CompoundTag q) {
      calls++;
    }
  }

  @GameTest(template = "empty", timeoutTicks = 300, batch = "011_costs")
  public static void creative_exempts_all_providers_and_survives_saved_mode_change(GameTestHelper h)
      throws Exception {
    var p = player(h, "011_creative_cost", GameType.CREATIVE);
    p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
    var spy = new SpyCost();
    PrefabApi.COSTS.put("prefabdeploy:011_spy", spy);
    var f =
        building(
            "011_creative",
            "{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":2}],\"xp\":30,\"viscriptshop\":12.34,\"custom\":[{\"provider\":\"prefabdeploy:011_spy\"}]}");
    double money = 0;
    if (OptionalMods.loaded("viscript_shop")) {
      OptionalMods.call(
          Class.forName("com.viscriptshop.util.ViScriptShopServerUtil"), "setMoney", p, 100.0);
      money = OptionalMods.money(p);
    }
    var q = Costs.quote(p, f);
    h.assertTrue(
        q.getBoolean("creativeExempt")
            && q.getList("custom", Tag.TAG_COMPOUND).isEmpty()
            && q.getList("items", Tag.TAG_COMPOUND).isEmpty()
            && q.getInt("xp") == 0
            && q.getDouble("money") == 0,
        "Creative quote contains a fee");
    h.assertTrue(
        Costs.describeText(q).key().equals("prefabdeploy.cost.creative"),
        "Creative GUI text missing");
    p.setGameMode(GameType.SURVIVAL);
    var committed = UUID.randomUUID();
    Costs.reserve(p, committed, q);
    p.load(
        NbtIo.readCompressed(
            p.server.getWorldPath(LevelResource.ROOT).resolve("playerdata/" + p.getUUID() + ".dat"),
            NbtAccounter.create(64L * 1024 * 1024)));
    h.assertTrue(
        Costs.auditReceipt(p, committed).getCompound("quote").getBoolean("creativeExempt"),
        "Creative exemption lost after player reload");
    Costs.commit(p, committed);
    Costs.commit(p, committed);
    var refunded = UUID.randomUUID();
    Costs.reserve(p, refunded, q);
    Costs.refund(p, refunded);
    Costs.refund(p, refunded);
    h.assertTrue(spy.calls == 0, "Creative transaction called a cost provider");
    h.assertTrue(
        p.totalExperience == 100 && p.getInventory().getItem(0).getCount() == 5,
        "Creative reservation/refund changed resources");
    if (OptionalMods.loaded("viscript_shop"))
      h.assertTrue(OptionalMods.money(p) == money, "Creative transaction changed VSS balance");
    PrefabApi.COSTS.remove("prefabdeploy:011_spy");
    h.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 300, batch = "011_survival")
  public static void survival_and_legacy_quotes_stay_charged_after_creative_switch(GameTestHelper h)
      throws Exception {
    var p = player(h, "011_survival_cost", GameType.SURVIVAL);
    p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
    var spy = new SpyCost();
    PrefabApi.COSTS.put("prefabdeploy:011_paid", spy);
    var f =
        building(
            "011_survival",
            "{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":2}],\"xp\":30,\"custom\":[{\"provider\":\"prefabdeploy:011_paid\"}]}");
    var q = Costs.quote(p, f);
    q.remove("creativeExempt"); // Legacy quotes must keep their original price.
    p.setGameMode(GameType.CREATIVE);
    var id = UUID.randomUUID();
    Costs.reserve(p, id, q);
    h.assertTrue(
        p.totalExperience == 70 && p.getInventory().getItem(0).getCount() == 3,
        "Mode change waived a fixed survival quote");
    p.load(
        NbtIo.readCompressed(
            p.server.getWorldPath(LevelResource.ROOT).resolve("playerdata/" + p.getUUID() + ".dat"),
            NbtAccounter.create(64L * 1024 * 1024)));
    Costs.refund(p, id);
    int calls = spy.calls;
    Costs.refund(p, id);
    h.assertTrue(
        spy.calls == calls
            && calls >= 4
            && p.totalExperience == 100
            && p.getInventory().getItem(0).getCount() == 5,
        "Refund lost resources or repeated custom callback");
    PrefabApi.COSTS.remove("prefabdeploy:011_paid");
    h.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 100, batch = "011_display")
  public static void localization_does_not_change_rule_semantics_or_author_text(GameTestHelper h) {
    var p = player(h, "011_text", GameType.CREATIVE);
    var f = building("011_display", "{\"mode\":\"free\"}");
    String authored = "Condition unavailable: author deliberately used this phrase";
    PrefabApi.RULES.put("prefabdeploy:011_literal", (player, prefab, anchor) -> authored);
    var script =
        JsonParser.parseString("{\"type\":\"script\",\"id\":\"prefabdeploy:011_literal\"}");
    var denied = Rules.evaluate(script, p, f, p.blockPosition());
    h.assertTrue(
        !denied.passed()
            && !denied.unavailable()
            && denied.text().key().isEmpty()
            && denied.text().plain().equals(authored),
        "Script text was translated or treated as a missing dependency");
    var not = new JsonObject();
    not.addProperty("type", "not");
    not.add("rule", script);
    h.assertTrue(
        Rules.evaluate(not, p, f, p.blockPosition()).passed(),
        "Literal display text affected NOT semantics");
    var nativeText = UiText.fromLegacy("Missing material: minecraft:diamond 1/2");
    var n = new CompoundTag();
    nativeText.message(n);
    h.assertTrue(
        !nativeText.key().isEmpty() && UiText.read(n, "text_display", "text").equals(nativeText),
        "Structured diagnostic failed NBT round trip");
    h.assertTrue(
        new UiText("anothermod:custom_list", "Value %s", List.of(UiText.literal("3")))
            .plain()
            .equals("Value 3"),
        "Foreign translation key interpreted as an internal list");
    h.assertTrue(
        PrefabDeploy.TAB.get().getIconItem().is(PrefabDeploy.TOOL.get()),
        "Creative tab icon incorrect");
    PrefabApi.RULES.remove("prefabdeploy:011_literal");
    h.succeed();
  }

  private static Sessions.Session session(ServerPlayer p) throws Exception {
    var field = Sessions.class.getDeclaredField("ACTIVE");
    field.setAccessible(true);
    return (Sessions.Session) ((Map<?, ?>) field.get(null)).get(p.getUUID());
  }

  private static Object field(Sessions.Session s, String name) throws Exception {
    var field = Sessions.Session.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(s);
  }

  private static void select(ServerPlayer p, boolean beacons) {
    p.getInventory().setItem(0, new ItemStack(PrefabDeploy.TOOL.get()));
    var n = Network.message("select");
    n.putString("id", "prefabdeploy:nbt_gallery");
    n.putBoolean("beacons", beacons);
    Sessions.receive(p, n);
  }

  private static void floatAt(ServerPlayer p, BlockPos pos) {
    p.setPos(pos.getX() + .5, pos.getY() + .5 - p.getEyeHeight(), pos.getZ() + .5 - 8);
    p.setYRot(0);
    p.setXRot(0);
    p.setShiftKeyDown(false);
    PrefabDeploy.BEACON_ITEM.get().use(p.level(), p, InteractionHand.MAIN_HAND);
  }

  @GameTest(template = "empty", timeoutTicks = 300, batch = "011_markers")
  public static void beacon_items_track_real_consumption_and_cancel_precedes_placement(
      GameTestHelper h) throws Exception {
    var p = player(h, "011_markers", GameType.SURVIVAL);
    select(p, true);
    h.assertTrue(Sessions.active(p), "Beacon session not selected");
    var a = h.absolutePos(new BlockPos(20, 9, 20));
    var f = PrefabLibrary.INSTANCE.get(ResourceLocation.parse("prefabdeploy:nbt_gallery"));
    p.getInventory().setItem(0, new ItemStack(PrefabDeploy.BEACON_ITEM.get(), 3));
    h.getLevel().setBlock(a, Blocks.AIR.defaultBlockState(), 3);
    floatAt(p, a);
    h.assertTrue(
        h.getLevel().getBlockState(a).is(PrefabDeploy.BEACON.get())
            && p.getMainHandItem().getCount() == 2,
        "Air click did not consume/place a floating beacon");
    var b = a.east(f.blueprint().width());
    h.getLevel().setBlock(b.below(), Blocks.STONE.defaultBlockState(), 3);
    var context =
        new BlockPlaceContext(
            p,
            InteractionHand.MAIN_HAND,
            p.getMainHandItem(),
            new BlockHitResult(
                Vec3.atCenterOf(b.below()).add(0, .5, 0), Direction.UP, b.below(), false));
    PrefabDeploy.BEACON_ITEM.get().place(context);
    h.assertTrue(
        h.getLevel().getBlockState(b).is(PrefabDeploy.BEACON.get())
            && p.getMainHandItem().getCount() == 1,
        "Ordinary beacon placement failed");
    p.setGameMode(GameType.CREATIVE);
    floatAt(p, a.south(f.blueprint().depth()));
    h.assertTrue(
        (int) field(session(p), "beaconRefundCount") == 2 && p.getMainHandItem().getCount() == 1,
        "Creative beacon was counted as consumed");
    p.setShiftKeyDown(true);
    PrefabDeploy.BEACON_ITEM.get().use(p.level(), p, InteractionHand.MAIN_HAND);
    h.assertTrue(
        !Sessions.active(p) && p.getMainHandItem().getCount() == 1,
        "Shift-air cancellation placed or consumed a beacon");
    p.setShiftKeyDown(false);
    select(p, false);
    var token = (UUID) field(session(p), "token");
    var fix = Network.message("fix");
    fix.putUUID("token", token);
    fix.putLong("anchor", a.asLong());
    fix.putInt("turns", 0);
    Sessions.receive(p, fix);
    h.assertTrue(DeploymentManager.busy(p.getUUID()), "No validation started");
    var cancel = Network.message("cancel");
    cancel.putUUID("token", token);
    Sessions.receive(p, cancel);
    h.assertTrue(!Sessions.active(p), "Cancellation failed during validation");
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    !DeploymentManager.busy(p.getUUID()),
                    "Cancelled validation is still scheduled"))
        .thenExecute(
            () -> {
              select(p, true);
              p.getInventory().setItem(0, new ItemStack(PrefabDeploy.BEACON_ITEM.get(), 3));
              p.setShiftKeyDown(true);
              PrefabDeploy.BEACON_ITEM
                  .get()
                  .place(
                      new BlockPlaceContext(
                          p,
                          InteractionHand.MAIN_HAND,
                          p.getMainHandItem(),
                          new BlockHitResult(
                              Vec3.atCenterOf(b.below()), Direction.UP, b.below(), false)));
              h.assertTrue(
                  !Sessions.active(p) && p.getMainHandItem().getCount() == 3,
                  "Shift-block cancellation fell through to block placement");
              p.setShiftKeyDown(false);
              p.setPos(b.getX() + .5, b.getY() + 1.5 - p.getEyeHeight(), b.getZ() + .5);
              p.setXRot(90);
              var fallback =
                  PrefabDeploy.BEACON_ITEM.get().use(p.level(), p, InteractionHand.MAIN_HAND);
              h.assertTrue(
                  fallback.getResult() == InteractionResult.PASS
                      && p.getMainHandItem().getCount() == 3,
                  "Failed ordinary placement fell through into floating placement");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 60000, batch = "011_cancel")
  public static void cancellation_after_deploy_keeps_the_real_construction_task(GameTestHelper h)
      throws Exception {
    var p = player(h, "011_cancel", GameType.CREATIVE);
    var pos = h.absolutePos(new BlockPos(20, 6, 20));
    select(p, false);
    UUID token = (UUID) field(session(p), "token");
    var fix = Network.message("fix");
    fix.putUUID("token", token);
    fix.putLong("anchor", pos.asLong());
    fix.putInt("turns", 0);
    Sessions.receive(p, fix);
    var id = new UUID[1];
    h.startSequence()
        .thenWaitUntil(
            () -> {
              try {
                h.assertTrue((boolean) field(session(p), "valid"), "Full validation still pending");
              } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
              } catch (Exception ex) {
                if (ex instanceof RuntimeException r) throw r;
                throw new RuntimeException(ex);
              }
            })
        .thenExecute(
            () -> {
              var deploy = Network.message("deploy");
              deploy.putUUID("token", token);
              Sessions.receive(p, deploy);
              try {
                id[0] = (UUID) field(session(p), "deployment");
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
              p.getInventory().setItem(0, new ItemStack(PrefabDeploy.BEACON_ITEM.get(), 3));
              p.setShiftKeyDown(true);
              PrefabDeploy.BEACON_ITEM.get().use(p.level(), p, InteractionHand.MAIN_HAND);
              h.assertTrue(
                  Sessions.active(p)
                      && DeploymentManager.busy(p.getUUID())
                      && p.getMainHandItem().getCount() == 3,
                  "Cancellation stopped construction or consumed a beacon");
              p.setShiftKeyDown(false);
            })
        .thenWaitUntil(
            () -> {
              h.assertTrue(
                  DeploymentManager.outcome(id[0]).orElse(false),
                  "Construction did not finish after rejected cancellation");
              h.assertTrue(!Sessions.active(p), "Completed task left its placement session active");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 60000, batch = "011_security")
  public static void creative_still_checks_unlock_and_protected_positions(GameTestHelper h) {
    var p = player(h, "011_security", GameType.CREATIVE);
    var pos = h.absolutePos(new BlockPos(20, 3, 20));
    var f = building("011_security", "{\"mode\":\"manual\",\"xp\":100000}");
    var meta = f.metadata().deepCopy();
    meta.addProperty("unlock", false);
    boolean rejected = false;
    try {
      DeploymentManager.submit(
          p,
          f.withMetadata(meta),
          new GridTransform(pos.getX(), pos.getY(), pos.getZ(), 0, 0),
          List.of(),
          false);
    } catch (UiText.Failure ex) {
      rejected = true;
    }
    h.assertTrue(rejected, "Creative mode bypassed unlock rule");
    h.getLevel().setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 3);
    var id =
        DeploymentManager.submit(
            p, f, new GridTransform(pos.getX(), pos.getY(), pos.getZ(), 0, 0), List.of(), false);
    h.succeedWhen(
        () -> {
          h.assertTrue(DeploymentManager.outcome(id).isPresent(), "Protected validation pending");
          h.assertTrue(
              !DeploymentManager.outcome(id).get()
                  && h.getLevel().getBlockState(pos).is(Blocks.BEDROCK)
                  && p.totalExperience == 100,
              "Creative modified protected content or charged XP");
        });
  }

  @GameTest(template = "empty", timeoutTicks = 120000, batch = "011_restart")
  public static void creative_quote_and_beacon_count_survive_journal_recovery(GameTestHelper h) {
    var p = player(h, "011_restart", GameType.CREATIVE);
    var pos = h.absolutePos(new BlockPos(20, 3, 20));
    var markers = List.of(pos.north(2), pos.north(3), pos.north(4));
    var f = building("011_restart", "{\"mode\":\"manual\",\"xp\":100000,\"viscriptshop\":100000}");
    var id = new UUID[1];
    var audit = new java.util.concurrent.CompletableFuture<?>[1];
    var sequence = h.startSequence();
    for (String phase : List.of("BLOCKS", "COMMIT")) {
      boolean committed = phase.equals("COMMIT");
      sequence
          .thenExecute(
              () -> {
                p.setGameMode(GameType.CREATIVE);
                h.getLevel().setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
                for (var m : markers)
                  h.getLevel().setBlock(m, PrefabDeploy.BEACON.get().defaultBlockState(), 3);
                id[0] =
                    DeploymentManager.submit(
                        p,
                        f,
                        new GridTransform(pos.getX(), pos.getY(), pos.getZ(), 0, 0),
                        markers,
                        false,
                        committed ? 1 : 0);
                System.setProperty("prefabdeploy.testHold." + id[0], phase);
              })
          .thenWaitUntil(
              () ->
                  h.assertTrue(
                      DeploymentManager.status().stream()
                          .anyMatch(s -> s.contains(id[0] + " " + phase + " ")),
                      "Waiting for durable creative stage"))
          .thenExecute(() -> audit[0] = DeploymentManager.audit(id[0]))
          .thenWaitUntil(() -> h.assertTrue(audit[0].isDone(), "Journal read pending"))
          .thenExecute(
              () -> {
                var record = (CompoundTag) audit[0].join();
                h.assertTrue(
                    record.getCompound("price").getBoolean("creativeExempt")
                        && record.getInt("beaconRefundCount") == (committed ? 1 : 0),
                    "New journal fields were not durable");
                System.clearProperty("prefabdeploy.testHold." + id[0]);
                DeploymentManager.stop();
                try {
                  p.load(
                      NbtIo.readCompressed(
                          p.server
                              .getWorldPath(LevelResource.ROOT)
                              .resolve("playerdata/" + p.getUUID() + ".dat"),
                          NbtAccounter.create(64L * 1024 * 1024)));
                } catch (Exception ex) {
                  throw new RuntimeException(ex);
                }
                p.setGameMode(GameType.SURVIVAL);
                DeploymentManager.start(p.server);
                DeploymentManager.attachFakePlayerForTesting(id[0], p);
              })
          .thenWaitUntil(
              () -> {
                h.assertTrue(
                    DeploymentManager.outcome(id[0]).isPresent(), "Creative recovery pending");
                h.assertTrue(
                    DeploymentManager.outcome(id[0]).get() == committed
                        && h.getLevel()
                            .getBlockState(pos)
                            .is(committed ? Blocks.STONE : Blocks.DIRT)
                        && p.totalExperience == 100,
                    "Recovery changed creative fee/world outcome");
                int count =
                    p.getInventory().items.stream()
                        .filter(s -> s.is(PrefabDeploy.BEACON_ITEM.get()))
                        .mapToInt(ItemStack::getCount)
                        .sum();
                h.assertTrue(
                    count == (committed ? 1 : 0), "Recovery duplicated or lost beacon refund");
                h.assertTrue(
                    !RegionLocks.locked(h.getLevel(), pos),
                    "Recovered creative task kept its lock");
              });
    }
    sequence.thenSucceed();
  }
}
