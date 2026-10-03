package io.github.prefabdeploy.blueprint;

import io.github.prefabdeploy.api.BlueprintImporter;
import io.github.prefabdeploy.core.PackedStates;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;

public final class LitematicImporter implements BlueprintImporter {
  public boolean accepts(String extension) {
    return extension.equals("litematic");
  }

  private static BlockPos vector(CompoundTag n) {
    return new BlockPos(n.getInt("x"), n.getInt("y"), n.getInt("z"));
  }

  public Blueprint read(CompoundTag root, int limit) {
    int version = root.getInt("Version");
    if (version < 5 || version > 7)
      throw new IllegalArgumentException("Supported litematic versions: 5..7");
    var regions = root.getCompound("Regions");
    if (regions.isEmpty()) throw new IllegalArgumentException("No litematic regions");
    BlockPos min = null, max = null;
    long volume = 0;
    for (String name : regions.getAllKeys()) {
      var r = regions.getCompound(name);
      var p = vector(r.getCompound("Position"));
      var s = vector(r.getCompound("Size"));
      volume += PackedStates.volume(s.getX(), s.getY(), s.getZ(), limit);
      if (volume > limit) throw new IllegalArgumentException("Position limit exceeded");
      var end =
          p.offset(
              s.getX() > 0 ? s.getX() - 1 : s.getX() + 1,
              s.getY() > 0 ? s.getY() - 1 : s.getY() + 1,
              s.getZ() > 0 ? s.getZ() - 1 : s.getZ() + 1);
      var lo =
          new BlockPos(
              Math.min(p.getX(), end.getX()),
              Math.min(p.getY(), end.getY()),
              Math.min(p.getZ(), end.getZ()));
      var hi =
          new BlockPos(
              Math.max(p.getX(), end.getX()),
              Math.max(p.getY(), end.getY()),
              Math.max(p.getZ(), end.getZ()));
      min =
          min == null
              ? lo
              : new BlockPos(
                  Math.min(min.getX(), lo.getX()),
                  Math.min(min.getY(), lo.getY()),
                  Math.min(min.getZ(), lo.getZ()));
      max =
          max == null
              ? hi
              : new BlockPos(
                  Math.max(max.getX(), hi.getX()),
                  Math.max(max.getY(), hi.getY()),
                  Math.max(max.getZ(), hi.getZ()));
    }
    var blocks = new LinkedHashMap<BlockPos, Blueprint.Voxel>();
    var actors = new ArrayList<Blueprint.Actor>();
    var ticks = new ArrayList<Blueprint.Tick>();
    var names = new ArrayList<>(regions.getAllKeys());
    Collections.sort(names);
    for (String name : names) {
      var r = regions.getCompound(name);
      var origin = vector(r.getCompound("Position"));
      var s = vector(r.getCompound("Size"));
      int sx = Math.abs(s.getX()), sy = Math.abs(s.getY()), sz = Math.abs(s.getZ());
      var lo =
          origin.offset(
              Math.min(0, s.getX() + 1), Math.min(0, s.getY() + 1), Math.min(0, s.getZ() + 1));
      var offset = lo.subtract(min);
      var palette = new ArrayList<BlockState>();
      var ps = r.getList("BlockStatePalette", Tag.TAG_COMPOUND);
      for (int i = 0; i < ps.size(); i++) palette.add(StateCodec.read(ps.getCompound(i)));
      int bits = PackedStates.bits(palette.size());
      long[] words = r.getLongArray("BlockStates");
      var bes = new HashMap<BlockPos, CompoundTag>();
      var ts = r.getList("TileEntities", Tag.TAG_COMPOUND);
      for (int i = 0; i < ts.size(); i++) {
        var n = ts.getCompound(i);
        bes.put(vector(n), n);
      }
      for (int y = 0; y < sy; y++)
        for (int z = 0; z < sz; z++)
          for (int x = 0; x < sx; x++) {
            var local = new BlockPos(x, y, z);
            var pos = local.offset(offset);
            var state = palette.get(PackedStates.get(words, bits, (y * sz + z) * sx + x));
            var v = new Blueprint.Voxel(pos, state, bes.get(local));
            var old = blocks.putIfAbsent(pos, v);
            if (old != null && (!old.state().equals(state) || !Objects.equals(old.nbt(), v.nbt())))
              throw new IllegalArgumentException("Conflicting overlapping regions at " + pos);
          }
      var es = r.getList("Entities", Tag.TAG_COMPOUND);
      for (int i = 0; i < es.size(); i++) {
        var n = es.getCompound(i);
        var p = n.getList("Pos", Tag.TAG_DOUBLE);
        if (p.size() != 3) throw new IllegalArgumentException("Invalid entity position");
        actors.add(
            new Blueprint.Actor(
                p.getDouble(0) + origin.getX() - min.getX(),
                p.getDouble(1) + origin.getY() - min.getY(),
                p.getDouble(2) + origin.getZ() - min.getZ(),
                n));
      }
      for (boolean fluid : new boolean[] {false, true}) {
        var pending =
            r.getList(fluid ? "PendingFluidTicks" : "PendingBlockTicks", Tag.TAG_COMPOUND);
        for (int i = 0; i < pending.size(); i++) {
          var n = pending.getCompound(i);
          ticks.add(
              new Blueprint.Tick(
                  vector(n).offset(offset),
                  n.getString(fluid ? "Fluid" : "Block"),
                  fluid,
                  n.getLong("Time"),
                  n.getInt("Priority")));
        }
      }
    }
    return new Blueprint(
        max.getX() - min.getX() + 1,
        max.getY() - min.getY() + 1,
        max.getZ() - min.getZ() + 1,
        new ArrayList<>(blocks.values()),
        actors,
        ticks);
  }
}
