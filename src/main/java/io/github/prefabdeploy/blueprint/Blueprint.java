package io.github.prefabdeploy.blueprint;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;

public record Blueprint(
    int width, int height, int depth, List<Voxel> voxels, List<Actor> entities, List<Tick> ticks) {
  public record Voxel(BlockPos pos, BlockState state, CompoundTag nbt) {
    public Voxel {
      pos = pos.immutable();
      nbt = nbt == null ? null : nbt.copy();
    }
  }

  public record Actor(double x, double y, double z, CompoundTag nbt) {
    public Actor {
      if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
        throw new IllegalArgumentException("Invalid entity coordinates");
      nbt = EntityData.normalize(nbt, x, y, z);
    }
  }

  public record Tick(BlockPos pos, String type, boolean fluid, long delay, int priority) {}

  public Blueprint {
    if (width <= 0 || height <= 0 || depth <= 0)
      throw new IllegalArgumentException("Invalid blueprint bounds");
    voxels = List.copyOf(voxels);
    entities = List.copyOf(entities);
    ticks = List.copyOf(ticks);
  }

  public CompoundTag serialize(boolean renderOnly) {
    var out = new CompoundTag();
    out.putInt("x", width);
    out.putInt("y", height);
    out.putInt("z", depth);
    var palette = new LinkedHashMap<BlockState, Integer>();
    var blocks = new ListTag();
    for (var v : voxels) {
      var n = new CompoundTag();
      n.putLong("p", v.pos.asLong());
      n.putInt("s", palette.computeIfAbsent(v.state, ignored -> palette.size()));
      if (v.nbt != null) n.put("n", renderOnly ? renderNbt(v.state, v.nbt) : v.nbt.copy());
      blocks.add(n);
    }
    var states = new ListTag();
    palette.keySet().forEach(s -> states.add(StateCodec.write(s)));
    out.put("palette", states);
    out.put("blocks", blocks);
    var actors = new ListTag();
    for (var e : entities) {
      var n = new CompoundTag();
      n.putDouble("x", e.x);
      n.putDouble("y", e.y);
      n.putDouble("z", e.z);
      n.put("n", renderOnly ? renderEntityNbt(e.nbt) : e.nbt.copy());
      actors.add(n);
    }
    out.put("entities", actors);
    if (!renderOnly) {
      var scheduled = new ListTag();
      for (var t : ticks) {
        var n = new CompoundTag();
        n.putLong("p", t.pos.asLong());
        n.putString("id", t.type);
        n.putBoolean("f", t.fluid);
        n.putLong("d", t.delay);
        n.putInt("q", t.priority);
        scheduled.add(n);
      }
      out.put("ticks", scheduled);
    }
    return out;
  }

  private static CompoundTag renderEntityNbt(CompoundTag source) {
    var n = source.copy();
    for (String key :
        List.of(
            "Items",
            "Inventory",
            "Offers",
            "Brain",
            "Owner",
            "OwnerUUID",
            "Trusted",
            "LoveCause",
            "Command",
            "LastOutput",
            "Leash",
            "leash",
            "LootTable",
            "UUID")) n.remove(key);
    var passengers = n.getList("Passengers", Tag.TAG_COMPOUND);
    for (int i = 0; i < passengers.size(); i++)
      passengers.set(i, renderEntityNbt(passengers.getCompound(i)));
    return n;
  }

  private static CompoundTag renderNbt(BlockState state, CompoundTag source) {
    var out = new CompoundTag();
    for (String key :
        List.of(
            "id",
            "front_text",
            "back_text",
            "patterns",
            "base_color",
            "SkullOwner",
            "profile",
            "color",
            "CustomName",
            "Rot")) if (source.contains(key)) out.put(key, source.get(key).copy());
    for (var adapter : io.github.prefabdeploy.api.PrefabApi.NBT_ADAPTERS)
      if (adapter.accepts(state)) out.merge(adapter.previewData(state, source.copy()));
    return out;
  }

  public static Blueprint deserialize(CompoundTag nbt, int limit) {
    var states = new ArrayList<BlockState>();
    var palette = nbt.getList("palette", Tag.TAG_COMPOUND);
    for (int i = 0; i < palette.size(); i++) states.add(StateCodec.read(palette.getCompound(i)));
    var blocks = nbt.getList("blocks", Tag.TAG_COMPOUND);
    if (blocks.size() > limit) throw new IllegalArgumentException("Too many positions");
    var voxels = new ArrayList<Voxel>();
    for (int i = 0; i < blocks.size(); i++) {
      var n = blocks.getCompound(i);
      voxels.add(
          new Voxel(
              BlockPos.of(n.getLong("p")),
              states.get(n.getInt("s")),
              n.contains("n") ? n.getCompound("n") : null));
    }
    var actors = new ArrayList<Actor>();
    var es = nbt.getList("entities", Tag.TAG_COMPOUND);
    for (int i = 0; i < es.size(); i++) {
      var n = es.getCompound(i);
      actors.add(
          new Actor(n.getDouble("x"), n.getDouble("y"), n.getDouble("z"), n.getCompound("n")));
    }
    var ticks = new ArrayList<Tick>();
    var ts = nbt.getList("ticks", Tag.TAG_COMPOUND);
    for (int i = 0; i < ts.size(); i++) {
      var n = ts.getCompound(i);
      ticks.add(
          new Tick(
              BlockPos.of(n.getLong("p")),
              n.getString("id"),
              n.getBoolean("f"),
              n.getLong("d"),
              n.getInt("q")));
    }
    return new Blueprint(nbt.getInt("x"), nbt.getInt("y"), nbt.getInt("z"), voxels, actors, ticks);
  }
}
