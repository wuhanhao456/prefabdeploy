package io.github.prefabdeploy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import io.github.prefabdeploy.blueprint.Blueprint;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

public final class PreviewMesh implements AutoCloseable {
  private record Part(AABB bounds, RenderType type, VertexBuffer buffer) {}

  private static final class Batch implements AutoCloseable {
    final ByteBufferBuilder memory = new ByteBufferBuilder(128 * 1024);
    final BufferBuilder builder;

    Batch(RenderType type) {
      builder = new BufferBuilder(memory, type.mode(), type.format());
    }

    @Override
    public void close() {
      memory.close();
    }
  }

  private final Blueprint blueprint;
  private final View view;
  private final Iterator<Map.Entry<SectionPos, List<Blueprint.Voxel>>> sections;
  private final List<Part> parts = new ArrayList<>();
  private Map.Entry<SectionPos, List<Blueprint.Voxel>> building;
  private final Map<RenderType, Batch> batches = new LinkedHashMap<>();
  private BufferBuilder builder;
  private int index, processed, actorIndex;
  private final RandomSource random = RandomSource.create();

  public PreviewMesh(Blueprint bp) {
    blueprint = bp;
    view = new View(bp);
    var groups =
        new TreeMap<SectionPos, List<Blueprint.Voxel>>(
            Comparator.comparingLong(SectionPos::asLong));
    for (var v : bp.voxels())
      if (!v.state().isAir())
        groups.computeIfAbsent(SectionPos.of(v.pos()), ignored -> new ArrayList<>()).add(v);
    sections = groups.entrySet().iterator();
  }

  public Blueprint blueprint() {
    return blueprint;
  }

  public boolean ready() {
    return building == null && !sections.hasNext() && actorIndex >= blueprint.entities().size();
  }

  public int processed() {
    return processed;
  }

  public void build(long deadline) {
    var mc = Minecraft.getInstance();
    int operations = 0;
    while (System.nanoTime() < deadline && operations++ < 512) {
      if (building == null) {
        if (!sections.hasNext()) {
          if (actorIndex >= blueprint.entities().size()) return;
          buildActor(blueprint.entities().get(actorIndex++));
          continue;
        }
        building = sections.next();
        builder = batch(GhostType.TYPE);
        index = 0;
      }
      if (index < building.getValue().size()) {
        var v = building.getValue().get(index++);
        processed++;
        var pos = v.pos();
        var state = v.state();
        var pose = new PoseStack();
        pose.translate(pos.getX(), pos.getY(), pos.getZ());
        if (state.getRenderShape() == RenderShape.MODEL) {
          var model = mc.getBlockRenderer().getBlockModel(state);
          var data = model.getModelData(view, pos, state, ModelData.EMPTY);
          for (Direction face : Direction.values())
            if (Block.shouldRenderFace(state, view, pos, face, pos.relative(face)))
              quads(model.getQuads(state, face, random, data, null), state, pos, pose);
          quads(model.getQuads(state, null, random, data, null), state, pos, pose);
        }
        try {
          var be = view.getBlockEntity(pos);
          if (be != null)
            mc.getBlockEntityRenderDispatcher()
                .renderItem(
                    be, pose, this::batch, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
          else if (state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED)
            mc.getBlockRenderer()
                .renderSingleBlock(
                    state, pose, this::batch, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        } catch (RuntimeException ex) {
          io.github.prefabdeploy.PrefabDeploy.LOGGER.warn(
              "Special preview renderer failed for {}", state, ex);
        }
        if (!state.getFluidState().isEmpty()) {
          var local = pos.subtract(building.getKey().origin());
          var fluidPose = new PoseStack();
          fluidPose.translate(
              building.getKey().origin().getX(),
              building.getKey().origin().getY(),
              building.getKey().origin().getZ());
          mc.getBlockRenderer()
              .renderLiquid(
                  pos,
                  view,
                  new OffsetConsumer(builder, fluidPose.last()),
                  state,
                  state.getFluidState());
        }
        continue;
      }
      var p = building.getKey().origin();
      upload(
          new AABB(
              p.getX() - 2,
              p.getY() - 2,
              p.getZ() - 2,
              p.getX() + 18,
              p.getY() + 18,
              p.getZ() + 18));
      builder = null;
      building = null;
    }
  }

  private BufferBuilder batch(RenderType type) {
    return batches.computeIfAbsent(type, Batch::new).builder;
  }

  private void upload(AABB bounds) {
    try {
      for (var entry : batches.entrySet()) {
        var mesh = entry.getValue().builder.build();
        if (mesh != null) {
          var vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
          vbo.bind();
          vbo.upload(mesh);
          VertexBuffer.unbind();
          parts.add(new Part(bounds, entry.getKey(), vbo));
        }
      }
    } finally {
      batches.values().forEach(Batch::close);
      batches.clear();
    }
  }

  private void buildActor(Blueprint.Actor actor) {
    var mc = Minecraft.getInstance();
    try {
      var entity =
          net.minecraft.world.entity.EntityType.loadEntityRecursive(
              actor.nbt().copy(), mc.level, e -> e);
      if (entity != null) {
        entity
            .getSelfAndPassengers()
            .forEach(
                e -> {
                  var pose = new PoseStack();
                  pose.translate(e.getX(), e.getY(), e.getZ());
                  mc.getEntityRenderDispatcher()
                      .getRenderer(e)
                      .render(e, e.getYRot(), 0, pose, this::batch, LightTexture.FULL_BRIGHT);
                });
        upload(
            new AABB(
                actor.x() - 4,
                actor.y() - 4,
                actor.z() - 4,
                actor.x() + 4,
                actor.y() + 8,
                actor.z() + 4));
      }
    } catch (RuntimeException ex) {
      batches.values().forEach(Batch::close);
      batches.clear();
      io.github.prefabdeploy.PrefabDeploy.LOGGER.warn("Entity preview renderer failed", ex);
    }
  }

  private void quads(
      List<net.minecraft.client.renderer.block.model.BakedQuad> quads,
      BlockState state,
      BlockPos pos,
      PoseStack pose) {
    var mc = Minecraft.getInstance();
    for (var quad : quads) {
      int color =
          quad.isTinted()
              ? mc.getBlockColors().getColor(state, view, pos, quad.getTintIndex())
              : 0xffffff;
      if (color == -1) color = 0xffffff;
      float shade = view.getShade(quad.getDirection(), quad.isShade());
      builder.putBulkData(
          pose.last(),
          quad,
          ((color >> 16) & 255) / 255f * shade,
          ((color >> 8) & 255) / 255f * shade,
          (color & 255) / 255f * shade,
          1,
          LightTexture.FULL_BRIGHT,
          OverlayTexture.NO_OVERLAY);
    }
  }

  public void draw(
      PoseStack pose,
      Matrix4f projection,
      float alpha,
      java.util.function.Predicate<AABB> visible) {
    int query = PreviewProfile.begin();
    for (var part : parts)
      if (visible.test(part.bounds)) {
        part.type.setupRenderState();
        RenderSystem.setShaderColor(1, 1, 1, alpha);
        if (alpha < 1) {
          RenderSystem.enableBlend();
          RenderSystem.defaultBlendFunc();
        }
        try {
          part.buffer.bind();
          part.buffer.drawWithShader(
              new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose()),
              projection,
              RenderSystem.getShader());
        } finally {
          VertexBuffer.unbind();
          RenderSystem.setShaderColor(1, 1, 1, 1);
          part.type.clearRenderState();
        }
      }
    PreviewProfile.end(query);
  }

  @Override
  public void close() {
    for (var part : parts) part.buffer.close();
    parts.clear();
    batches.values().forEach(Batch::close);
    batches.clear();
  }

  private static final class GhostType extends RenderType {
    static final RenderType TYPE =
        create(
            "prefabdeploy_ghost",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            256 * 1024,
            false,
            true,
            CompositeState.builder()
                .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
                .setTextureState(new TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, true))
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setLightmapState(LIGHTMAP)
                .setCullState(NO_CULL)
                .setOutputState(MAIN_TARGET)
                .setWriteMaskState(COLOR_DEPTH_WRITE)
                .createCompositeState(false));

    private GhostType() {
      super(
          "unused",
          DefaultVertexFormat.BLOCK,
          VertexFormat.Mode.QUADS,
          256,
          false,
          false,
          () -> {},
          () -> {});
    }
  }

  private static final class View implements BlockAndTintGetter {
    private final Map<BlockPos, Blueprint.Voxel> blocks = new HashMap<>();
    private final Map<BlockPos, BlockEntity> entities = new HashMap<>();
    private final Blueprint bp;

    View(Blueprint bp) {
      this.bp = bp;
      for (var v : bp.voxels()) blocks.put(v.pos(), v);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
      var v = blocks.get(pos);
      return v == null ? Blocks.AIR.defaultBlockState() : v.state();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
      if (entities.containsKey(pos)) return entities.get(pos);
      var v = blocks.get(pos);
      if (v == null || v.nbt() == null) return null;
      var be =
          BlockEntity.loadStatic(
              pos, v.state(), v.nbt(), Minecraft.getInstance().level.registryAccess());
      if (be != null) be.setLevel(Minecraft.getInstance().level);
      entities.put(pos.immutable(), be);
      return be;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
      return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
      return bp.height();
    }

    @Override
    public int getMinBuildHeight() {
      return 0;
    }

    @Override
    public float getShade(Direction face, boolean shade) {
      return !shade
          ? 1
          : switch (face) {
            case DOWN -> 0.5f;
            case UP -> 1;
            case NORTH, SOUTH -> 0.8f;
            case EAST, WEST -> 0.6f;
          };
    }

    @Override
    public LevelLightEngine getLightEngine() {
      return Minecraft.getInstance().level.getLightEngine();
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
      return 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int reduction) {
      return 15;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
      var mc = Minecraft.getInstance();
      return mc.level.getBlockTint(mc.player.blockPosition(), resolver);
    }
  }

  private record OffsetConsumer(VertexConsumer delegate, PoseStack.Pose pose)
      implements VertexConsumer {
    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
      delegate.addVertex(pose, x, y, z);
      return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
      delegate.setColor(r, g, b, a);
      return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
      delegate.setUv(u, v);
      return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
      delegate.setUv1(u, v);
      return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
      delegate.setUv2(u, v);
      return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
      delegate.setNormal(pose, x, y, z);
      return this;
    }
  }
}
