package io.github.prefabdeploy.server;

import io.github.prefabdeploy.PrefabDeploy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Repair old beacon blocks when their chunk loads; never load additional chunks. */
public final class BeaconMigration {
  public static void onChunkLoad(ChunkEvent.Load event) {
    if (!(event.getChunk() instanceof LevelChunk chunk) || event.getLevel().isClientSide()) return;
    var pos = new BlockPos.MutableBlockPos();
    var sections = chunk.getSections();
    for (int index = 0; index < sections.length; index++) {
      var section = sections[index];
      if (!section.maybeHas(state -> state.is(PrefabDeploy.BEACON.get()))) continue;
      int yBase = chunk.getSectionYFromSectionIndex(index) << 4;
      for (int y = 0; y < 16; y++)
        for (int z = 0; z < 16; z++)
          for (int x = 0; x < 16; x++)
            if (section.getBlockState(x, y, z).is(PrefabDeploy.BEACON.get())) {
              pos.set(chunk.getPos().getMinBlockX() + x, yBase + y, chunk.getPos().getMinBlockZ() + z);
              chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
            }
    }
  }

  private BeaconMigration() {}
}
