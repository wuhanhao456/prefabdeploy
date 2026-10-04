package io.github.prefabdeploy.server;

import java.io.*;
import java.nio.file.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.level.saveddata.SavedData;

/** Resource SavedData never uses NeoForge's asynchronous, potentially stale autosave snapshots. */
public final class ResourceReceiptStorage {
  public static final String TAG = "prefabdeploy_resource_receipts";

  public static boolean supported(SavedData data) {
    return switch (data.getClass().getName()) {
      case "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage",
          "net.p3pp3rf1y.sophisticatedcore.linkedstorage.LinkedStorageGroupsSavedData",
          "com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet" -> true;
      default -> false;
    };
  }

  public static CompoundTag receipts(SavedData data) {
    if (!supported(data) || !(data instanceof ResourceReceiptAccess access))
      throw new IllegalStateException("Resource persistence adapter unavailable");
    return access.prefabResourceReceipts();
  }

  public static void loaded(SavedData data, CompoundTag contents) {
    if (supported(data))
      ((ResourceReceiptAccess) data).prefabResourceReceipts(contents.getCompound(TAG).copy());
  }

  public static void save(SavedData data, Path path, HolderLookup.Provider registries) {
    try {
      var contents = data.save(new CompoundTag(), registries);
      contents.put(TAG, receipts(data).copy());
      var root = new CompoundTag();
      root.put("data", contents);
      root.putInt("DataVersion", 3955);
      var out = new ByteArrayOutputStream();
      NbtIo.writeCompressed(root, out);
      AtomicFile.writeRaw(path, out.toByteArray());
      data.setDirty(false);
    } catch (IOException ex) {
      data.setDirty();
      throw new IllegalStateException("Resource transaction could not be saved", ex);
    }
  }

  /** A swallowed vanilla SavedData load error must never look like an unpaid transaction. */
  public static void verify(SavedData data, Path path) {
    if (!Files.exists(path)) {
      if (!receipts(data).isEmpty())
        throw new IllegalStateException("Resource receipt file is missing; recovery is pending");
      return;
    }
    try {
      var disk = NbtIo.readCompressed(path, NbtAccounter.create(256L * 1024 * 1024));
      if (!disk.contains("data", Tag.TAG_COMPOUND)
          || !disk.getCompound("data").getCompound(TAG).equals(receipts(data)))
        throw new IllegalStateException("Resource receipt file differs; recovery is pending");
    } catch (IOException ex) {
      throw new IllegalStateException("Resource receipt file cannot be read; recovery is pending", ex);
    }
  }

  private ResourceReceiptStorage() {}
}
