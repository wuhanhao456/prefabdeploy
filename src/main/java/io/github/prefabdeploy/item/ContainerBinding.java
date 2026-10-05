package io.github.prefabdeploy.item;

import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.UiText;
import io.github.prefabdeploy.compat.BoundContainers;
import io.github.prefabdeploy.server.Sessions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** The binding travels with this tool. Client data is display-only; funding checks the world again. */
public final class ContainerBinding {
  public static final String TAG = "prefabdeploy:container_binding";

  public static CompoundTag read(ItemStack tool) {
    if (!tool.is(PrefabDeploy.TOOL.get())) return new CompoundTag();
    var data = tool.get(DataComponents.CUSTOM_DATA);
    if (data == null) return new CompoundTag();
    var binding = data.copyTag().getCompound(TAG);
    return binding.getInt("version") == 1 ? binding.copy() : new CompoundTag();
  }

  public static void write(ItemStack tool, CompoundTag binding) {
    CustomData.update(DataComponents.CUSTOM_DATA, tool, data -> {
      if (binding.isEmpty()) data.remove(TAG);
      else data.put(TAG, binding.copy());
    });
  }

  public static void bind(ServerPlayer player, UseOnContext context) {
    try {
      if (Sessions.active(player)) { Sessions.requestCancel(player); return; }
      if (player.isSpectator() || !player.getMainHandItem().is(PrefabDeploy.TOOL.get())) return;
      var eye = player.getEyePosition();
      var hit = player.level().clip(new ClipContext(eye,
          eye.add(player.getLookAngle().scale(player.blockInteractionRange())),
          ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
      if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(context.getClickedPos()))
        throw UiText.failure(UiText.tr("binding.out_of_reach", "The container is outside interaction reach"));
      var binding = BoundContainers.bind(player, hit);
      write(context.getItemInHand(), binding);
      player.displayClientMessage(UiText.tr("binding.bound", "Material container bound: %s (%s)",
          hit.getBlockPos().toShortString(), player.level().dimension().location().toString()).component(), true);
    } catch (Exception ex) {
      player.displayClientMessage(UiText.fromThrowable(ex).component(), true);
    }
  }

  public static void unbind(ServerPlayer player) {
    if (Sessions.active(player)) { Sessions.requestCancel(player); return; }
    // A failed block use must never fall through into clearing an existing binding.
    var eye = player.getEyePosition();
    var hit = player.level().clip(new ClipContext(eye,
        eye.add(player.getLookAngle().scale(player.blockInteractionRange())),
        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    if (hit.getType() == HitResult.Type.BLOCK || player.isSpectator()) return;
    write(player.getMainHandItem(), new CompoundTag());
    player.displayClientMessage(UiText.tr("binding.cleared", "Material container binding cleared").component(), true);
  }

  private ContainerBinding() {}
}
