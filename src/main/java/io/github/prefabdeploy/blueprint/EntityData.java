package io.github.prefabdeploy.blueprint;

import net.minecraft.nbt.*;

/** Normalizes actor positions and supported vanilla attachment coordinates. */
public final class EntityData {
  public static CompoundTag normalize(CompoundTag source, double x, double y, double z) {
    var n = source.copy();
    var p = n.getList("Pos", Tag.TAG_DOUBLE);
    double dx = p.size() == 3 ? x - p.getDouble(0) : 0,
        dy = p.size() == 3 ? y - p.getDouble(1) : 0,
        dz = p.size() == 3 ? z - p.getDouble(2) : 0;
    translate(n, dx, dy, dz);
    n.put("Pos", position(x, y, z));
    return n;
  }

  private static void translate(CompoundTag n, double dx, double dy, double dz) {
    var p = n.getList("Pos", Tag.TAG_DOUBLE);
    if (p.size() == 3)
      n.put("Pos", position(p.getDouble(0) + dx, p.getDouble(1) + dy, p.getDouble(2) + dz));
    for (String prefix : new String[] {"Tile", "Sleeping"})
      if (n.contains(prefix + "X")) {
        n.putInt(prefix + "X", (int) Math.floor(n.getInt(prefix + "X") + dx));
        n.putInt(prefix + "Y", (int) Math.floor(n.getInt(prefix + "Y") + dy));
        n.putInt(prefix + "Z", (int) Math.floor(n.getInt(prefix + "Z") + dz));
      }
    var leash = n.getCompound("Leash");
    if (leash.contains("X")) {
      leash.putInt("X", (int) Math.floor(leash.getInt("X") + dx));
      leash.putInt("Y", (int) Math.floor(leash.getInt("Y") + dy));
      leash.putInt("Z", (int) Math.floor(leash.getInt("Z") + dz));
    }
    if (n.contains("leash", Tag.TAG_INT_ARRAY)) {
      int[] point = n.getIntArray("leash");
      if (point.length != 3) throw new IllegalArgumentException("Invalid leash coordinates");
      n.putIntArray(
          "leash",
          new int[] {
            (int) Math.floor(point[0] + dx),
            (int) Math.floor(point[1] + dy),
            (int) Math.floor(point[2] + dz)
          });
    }
    var passengers = n.getList("Passengers", Tag.TAG_COMPOUND);
    for (int i = 0; i < passengers.size(); i++) translate(passengers.getCompound(i), dx, dy, dz);
  }

  public static ListTag position(double... values) {
    var n = new ListTag();
    for (double v : values) n.add(DoubleTag.valueOf(v));
    return n;
  }

  private EntityData() {}
}
