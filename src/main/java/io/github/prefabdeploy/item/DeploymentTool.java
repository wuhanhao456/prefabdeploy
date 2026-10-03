package io.github.prefabdeploy.item;

import io.github.prefabdeploy.network.Network;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class DeploymentTool extends Item {
  public DeploymentTool(Properties p) {
    super(p);
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    if (level.isClientSide() && hand == InteractionHand.MAIN_HAND) Network.TOOL.accept("use");
    return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    if (context.getLevel().isClientSide() && context.getHand() == InteractionHand.MAIN_HAND)
      Network.TOOL.accept("use");
    return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
  }
}
