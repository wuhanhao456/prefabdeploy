package io.github.beaconvisual.client;

import io.github.beaconvisual.BeaconVisualBlockEntity;
import io.github.beaconvisual.BeaconVisualMotion;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.joml.Vector3f;

/** Call register while constructing the mod, on the physical client only. */
@OnlyIn(Dist.CLIENT)
public final class BeaconVisualClient {
  private static final DustParticleOptions RED_DUST = new DustParticleOptions(new Vector3f(1.0F, 0.12F, 0.08F), 0.45F);
  private static boolean registered;
  static long spawnedParticles;

  private BeaconVisualClient() {}

  public static void register(IEventBus modBus, Supplier<BlockEntityType<BeaconVisualBlockEntity>> type) {
    Objects.requireNonNull(modBus); Objects.requireNonNull(type);
    if (registered) throw new IllegalStateException("Register one visual type once; it can support multiple beacon blocks.");
    registered = true;
    modBus.addListener((ModelEvent.RegisterAdditional e) -> e.register(BeaconVisualRenderer.CORE_MODEL));
    modBus.addListener((EntityRenderersEvent.RegisterRenderers e) ->
        e.registerBlockEntityRenderer(type.get(), BeaconVisualRenderer::new));
    modBus.addListener((FMLClientSetupEvent e) ->
        e.enqueueWork(() -> BeaconVisualBlockEntity.setClientTicker(BeaconVisualClient::tickParticles)));
  }

  private static void tickParticles(BeaconVisualBlockEntity entity) {
    Minecraft mc = Minecraft.getInstance();
    var level = entity.getLevel();
    if (mc.player == null || level == null || mc.level != level) return;
    var pos = entity.getBlockPos();
    if (mc.player.distanceToSqr(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5) > 24*24) return;
    long time = level.getGameTime();
    // Position-dependent phase staggers emissions, without moving the reference point.
    if (Math.floorMod(time + pos.asLong(), 4) != 0) return;
    double angle = (time % 160) * Math.PI * 2 / 160.0 + Math.floorMod(pos.asLong(), 17);
    double bob = BeaconVisualMotion.bob(time, 0);
    for (int i=0; i<2; i++) {
      double theta = angle + i*Math.PI;
      double x = Math.cos(theta), z = Math.sin(theta);
      level.addParticle(RED_DUST, pos.getX()+0.5+x*0.20,
          pos.getY()+0.5+bob+0.08*Math.sin(theta*2), pos.getZ()+0.5+z*0.20,
          -z*0.004, 0.001, x*0.004);
      spawnedParticles++;
    }
  }
}
