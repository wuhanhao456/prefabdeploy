package io.github.prefabdeploy.server;

import io.github.prefabdeploy.PrefabDeploy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.*;
import net.neoforged.neoforge.registries.*;

/** Container inventory and its receipt are serialized in one chunk snapshot. */
public final class ContainerReceipts {
  private static final DeferredRegister<AttachmentType<?>> TYPES =
      DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, PrefabDeploy.ID);
  public static final DeferredHolder<AttachmentType<?>, AttachmentType<CompoundTag>> TYPE =
      TYPES.register("container_receipts", () -> AttachmentType.builder(() -> new CompoundTag())
          .serialize(CompoundTag.CODEC).build());

  public static void register(IEventBus bus) { TYPES.register(bus); }
  public static CompoundTag ledger(LevelChunk chunk) { return chunk.getData(TYPE); }

  public static void verify(ServerLevel level, LevelChunk chunk) {
    var disk = level.getChunkSource().chunkMap.read(chunk.getPos()).join();
    if (disk.isEmpty()) {
      if (!ledger(chunk).isEmpty()) throw new IllegalStateException("Resource receipt file is missing; recovery is pending");
      return;
    }
    var saved = disk.get().getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY)
        .getCompound("prefabdeploy:container_receipts");
    if (!saved.equals(ledger(chunk)))
      throw new IllegalStateException("Resource receipt file differs; recovery is pending");
  }

  public static void save(ServerLevel level, LevelChunk chunk) {
    chunk.setUnsaved(true);
    var snapshot = ChunkSerializer.write(level, chunk);
    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
        new net.neoforged.neoforge.event.level.ChunkDataEvent.Save(chunk, level, snapshot));
    try {
      level.getChunkSource().chunkMap.write(chunk.getPos(), snapshot).join();
      level.getChunkSource().chunkMap.flushWorker();
      chunk.setUnsaved(false);
    } catch (RuntimeException ex) {
      chunk.setUnsaved(true);
      throw new IllegalStateException("Resource transaction could not be saved", ex);
    }
  }

  private ContainerReceipts() {}
}
