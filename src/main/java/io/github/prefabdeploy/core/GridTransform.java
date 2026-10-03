package io.github.prefabdeploy.core;

/** A is a grid boundary corner, so negative axes need a one-cell correction. */
public record GridTransform(int x, int y, int z, int turns, int groundY) {
  public GridTransform {
    turns = Math.floorMod(turns, 4);
  }

  public record Cell(int x, int y, int z) {}

  public record Point(double x, double y, double z) {}

  public Cell cell(int lx, int ly, int lz) {
    return switch (turns) {
      case 0 -> new Cell(x + lx, y + ly - groundY, z + lz);
      case 1 -> new Cell(x - lz - 1, y + ly - groundY, z + lx);
      case 2 -> new Cell(x - lx - 1, y + ly - groundY, z - lz - 1);
      default -> new Cell(x + lz, y + ly - groundY, z - lx - 1);
    };
  }

  public Point point(double lx, double ly, double lz) {
    return switch (turns) {
      case 0 -> new Point(x + lx, y + ly - groundY, z + lz);
      case 1 -> new Point(x - lz, y + ly - groundY, z + lx);
      case 2 -> new Point(x - lx, y + ly - groundY, z - lz);
      default -> new Point(x + lz, y + ly - groundY, z - lx);
    };
  }

  public Cell cornerB(int width) {
    var p = point(width, groundY, 0);
    return new Cell((int) p.x, (int) p.y, (int) p.z);
  }

  public Cell cornerC(int depth) {
    var p = point(0, groundY, depth);
    return new Cell((int) p.x, (int) p.y, (int) p.z);
  }

  public static int direction(int dx, int dz, int width) {
    if (dx == width && dz == 0) return 0;
    if (dx == 0 && dz == width) return 1;
    if (dx == -width && dz == 0) return 2;
    if (dx == 0 && dz == -width) return 3;
    return -1;
  }
}
