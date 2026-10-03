package io.github.prefabdeploy.core;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Uses the same local boundary coordinates as the rendered preview frame. */
public final class PreviewTarget {
  public static AABB bounds(GridTransform transform, int width, int height, int depth) {
    var a = transform.point(0, 0, 0);
    var b = transform.point(width, height, depth);
    return new AABB(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
        Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
  }

  public static boolean hit(AABB bounds, Vec3 start, Vec3 end) {
    var box = bounds.inflate(.002);
    return box.contains(start) || box.clip(start, end).isPresent();
  }

  private PreviewTarget() {}
}
