package io.github.prefabdeploy.server;

import io.github.prefabdeploy.api.PrefabApi;
import io.github.prefabdeploy.blueprint.Blueprint;
import io.github.prefabdeploy.core.GridTransform;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Rotation;

public final class NbtTransforms {
  public static Rotation rotation(int turns) {
    return Rotation.values()[Math.floorMod(turns, 4)];
  }

  public static BlockPos pos(GridTransform t, BlockPos p) {
    var c = t.cell(p.getX(), p.getY(), p.getZ());
    return new BlockPos(c.x(), c.y(), c.z());
  }

  public static CompoundTag block(Blueprint.Voxel v, GridTransform t) {
    if (v.nbt() == null) return null;
    var n = v.nbt().copy();
    var p = pos(t, v.pos());
    n.putInt("x", p.getX());
    n.putInt("y", p.getY());
    n.putInt("z", p.getZ());
    for (var adapter : PrefabApi.NBT_ADAPTERS)
      if (adapter.accepts(v.state())) adapter.transform(v.state(), n, t);
    return n;
  }

  public static CompoundTag entity(
      Blueprint.Actor e, GridTransform t, UUID uuid, Map<UUID, UUID> ids, UUID job) {
    var n = e.nbt().copy();
    remap(n, ids);
    transformEntity(n, t, job);
    n.putUUID("UUID", uuid);
    return n;
  }

  private static void transformEntity(CompoundTag n, GridTransform t, UUID job) {
    var source = n.getList("Pos", Tag.TAG_DOUBLE);
    if (source.size() == 3) {
      var p = t.point(source.getDouble(0), source.getDouble(1), source.getDouble(2));
      n.put("Pos", doubles(p.x(), p.y(), p.z()));
    }
    var r = n.getList("Rotation", Tag.TAG_FLOAT);
    float yaw = r.isEmpty() ? 0 : r.getFloat(0);
    var rotated = new ListTag();
    rotated.add(FloatTag.valueOf(yaw + 90 * t.turns()));
    rotated.add(FloatTag.valueOf(r.size() > 1 ? r.getFloat(1) : 0));
    n.put("Rotation", rotated);
    if (!n.hasUUID("UUID")) n.putUUID("UUID", UUID.randomUUID());
    n.remove("UUIDMost");
    n.remove("UUIDLeast");
    n.putUUID("prefabdeploy_job", job);
    for (String prefix : new String[] {"Tile", "Sleeping"})
      if (n.contains(prefix + "X")) {
        var tile = t.cell(n.getInt(prefix + "X"), n.getInt(prefix + "Y"), n.getInt(prefix + "Z"));
        n.putInt(prefix + "X", tile.x());
        n.putInt(prefix + "Y", tile.y());
        n.putInt(prefix + "Z", tile.z());
      }
    var motion = n.getList("Motion", Tag.TAG_DOUBLE);
    if (motion.size() == 3) {
      var origin = t.point(0, 0, 0);
      var vector = t.point(motion.getDouble(0), motion.getDouble(1), motion.getDouble(2));
      n.put(
          "Motion",
          doubles(vector.x() - origin.x(), vector.y() - origin.y(), vector.z() - origin.z()));
    }
    var leash = n.getCompound("Leash");
    if (leash.contains("X")) {
      var tile = t.cell(leash.getInt("X"), leash.getInt("Y"), leash.getInt("Z"));
      leash.putInt("X", tile.x());
      leash.putInt("Y", tile.y());
      leash.putInt("Z", tile.z());
    }
    if (n.contains("Facing", Tag.TAG_BYTE)) {
      int facing = n.getByte("Facing");
      if (facing >= 2 && facing <= 5) {
        var direction = net.minecraft.core.Direction.from3DDataValue(facing);
        for (int i = 0; i < t.turns(); i++) direction = direction.getClockWise();
        n.putByte("Facing", (byte) direction.get3DDataValue());
      }
    }
    if (n.contains("facing", Tag.TAG_BYTE))
      n.putByte("facing", (byte) Math.floorMod(n.getByte("facing") + t.turns(), 4));
    if (n.contains("leash", Tag.TAG_INT_ARRAY)) {
      int[] point = n.getIntArray("leash");
      if (point.length != 3) throw new IllegalArgumentException("Invalid leash coordinates");
      var tile = t.cell(point[0], point[1], point[2]);
      n.putIntArray("leash", new int[] {tile.x(), tile.y(), tile.z()});
    }
    for (var adapter : PrefabApi.NBT_ADAPTERS) adapter.transformEntity(n, t);
    var passengers = n.getList("Passengers", Tag.TAG_COMPOUND);
    for (int i = 0; i < passengers.size(); i++) transformEntity(passengers.getCompound(i), t, job);
  }

  private static void remap(Tag tag, Map<UUID, UUID> ids) {
    if (tag instanceof CompoundTag n)
      for (String key : new ArrayList<>(n.getAllKeys())) {
        if (n.hasUUID(key)) {
          var old = n.getUUID(key);
          if (ids.containsKey(old)) n.putUUID(key, ids.get(old));
        } else remap(n.get(key), ids);
      }
    else if (tag instanceof ListTag list) for (Tag child : list) remap(child, ids);
  }

  public static ListTag doubles(double... values) {
    var list = new ListTag();
    for (double d : values) list.add(DoubleTag.valueOf(d));
    return list;
  }

  private NbtTransforms() {}
}
