package io.github.prefabdeploy.blueprint;

import io.github.prefabdeploy.api.BlueprintImporter;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class StructureImporter implements BlueprintImporter {
  public boolean accepts(String extension) {
    return extension.equals("nbt");
  }

  public Blueprint read(CompoundTag root, int limit) {
    var size = root.getList("size", Tag.TAG_INT);
    if (size.size() != 3) throw new IllegalArgumentException("Missing structure size");
    var paletteNbt = root.getList("palette", Tag.TAG_COMPOUND);
    if (paletteNbt.isEmpty() && root.contains("palettes"))
      paletteNbt = (ListTag) root.getList("palettes", Tag.TAG_LIST).get(0);
    var palette = new ArrayList<BlockState>();
    for (int i = 0; i < paletteNbt.size(); i++)
      palette.add(StateCodec.read(paletteNbt.getCompound(i)));
    var list = root.getList("blocks", Tag.TAG_COMPOUND);
    if (list.size() > limit) throw new IllegalArgumentException("Position limit exceeded");
    var blocks = new LinkedHashMap<BlockPos, Blueprint.Voxel>();
    for (int i = 0; i < list.size(); i++) {
      var n = list.getCompound(i);
      var p = n.getList("pos", Tag.TAG_INT);
      if (p.size() != 3) throw new IllegalArgumentException("Invalid block position");
      var pos = new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2));
      if (pos.getX() < 0
          || pos.getY() < 0
          || pos.getZ() < 0
          || pos.getX() >= size.getInt(0)
          || pos.getY() >= size.getInt(1)
          || pos.getZ() >= size.getInt(2))
        throw new IllegalArgumentException("Position outside structure");
      var state = palette.get(n.getInt("state"));
      if (state.is(Blocks.STRUCTURE_VOID)) continue;
      if (blocks.put(
              pos, new Blueprint.Voxel(pos, state, n.contains("nbt") ? n.getCompound("nbt") : null))
          != null) throw new IllegalArgumentException("Duplicate structure position");
    }
    var entities = new ArrayList<Blueprint.Actor>();
    var es = root.getList("entities", Tag.TAG_COMPOUND);
    for (int i = 0; i < es.size(); i++) {
      var n = es.getCompound(i);
      var p = n.getList("pos", Tag.TAG_DOUBLE);
      if (p.size() != 3) throw new IllegalArgumentException("Invalid entity position");
      entities.add(
          new Blueprint.Actor(
              p.getDouble(0), p.getDouble(1), p.getDouble(2), n.getCompound("nbt")));
    }
    return new Blueprint(
        size.getInt(0),
        size.getInt(1),
        size.getInt(2),
        new ArrayList<>(blocks.values()),
        entities,
        List.of());
  }
}
