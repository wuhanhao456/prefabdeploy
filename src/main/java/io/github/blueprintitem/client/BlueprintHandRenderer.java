package io.github.blueprintitem.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only, opt-in first-person presentation for a blueprint item.
 * The hand, tilt, swing and equip transforms follow Minecraft 1.21.1's map renderer.
 * The paper is rendered as a three-dimensional baked model instead of map pixels.
 * No registry changes, Mixin, networking or renderer replacement are required.
 */
@OnlyIn(Dist.CLIENT)
public final class BlueprintHandRenderer {
  private static Predicate<ItemStack> matches = stack -> false;
  private static boolean registered;

  private BlueprintHandRenderer() {}

  /** Call once from client setup (enqueueWork). Repeated calls replace the predicate. */
  public static void register(Predicate<ItemStack> isBlueprint) {
    matches = Objects.requireNonNull(isBlueprint, "isBlueprint");
    if (!registered) {
      NeoForge.EVENT_BUS.addListener(BlueprintHandRenderer::renderHand);
      registered = true;
    }
  }

  private static boolean blueprint(ItemStack stack) {
    return !stack.isEmpty() && matches.test(stack);
  }

  private static void renderHand(RenderHandEvent event) {
    Minecraft mc = Minecraft.getInstance();
    AbstractClientPlayer player = mc.player;
    if (player == null || mc.level == null || player.isScoping()) return;
    // Respect a renderer which has already cancelled this event.
    if (event.isCanceled()) return;
    if (event.getHand() == InteractionHand.OFF_HAND
        && event.getItemStack().isEmpty()
        && blueprint(player.getMainHandItem())) {
      event.setCanceled(true);
      return;
    }
    if (!blueprint(event.getItemStack())) return;
    boolean twoHands = event.getHand() == InteractionHand.MAIN_HAND
        && player.getOffhandItem().isEmpty();
    HumanoidArm arm = event.getHand() == InteractionHand.MAIN_HAND
        ? player.getMainArm() : player.getMainArm().getOpposite();
    PoseStack pose = event.getPoseStack();
    pose.pushPose();
    try {
      if (twoHands) renderTwoHands(mc, player, event);
      else renderOneHand(mc, player, arm, event);
    } finally {
      pose.popPose();
    }
    event.setCanceled(true);
  }

  private static float mapTilt(float pitch) {
    float value = Mth.clamp(1.0F - pitch / 45.0F + 0.1F, 0, 1);
    return -Mth.cos(value * Mth.PI) * 0.5F + 0.5F;
  }

  private static void renderTwoHands(Minecraft mc, AbstractClientPlayer player, RenderHandEvent e) {
    PoseStack pose = e.getPoseStack();
    float swing = e.getSwingProgress();
    float root = Mth.sqrt(swing);
    float y = -0.2F * Mth.sin(swing * Mth.PI);
    float z = -0.4F * Mth.sin(root * Mth.PI);
    pose.translate(0, -y / 2, z);
    float tilt = mapTilt(e.getInterpolatedPitch());
    pose.translate(0, 0.04F - e.getEquipProgress() * 1.2F - tilt * 0.5F, -0.72F);
    pose.mulPose(Axis.XP.rotationDegrees(tilt * -85.0F));
    if (!player.isInvisible()) {
      pose.pushPose();
      pose.mulPose(Axis.YP.rotationDegrees(90));
      mapHand(mc, player, pose, e.getMultiBufferSource(), e.getPackedLight(), HumanoidArm.RIGHT);
      mapHand(mc, player, pose, e.getMultiBufferSource(), e.getPackedLight(), HumanoidArm.LEFT);
      pose.popPose();
    }
    pose.mulPose(Axis.XP.rotationDegrees(Mth.sin(root * Mth.PI) * 20));
    renderPaper(mc, player, e, 0.60F);
  }

  private static void renderOneHand(Minecraft mc, AbstractClientPlayer player, HumanoidArm arm, RenderHandEvent e) {
    PoseStack pose = e.getPoseStack();
    float sign = arm == HumanoidArm.RIGHT ? 1 : -1;
    pose.translate(sign * 0.125F, -0.125F, 0);
    if (!player.isInvisible()) {
      pose.pushPose();
      pose.mulPose(Axis.ZP.rotationDegrees(sign * 10));
      playerArm(mc, player, pose, e.getMultiBufferSource(), e.getPackedLight(), e.getEquipProgress(), e.getSwingProgress(), arm);
      pose.popPose();
    }
    pose.pushPose();
    // A volumetric tower needs more camera clearance than a flat vanilla map.
    pose.translate(sign * 0.25F, -0.08F - e.getEquipProgress() * 1.2F, -1.05F);
    float root = Mth.sqrt(e.getSwingProgress());
    pose.translate(sign * -0.5F * Mth.sin(root * Mth.PI),
        0.4F * Mth.sin(root * Mth.PI * 2) - 0.3F * Mth.sin(root * Mth.PI),
        -0.3F * Mth.sin(e.getSwingProgress() * Mth.PI));
    pose.mulPose(Axis.XP.rotationDegrees(root * -45));
    pose.mulPose(Axis.YP.rotationDegrees(sign * root * -30));
    renderPaper(mc, player, e, 0.42F);
    pose.popPose();
  }

  private static void renderPaper(Minecraft mc, AbstractClientPlayer player, RenderHandEvent e, float scale) {
    PoseStack pose = e.getPoseStack();
    pose.pushPose();
    // The model is authored on X/Z with its top facing +Y. Maps are on X/Y.
    pose.mulPose(Axis.XP.rotationDegrees(90));
    pose.scale(scale, scale, scale);
    // ItemRenderer centers at [8,8,8]; move the paper's top (Y=1.1875) onto the map plane.
    pose.translate(0, (8.0F - 1.1875F) / 16.0F, 0);
    mc.getItemRenderer().renderStatic(player, e.getItemStack(), ItemDisplayContext.NONE, false,
        pose, e.getMultiBufferSource(), mc.level, e.getPackedLight(), OverlayTexture.NO_OVERLAY, player.getId());
    pose.popPose();
  }

  private static PlayerRenderer playerRenderer(Minecraft mc, AbstractClientPlayer player) {
    return (PlayerRenderer) mc.getEntityRenderDispatcher().getRenderer(player);
  }

  private static void hand(Minecraft mc, AbstractClientPlayer player, PoseStack pose, MultiBufferSource buffers, int light, HumanoidArm arm) {
    PlayerRenderer renderer = playerRenderer(mc, player);
    if (arm == HumanoidArm.RIGHT) renderer.renderRightHand(pose, buffers, light, player);
    else renderer.renderLeftHand(pose, buffers, light, player);
  }

  private static void mapHand(Minecraft mc, AbstractClientPlayer player, PoseStack pose, MultiBufferSource buffers, int light, HumanoidArm arm) {
    pose.pushPose();
    float sign = arm == HumanoidArm.RIGHT ? 1 : -1;
    pose.mulPose(Axis.YP.rotationDegrees(92));
    pose.mulPose(Axis.XP.rotationDegrees(45));
    pose.mulPose(Axis.ZP.rotationDegrees(sign * -41));
    pose.translate(sign * 0.3F, -1.1F, 0.45F);
    hand(mc, player, pose, buffers, light, arm);
    pose.popPose();
  }

  private static void playerArm(Minecraft mc, AbstractClientPlayer player, PoseStack pose, MultiBufferSource buffers,
      int light, float equip, float swing, HumanoidArm arm) {
    float sign = arm == HumanoidArm.RIGHT ? 1 : -1;
    float root = Mth.sqrt(swing);
    pose.translate(sign * (-0.3F * Mth.sin(root * Mth.PI) + 0.64000005F),
        0.4F * Mth.sin(root * Mth.PI * 2) - 0.6F - equip * 0.6F,
        -0.4F * Mth.sin(swing * Mth.PI) - 0.71999997F);
    pose.mulPose(Axis.YP.rotationDegrees(sign * 45));
    pose.mulPose(Axis.YP.rotationDegrees(sign * Mth.sin(root * Mth.PI) * 70));
    pose.mulPose(Axis.ZP.rotationDegrees(sign * Mth.sin(swing * swing * Mth.PI) * -20));
    pose.translate(sign * -1, 3.6F, 3.5F);
    pose.mulPose(Axis.ZP.rotationDegrees(sign * 120));
    pose.mulPose(Axis.XP.rotationDegrees(200));
    pose.mulPose(Axis.YP.rotationDegrees(sign * -135));
    pose.translate(sign * 5.6F, 0, 0);
    hand(mc, player, pose, buffers, light, arm);
  }
}
