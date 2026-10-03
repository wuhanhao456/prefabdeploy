package io.github.prefabdeploy.testing;

import com.mojang.authlib.GameProfile;
import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.library.*;
import io.github.prefabdeploy.network.Network;
import io.github.prefabdeploy.server.Sessions;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("prefabreload")
@PrefixGameTestTemplate(false)
public final class ReloadGameTests {
  @GameTest(template="empty", timeoutTicks=1200)
  public static void command_discovers_updates_and_removes_datapack_buildings(GameTestHelper h) throws Exception {
    var server = h.getLevel().getServer();
    var root = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("prefabdeploy-reload-test");
    var data = root.resolve("data/reloadtest");
    Files.createDirectories(data.resolve("blueprints"));
    Files.createDirectories(data.resolve("prefabs"));
    Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":48,\"description\":\"Reload regression\"}}");
    try (var input = server.getResourceManager().getResourceOrThrow(ResourceLocation.parse("prefabdeploy:blueprints/cottage.nbt")).open()) {
      Files.write(data.resolve("blueprints/house.nbt"), input.readAllBytes());
    }
    Path definition = data.resolve("prefabs/house.json"), bad = data.resolve("prefabs/bad.json");
    String pattern = "{\"name\":\"%s\",\"source\":\"reloadtest:blueprints/house.nbt\",\"cost\":{\"mode\":\"free\"}}";
    Files.writeString(definition, pattern.formatted("Hot1"));
    Files.writeString(bad,"{\"source\":\"reloadtest:blueprints/missing.nbt\",\"cost\":{\"mode\":\"free\"}}");
    var p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(),"012_reload"));
    p.getInventory().setItem(0,new ItemStack(PrefabDeploy.TOOL.get()));
    var select = Network.message("select"); select.putString("id","prefabdeploy:nbt_gallery"); Sessions.receive(p,select);
    var old = PrefabLibrary.INSTANCE.get(ResourceLocation.parse("prefabdeploy:nbt_gallery"));
    var source = server.createCommandSourceStack();
    h.assertTrue(server.getCommands().getDispatcher().execute("prefab reload", source)==1,"Reload command did not start");
    int[] phase = {0};
    h.succeedWhen(() -> {
      var current = PrefabLibrary.INSTANCE.get(ResourceLocation.parse("reloadtest:house"));
      try {
        if (phase[0]==0) {
          h.assertTrue(current!=null && current.valid() && current.name().equals("Hot1"),"New datapack was not discovered");
          h.assertTrue(!PrefabLibrary.INSTANCE.get(ResourceLocation.parse("reloadtest:bad")).valid(),"Bad source was accepted");
          h.assertTrue(Sessions.active(p) && old.blueprint()!=null,"Reload discarded active placement");
          var active = Sessions.class.getDeclaredField("ACTIVE"); active.setAccessible(true);
          var session = ((Map<?,?>)active.get(null)).get(p.getUUID());
          var prefab = session.getClass().getDeclaredField("prefab"); prefab.setAccessible(true);
          h.assertTrue(prefab.get(session)==old,"Reload replaced active snapshot");
          Files.writeString(definition,pattern.formatted("Hot2")); Files.delete(bad);
          h.assertTrue(server.getCommands().getDispatcher().execute("prefab reload",source)==1,"Update reload did not start");
          phase[0]=1;
        } else if (phase[0]==1) {
          h.assertTrue(current!=null && current.name().equals("Hot2"),"Changed definition was not reloaded");
          h.assertTrue(PrefabLibrary.INSTANCE.get(ResourceLocation.parse("reloadtest:bad"))==null,"Deleted definition remains");
          Files.delete(definition);
          h.assertTrue(server.getCommands().getDispatcher().execute("prefab reload",source)==1,"Delete reload did not start");
          phase[0]=2;
        } else {
          h.assertTrue(current==null,"Deleted building remains in catalog");
          Sessions.cancel(p);
          PrefabDeploy.LOGGER.info("PREFAB HOT RELOAD DISCOVERY UPDATE DELETE SNAPSHOT VERIFIED");
          return;
        }
      } catch (GameTestAssertException ex) { throw ex; }
      catch (Exception ex) { throw new GameTestAssertException(ex.toString()); }
      throw new GameTestAssertException("Waiting for the next reload phase");
    });
  }
}
