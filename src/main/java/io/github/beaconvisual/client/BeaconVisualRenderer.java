package io.github.beaconvisual.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.beaconvisual.BeaconVisualBlockEntity;
import io.github.beaconvisual.BeaconVisualMotion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class BeaconVisualRenderer implements BlockEntityRenderer<BeaconVisualBlockEntity> {
  public static final ModelResourceLocation CORE_MODEL = ModelResourceLocation.standalone(
      ResourceLocation.fromNamespaceAndPath("beaconvisual", "block/core"));
  static long renderedFrames;

  public BeaconVisualRenderer(BlockEntityRendererProvider.Context context) {}

  @Override
  public void render(BeaconVisualBlockEntity entity, float partialTick, PoseStack pose,
      MultiBufferSource buffers, int packedLight, int packedOverlay) {
    if (entity.getLevel() == null || entity.isRemoved()) return;
    long time = entity.getLevel().getGameTime();
    Minecraft mc = Minecraft.getInstance();
    var model = mc.getModelManager().getModel(CORE_MODEL);
    pose.pushPose();
    try {
      pose.translate(0.5, 0.5 + BeaconVisualMotion.bob(time, partialTick), 0.5);
      pose.mulPose(Axis.YP.rotationDegrees(BeaconVisualMotion.yaw(time, partialTick)));
      pose.translate(-0.5, -0.5, -0.5);
      mc.getBlockRenderer().getModelRenderer().renderModel(pose.last(),
          buffers.getBuffer(RenderType.cutout()), entity.getBlockState(), model,
          1, 1, 1, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
      renderedFrames++;
    } finally {
      pose.popPose();
    }
  }
}
