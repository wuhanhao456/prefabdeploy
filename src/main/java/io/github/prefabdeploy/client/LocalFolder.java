package io.github.prefabdeploy.client;

import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.library.LocalBlueprints;
import java.nio.file.Files;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class LocalFolder {
  static void open() {
    var mc = Minecraft.getInstance();
    if (!Client.supported() || !Client.localImportAllowed || !mc.hasSingleplayerServer()) return;
    try {
      var folder = LocalBlueprints.directory();
      Files.createDirectories(folder);
      String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
      new ProcessBuilder(os.contains("win") ? "explorer.exe" : os.contains("mac") ? "open" : "xdg-open",
          folder.toString()).start();
    } catch (Exception ex) {
      PrefabDeploy.LOGGER.warn("Unable to open blueprint folder", ex);
      if (mc.player != null) mc.player.displayClientMessage(
          Component.translatable("prefabdeploy.local.folder_open_failed"), false);
    }
  }

  private LocalFolder() {}
}
