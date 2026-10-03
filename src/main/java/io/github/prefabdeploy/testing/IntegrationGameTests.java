package io.github.prefabdeploy.testing;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.blueprint.Blueprint;
import io.github.prefabdeploy.compat.*;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.library.*;
import io.github.prefabdeploy.server.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(PrefabDeploy.ID)
@PrefixGameTestTemplate(false)
public final class IntegrationGameTests {
  @GameTest(template = "empty", timeoutTicks = 60000)
  public static void ftb_detector_retains_mod_specific_nbt(GameTestHelper h) {
    if (!OptionalMods.loaded("ftbquests")) {
      h.succeed();
      return;
    }
    var p = player(h, "prefab_mod_nbt");
    var block =
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            ResourceLocation.parse("ftbquests:detector"));
    h.assertTrue(block != Blocks.AIR, "FTB detector block missing");
    var n = new CompoundTag();
    n.putString("id", "ftbquests:detector");
    n.putString("Object", "0000000000001052");
    n.putInt("Radius", 5);
    var bp =
        new Blueprint(
            1,
            1,
            1,
            List.of(new Blueprint.Voxel(BlockPos.ZERO, block.defaultBlockState(), n)),
            List.of(),
            List.of());
    var f = prefab("mod_nbt", bp, "{\"cost\":{\"mode\":\"free\"}}");
    var pos = h.absolutePos(new BlockPos(20, 3, 20));
    var t = new GridTransform(pos.getX(), pos.getY(), pos.getZ(), 1, 0);
    var target = NbtTransforms.pos(t, BlockPos.ZERO);
    var id = DeploymentManager.submit(p, f, t, List.of(), false);
    h.succeedWhen(
        () -> {
          h.assertTrue(DeploymentManager.outcome(id).orElse(false), "Mod block copy incomplete");
          var be = h.getLevel().getBlockEntity(target);
          h.assertTrue(be != null, "Mod block entity missing");
          var saved = be.saveWithFullMetadata(h.getLevel().registryAccess());
          h.assertTrue(
              saved.getInt("Radius") == 5
                  && saved.getString("Object").equals("0000000000001052")
                  && saved.getInt("x") == target.getX(),
              "Mod-specific NBT or coordinates changed");
        });
  }

  private static ServerPlayer player(GameTestHelper h, String name) {
    var p =
        FakePlayerFactory.get(
            h.getLevel(),
            new GameProfile(
                UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                name));
    p.getInventory().clearContent();
    p.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(1, 12, 1)));
    return p;
  }

  private static Object teamManager(ServerPlayer p) throws Exception {
    var api = OptionalMods.call(Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI"), "api");
    var manager = OptionalMods.call(api, "getManager");
    OptionalMods.call(manager, "playerLoggedIn", null, p.getUUID(), p.getGameProfile().getName());
    return manager;
  }

  private static Prefab prefab(String id, Blueprint bp, String meta) {
    return new Prefab(
        ResourceLocation.parse("prefabdeploy:" + id),
        id,
        "test",
        0,
        bp,
        JsonParser.parseString(meta).getAsJsonObject(),
        id,
        "");
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void native_currency_items_xp_share_player_save_boundary(GameTestHelper h)
      throws Exception {
    if (!OptionalMods.loaded("viscript_shop")) {
      h.succeed();
      return;
    }
    var p = player(h, "prefab_currency");
    p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
    p.giveExperiencePoints(100 - p.totalExperience);
    OptionalMods.call(
        Class.forName("com.viscriptshop.util.ViScriptShopServerUtil"), "setMoney", p, 100.0);
    var f =
        prefab(
            "currency",
            new Blueprint(
                1,
                1,
                1,
                List.of(new Blueprint.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null)),
                List.of(),
                List.of()),
            "{\"cost\":{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":2}],\"xp\":30,\"viscriptshop\":12.34}}");
    var id = UUID.randomUUID();
    Costs.reserve(p, id, Costs.quote(p, f));
    h.assertTrue(
        Math.abs(OptionalMods.money(p) - 87.66) < 1e-7
            && p.totalExperience == 70
            && p.getInventory().getItem(0).getCount() == 3,
        "Mixed reservation failed");
    p.load(
        NbtIo.readCompressed(
            p.server.getWorldPath(LevelResource.ROOT).resolve("playerdata/" + p.getUUID() + ".dat"),
            NbtAccounter.create(64L * 1024 * 1024)));
    h.assertTrue(
        Math.abs(OptionalMods.money(p) - 87.66) < 1e-7 && Costs.state(p, id).equals("RESERVED"),
        "Currency and receipt did not reload atomically");
    Costs.reserve(p, id, Costs.quoteUnchecked(p, f));
    Costs.refund(p, id);
    Costs.refund(p, id);
    h.assertTrue(
        Math.abs(OptionalMods.money(p) - 100) < 1e-7
            && p.totalExperience == 100
            && p.getInventory().getItem(0).getCount() == 5,
        "Refund duplicated or lost a resource");
    var settled = UUID.randomUUID();
    Costs.reserve(p, settled, Costs.quote(p, f));
    Costs.commit(p, settled);
    Costs.commit(p, settled);
    h.assertTrue(Math.abs(OptionalMods.money(p) - 87.66) < 1e-7, "Settlement repeated payment");
    PrefabDeploy.LOGGER.info("PREFAB VSS MIXED TRANSACTION VERIFIED");
    h.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void ftb_team_flags_quest_completion_and_kube_hooks(GameTestHelper h)
      throws Exception {
    if (!OptionalMods.loaded("ftbquests")) {
      h.succeed();
      return;
    }
    var p = player(h, "prefab_quest");
    teamManager(p);
    var team = OptionalMods.team(p);
    var f = PrefabLibrary.INSTANCE.get(ResourceLocation.parse("prefabdeploy:cottage"));
    ServerState.flag(p.server, team + ":integration", true);
    h.assertTrue(
        Rules.test(
                JsonParser.parseString(
                    "{\"type\":\"flag\",\"scope\":\"team\",\"id\":\"integration\"}"),
                p,
                f,
                p.blockPosition())
            .isEmpty(),
        "Team flag adapter failed");
    var api = OptionalMods.call(Class.forName("dev.ftb.mods.ftbquests.api.FTBQuestsAPI"), "api");
    var file = OptionalMods.call(api, "getQuestFile", false);
    var base = Class.forName("dev.ftb.mods.ftbquests.quest.BaseQuestFile");
    var group = Class.forName("dev.ftb.mods.ftbquests.quest.ChapterGroup");
    var chapterType = Class.forName("dev.ftb.mods.ftbquests.quest.Chapter");
    var chapter =
        chapterType
            .getConstructor(long.class, base, group)
            .newInstance(0x1051L, file, OptionalMods.call(file, "getDefaultChapterGroup"));
    var quest =
        Class.forName("dev.ftb.mods.ftbquests.quest.Quest")
            .getConstructor(long.class, chapterType)
            .newInstance(0x1052L, chapter);
    OptionalMods.call(chapter, "onCreated");
    OptionalMods.call(quest, "onCreated");
    var data = (Optional<?>) OptionalMods.call(file, "getTeamData", p);
    h.assertTrue(data.isPresent(), "Quest team data missing");
    OptionalMods.call(data.get(), "setCompleted", 0x1052L, new Date());
    OptionalMods.call(data.get(), "clearCachedProgress");
    h.assertTrue(OptionalMods.quest(p, "1052"), "Completed quest not recognized");
    if (Boolean.getBoolean("prefabdeploy.compatFixtures")) {
      h.assertTrue(
          Rules.test(f.metadata().get("unlock"), p, f, p.blockPosition()).isEmpty(),
          "KubeJS rule not registered");
      var denied = prefab("test_kube_deny", f.blueprint(), "{\"cost\":{\"mode\":\"free\"}}");
      h.assertTrue(
          PrefabEvents.before(p, denied).equals("KubeJS test denied"), "KubeJS veto not delivered");
    }
    PrefabDeploy.LOGGER.info("PREFAB FTB TEAM AND QUEST VERIFIED");
    h.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 60000, batch = "claims")
  public static void foreign_claim_rejects_all_positions_before_payment(GameTestHelper h)
      throws Exception {
    if (!OptionalMods.loaded("ftbchunks")) {
      h.succeed();
      return;
    }
    var p = player(h, "prefab_intruder");
    var owner = player(h, "prefab_claim_owner");
    teamManager(p);
    teamManager(owner);
    p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
    var origin = h.absolutePos(new BlockPos(20, 3, 20));
    int boundary = ((origin.getX() >> 4) + 1) * 16;
    var pos = new BlockPos(boundary - 1, origin.getY(), origin.getZ());
    var denied = pos.east();
    h.getLevel().setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
    h.getLevel().setBlock(denied, Blocks.DIRT.defaultBlockState(), 3);
    var fakePolicy =
        Class.forName("dev.ftb.mods.ftbchunks.FTBChunksWorldConfig")
            .getField("ALLOW_FAKE_PLAYERS")
            .get(null);
    OptionalMods.call(
        fakePolicy,
        "set",
        Class.forName("dev.ftb.mods.ftbchunks.api.ProtectionPolicy").getField("CHECK").get(null));
    var api = OptionalMods.call(Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI"), "api");
    var manager = OptionalMods.call(api, "getManager");
    var data = OptionalMods.call(manager, "getOrCreateData", owner);
    OptionalMods.call(data, "setExtraClaimChunks", 16);
    OptionalMods.call(data, "updateLimits");
    var cp =
        Class.forName("dev.ftb.mods.ftblibrary.math.ChunkDimPos")
            .getConstructor(net.minecraft.resources.ResourceKey.class, ChunkPos.class)
            .newInstance(h.getLevel().dimension(), new ChunkPos(denied));
    var claim = OptionalMods.call(data, "claim", owner.createCommandSourceStack(), cp, false);
    h.assertTrue(
        Boolean.TRUE.equals(OptionalMods.call(claim, "isSuccess")),
        "Fixture claim failed: " + claim);
    h.assertTrue(
        !OptionalMods.protection(p, denied).isEmpty(), "Foreign claim did not block the adapter");
    var f =
        prefab(
            "claim_reject",
            new Blueprint(
                2,
                1,
                1,
                List.of(
                    new Blueprint.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null),
                    new Blueprint.Voxel(
                        new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState(), null)),
                List.of(),
                List.of()),
            "{\"cost\":{\"mode\":\"manual\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":2}]}}");
    var id =
        DeploymentManager.submit(
            p, f, new GridTransform(pos.getX(), pos.getY(), pos.getZ(), 0, 0), List.of(), false);
    h.succeedWhen(
        () -> {
          h.assertTrue(DeploymentManager.outcome(id).isPresent(), "Claim validation incomplete");
          h.assertTrue(
              !DeploymentManager.outcome(id).get()
                  && h.getLevel().getBlockState(pos).is(Blocks.DIRT)
                  && h.getLevel().getBlockState(denied).is(Blocks.DIRT)
                  && p.getInventory().getItem(0).getCount() == 5,
              "Claim rejection changed world or charged cost");
          try {
            OptionalMods.call(data, "unclaim", owner.createCommandSourceStack(), cp, false, true);
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
          PrefabDeploy.LOGGER.info("PREFAB FOREIGN CLAIM VERIFIED");
        });
  }
}
