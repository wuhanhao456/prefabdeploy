package io.github.beaconvisual;

import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** No gameplay data, custom NBT, packets, inventory or server ticking. */
public final class BeaconVisualBlockEntity extends BlockEntity {
  private static Consumer<BeaconVisualBlockEntity> clientTicker = entity -> {};

  public BeaconVisualBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
    super(type, pos, state);
  }

  /** Installed by the physical-client adapter; safe default on dedicated servers. */
  public static void setClientTicker(Consumer<BeaconVisualBlockEntity> ticker) {
    clientTicker = Objects.requireNonNull(ticker);
  }

  static void clientTick(BeaconVisualBlockEntity entity) {
    if (!entity.isRemoved() && entity.getLevel() != null && entity.getLevel().isClientSide) {
      clientTicker.accept(entity);
    }
  }
}
