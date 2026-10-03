package io.github.beaconvisual;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Ordinary full-cell selection/collision, with an empty visual block entity. */
public final class BeaconVisualBlock extends Block implements EntityBlock {
  private final Supplier<BlockEntityType<BeaconVisualBlockEntity>> visualType;

  public BeaconVisualBlock(Properties properties, Supplier<BlockEntityType<BeaconVisualBlockEntity>> visualType) {
    super(properties);
    this.visualType = Objects.requireNonNull(visualType);
  }

  @Override
  public BeaconVisualBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new BeaconVisualBlockEntity(visualType.get(), pos, state);
  }

  @Override
  public <T extends net.minecraft.world.level.block.entity.BlockEntity> BlockEntityTicker<T> getTicker(
      Level level, BlockState state, BlockEntityType<T> type) {
    // The server has no animation ticker, particle state or client class dependency.
    if (!level.isClientSide || type != visualType.get()) return null;
    return (l, p, s, entity) -> BeaconVisualBlockEntity.clientTick((BeaconVisualBlockEntity) entity);
  }
}
