package io.github.prefabdeploy.server;

import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.UiText;
import io.github.prefabdeploy.library.*;
import java.util.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/** Administrative resource reload. Active sessions keep their existing prefab and quote. */
public final class PrefabReload {
  private static final Set<MinecraftServer> RELOADING = Collections.newSetFromMap(new WeakHashMap<>());

  public static int run(CommandSourceStack source) {
    var server = source.getServer();
    if (!RELOADING.add(server)) {
      source.sendFailure(UiText.tr("reload.busy", "Prefab data is already reloading").component());
      return 0;
    }
    source.sendSuccess(() -> UiText.tr("reload.started", "Reloading prefab data...").component(), false);
    try {
      var repository = server.getPackRepository();
      var selected = List.copyOf(repository.getSelectedIds());
      repository.reload();
      var packs = new ArrayList<>(selected);
      var disabled = server.getWorldData().getDataConfiguration().dataPacks().getDisabled();
      for (var id : repository.getAvailableIds())
        if (!disabled.contains(id) && !packs.contains(id)) packs.add(id);
      net.neoforged.neoforge.resource.ResourcePackLoader.reorderNewlyDiscoveredPacks(packs, selected, repository);
      server.reloadResources(packs).whenComplete((unused, error) -> server.execute(() -> {
        if (error != null) {
          failed(source, error);
          return;
        }
        Runnable finish = () -> {
          RELOADING.remove(server);
          var entries = PrefabLibrary.INSTANCE.entries().values();
          long invalid = entries.stream().filter(f -> !f.valid()).count();
          source.sendSuccess(() -> UiText.tr("reload.completed", "Loaded %s buildings (%s unavailable)", entries.size(), invalid).component(), true);
        };
        var host = server.getPlayerList().getPlayers().stream().filter(LocalBlueprints::allowed).findFirst();
        if (host.isPresent()) LocalBlueprints.refresh(host.get(), finish, error2 -> failed(source, error2));
        else finish.run();
      }));
    } catch (Exception error) { failed(source, error); return 0; }
    return 1;
  }

  private static void failed(CommandSourceStack source, Throwable error) {
    RELOADING.remove(source.getServer());
    PrefabDeploy.LOGGER.error("Prefab data reload failed", error);
    source.sendFailure(UiText.tr("reload.failed", "Prefab data reload failed; check the server log").component());
  }

  private PrefabReload() {}
}
