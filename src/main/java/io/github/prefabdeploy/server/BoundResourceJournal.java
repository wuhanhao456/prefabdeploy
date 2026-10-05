package io.github.prefabdeploy.server;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** A third-party handler has no atomic-save API. A cold, unfinished mutation must be audited. */
public final class BoundResourceJournal {
  private static final UUID RUNTIME = UUID.randomUUID();
  private static final Map<MinecraftServer, Map<String, BoundResourceJournal>> CACHE = new WeakHashMap<>();
  private final Path path;
  private final CompoundTag identity;
  private CompoundTag disk;
  private final CompoundTag receipts;

  private BoundResourceJournal(MinecraftServer server, CompoundTag id) throws IOException {
    identity = id.copy();
    String key = UUID.nameUUIDFromBytes(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    path = server.getWorldPath(LevelResource.ROOT).resolve("prefabdeploy/bound-resources/" + key + ".journal");
    disk = Files.exists(path) ? read() : new CompoundTag();
    if (!disk.isEmpty() && (disk.getInt("version") != 1 || !disk.getCompound("source").equals(identity)))
      throw new IOException("Bound resource journal identity or version differs");
    receipts = disk.getCompound("receipts").copy();
    if (!disk.hasUUID("runtime") || !disk.getUUID("runtime").equals(RUNTIME)) {
      for (String transaction : receipts.getAllKeys()) {
        var receipt = receipts.getCompound(transaction);
        if (!Set.of("COMMITTED", "REFUNDED").contains(receipt.getString("state"))) {
          receipt.putString("state", "UNCERTAIN");
          receipt.putString("reason", "Third-party storage restarted without atomic mutation evidence");
        }
      }
    }
  }

  public static BoundResourceJournal get(MinecraftServer server, CompoundTag id) {
    String key = id.toString();
    var journals = CACHE.computeIfAbsent(server, s -> new HashMap<>());
    return journals.computeIfAbsent(key, k -> {
      try { return new BoundResourceJournal(server, id); }
      catch (IOException ex) { throw new IllegalStateException("Bound resource journal cannot be read; recovery is pending", ex); }
    });
  }

  public static void clear(MinecraftServer server) { CACHE.remove(server); }
  public CompoundTag receipts() { return receipts; }
  public String location() { return path.toString(); }

  public void verify() {
    try {
      var current = Files.exists(path) ? read() : new CompoundTag();
      if (!current.equals(disk)) throw new IOException("Bound resource journal differs");
    } catch (IOException ex) { throw new IllegalStateException("Resource receipt file differs; recovery is pending", ex); }
  }

  public void save() {
    var snapshot = new CompoundTag();
    snapshot.putInt("version", 1);
    snapshot.putUUID("runtime", RUNTIME);
    snapshot.put("source", identity.copy());
    snapshot.put("receipts", receipts.copy());
    try {
      var out = new ByteArrayOutputStream();
      NbtIo.writeCompressed(snapshot, out);
      AtomicFile.write(path, out.toByteArray());
      disk = snapshot;
    } catch (IOException ex) { throw new IllegalStateException("Resource transaction could not be saved", ex); }
  }

  private CompoundTag read() throws IOException {
    try (var in = new ByteArrayInputStream(AtomicFile.read(path, 64 * 1024 * 1024))) {
      return NbtIo.readCompressed(in, NbtAccounter.create(64L * 1024 * 1024));
    }
  }
}
