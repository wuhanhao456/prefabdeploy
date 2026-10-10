package io.github.prefabdeploy.library;

import io.github.prefabdeploy.PrefabDeploy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.world.level.validation.DirectoryValidator;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/** Building datapacks are authoritative server resources; they never upload client files. */
public final class BlueprintPacks implements RepositorySource {
  public static final String BUILTIN_ID = "mod/prefabdeploy:builtin/mob_towers";
  public static final String FOLDER_PREFIX = "prefabdeploy_blueprints/";
  private final Path root;

  public BlueprintPacks(Path root) { this.root = root; }

  public static void register(AddPackFindersEvent event) {
    if (event.getPackType() != PackType.SERVER_DATA) return;
    event.addPackFinders(ResourceLocation.fromNamespaceAndPath(PrefabDeploy.ID, "builtin/mob_towers"),
        PackType.SERVER_DATA, Component.literal("Prefab Deploy mob towers"), PackSource.BUILT_IN,
        true, Pack.Position.BOTTOM);
    // Trusted repositories are created on remote clients for known-pack matching.
    if (!event.isTrusted()) event.addRepositorySource(new BlueprintPacks(LocalBlueprints.directory()));
  }

  @Override
  public void loadPacks(Consumer<Pack> accept) {
    try {
      java.nio.file.Files.createDirectories(root);
      // Match the local importer: no linked content outside the instance directory.
      FolderRepositorySource.discoverPacks(root, new DirectoryValidator(path -> false), (path, supplier) -> {
        var location = new PackLocationInfo(FOLDER_PREFIX + path.getFileName(),
            Component.literal(path.getFileName().toString()), PackSource.SERVER, Optional.empty());
        try {
          var resources = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
              ? new SnapshotZipSupplier(path) : supplier;
          var pack = Pack.readMetaAndCreate(location, resources, PackType.SERVER_DATA,
              new PackSelectionConfig(false, Pack.Position.TOP, false));
          if (pack != null) accept.accept(pack);
        } catch (UncheckedIOException error) {
          PrefabDeploy.LOGGER.warn("Unable to read building datapack {}", path, error);
        }
      });
    } catch (IOException error) {
      PrefabDeploy.LOGGER.warn("Unable to discover building datapacks in {}", root, error);
    }
  }

  /** Keep native ZIP/overlay semantics without holding the author's ZIP open on Windows. */
  private record SnapshotZipSupplier(Path source) implements Pack.ResourcesSupplier {
    private FilePackResources.SharedZipFileAccess snapshot() {
      Path temporary = null;
      try {
        temporary = Files.createTempFile("prefabdeploy-pack-", ".zip");
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
        final Path copy = temporary;
        return new FilePackResources.SharedZipFileAccess(copy.toFile()) {
          @Override public void close() {
            super.close();
            try { Files.deleteIfExists(copy); }
            catch (IOException error) {
              copy.toFile().deleteOnExit();
              PrefabDeploy.LOGGER.warn("Unable to remove temporary building datapack {}", copy, error);
            }
          }
        };
      } catch (IOException error) {
        if (temporary != null) {
          try { Files.deleteIfExists(temporary); }
          catch (IOException cleanup) { error.addSuppressed(cleanup); }
        }
        throw new UncheckedIOException(error);
      }
    }

    @Override public PackResources openPrimary(PackLocationInfo location) {
      return new FilePackResources(location, snapshot(), "");
    }

    @Override public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
      var access = snapshot();
      var primary = new FilePackResources(location, access, "");
      if (metadata.overlays().isEmpty()) return primary;
      var overlays = new ArrayList<PackResources>();
      for (var overlay : metadata.overlays()) overlays.add(new FilePackResources(location, access, overlay));
      return new CompositePackResources(primary, overlays);
    }
  }
}
