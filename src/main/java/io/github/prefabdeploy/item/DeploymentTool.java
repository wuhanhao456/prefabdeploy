package io.github.prefabdeploy.item;

import io.github.prefabdeploy.network.Network;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
    if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(player.getItemInHand(hand));
    if (player.isShiftKeyDown()) {
      if (player instanceof ServerPlayer p) ContainerBinding.unbind(p);
      return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }
    if (level.isClientSide() && hand == InteractionHand.MAIN_HAND) Network.TOOL.accept("use");
    return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    if (context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
    if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
      if (context.getPlayer() instanceof ServerPlayer p) ContainerBinding.bind(p, context);
      return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }
    if (context.getLevel().isClientSide() && context.getHand() == InteractionHand.MAIN_HAND)
      Network.TOOL.accept("use");
    return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
  }

  @Override
  public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
    lines.add(Component.translatable("prefabdeploy.binding.hint").withStyle(ChatFormatting.GRAY));
    var binding = ContainerBinding.read(stack);
    if (!binding.isEmpty()) {
      var pos = BlockPos.of(binding.getLong("pos"));
      lines.add(Component.translatable("prefabdeploy.binding.location", pos.getX(), pos.getY(), pos.getZ(),
          binding.getString("dimension")).withStyle(ChatFormatting.GRAY));
      lines.add(Component.translatable("prefabdeploy.binding.clear_hint").withStyle(ChatFormatting.GRAY));
    }
  }
}
