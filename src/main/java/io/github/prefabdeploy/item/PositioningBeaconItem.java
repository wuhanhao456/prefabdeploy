package io.github.prefabdeploy.item;

import io.github.prefabdeploy.*;
import io.github.prefabdeploy.compat.OptionalMods;
import io.github.prefabdeploy.network.Network;
import io.github.prefabdeploy.server.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.HitResult;

public final class PositioningBeaconItem extends BlockItem {
  public PositioningBeaconItem(Block b, Properties p) {
    super(b, p);
  }

  @Override
  public InteractionResult place(BlockPlaceContext context) {
    if (cancel(context.getLevel(), context.getPlayer(), context.getHand()))
      return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    if (context.getPlayer() instanceof ServerPlayer p) {
      String error = check(p, context.getClickedPos());
      if (!error.isEmpty()) {
        p.displayClientMessage(UiText.fromLegacy(error).component(), true);
        return InteractionResult.FAIL;
      }
    }
    var stack = context.getItemInHand();
    int before = stack.getCount();
    var result = super.place(context);
    if (result.consumesAction() && context.getPlayer() instanceof ServerPlayer p)
      Sessions.markerPlaced(p, context.getClickedPos(), Math.max(0, before - stack.getCount()));
    return result;
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    var stack = player.getItemInHand(hand);
    if (cancel(level, player, hand))
      return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(stack);
    var eye = player.getEyePosition();
    var hit =
        level.clip(
            new ClipContext(
                eye,
                eye.add(player.getLookAngle().scale(player.blockInteractionRange())),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));
    // A failed ordinary block placement must not fall through into floating placement.
    if (hit.getType() == HitResult.Type.BLOCK) return InteractionResultHolder.pass(stack);
    if (player instanceof ServerPlayer p) {
      var pos =
          BlockPos.containing(
              p.getEyePosition().add(p.getLookAngle().scale(Sessions.floatDistance(p))));
      String error = check(p, pos);
      if (error.isEmpty() && !level.getBlockState(pos).isAir())
        error = "Floating beacon needs an empty position";
      if (!error.isEmpty()) {
        p.displayClientMessage(UiText.fromLegacy(error).component(), true);
        return InteractionResultHolder.fail(stack);
      }
      if (!level.setBlock(pos, getBlock().defaultBlockState(), 3))
        return InteractionResultHolder.fail(stack);
      int spent = p.getAbilities().instabuild ? 0 : 1;
      if (spent > 0) stack.shrink(spent);
      Sessions.markerPlaced(p, pos, spent);
    }
    return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
  }

  private boolean cancel(Level level, Player player, InteractionHand hand) {
    if (player == null || hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown())
      return false;
    if (level.isClientSide() && Network.PLACEMENT.getAsBoolean()) {
      Network.TOOL.accept("cancel");
      return true;
    }
    if (player instanceof ServerPlayer p && Sessions.active(p)) {
      Sessions.requestCancel(p);
      return true;
    }
    return false;
  }

  private String check(ServerPlayer p, BlockPos pos) {
    if (!p.level().hasChunkAt(pos)
        || !p.level().getWorldBorder().isWithinBounds(pos)
        || pos.getY() < p.level().getMinBuildHeight()
        || pos.getY() >= p.level().getMaxBuildHeight())
      return "Beacon position is not loaded or outside world limits";
    if (RegionLocks.locked(p.level(), pos)) return "Construction area is locked";
    if (p.server.isUnderSpawnProtection(p.serverLevel(), pos, p)) return "Spawn protection";
    String permission = OptionalMods.protection(p, pos);
    return permission.isEmpty() ? Sessions.canPlaceMarker(p, pos) : permission;
  }
}
