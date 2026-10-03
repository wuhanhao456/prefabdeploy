package io.github.beaconvisual;

/** Stateless animation in block coordinates, shared by the renderer and particles. */
public final class BeaconVisualMotion {
  private BeaconVisualMotion() {}

  public static double bob(long gameTime, float partialTick) {
    return (0.75 / 16.0) * Math.sin(((gameTime % 80) + partialTick) * Math.PI * 2.0 / 80.0);
  }

  public static float yaw(long gameTime, float partialTick) {
    return (float) (((gameTime % 160) + partialTick) * 360.0 / 160.0);
  }
}
