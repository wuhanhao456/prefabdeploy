package io.github.prefabdeploy.server;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public final class RegionLocks {
  private record Key(ResourceKey<Level> dimension, long chunk) {}

  private static final Map<Key, UUID> OWNERS = new HashMap<>();
  private static final ThreadLocal<UUID> WRITER = new ThreadLocal<>();

  public static boolean lock(Level level, Set<Long> chunks, UUID id) {
    for (long c : chunks) {
      var owner = OWNERS.get(new Key(level.dimension(), c));
      if (owner != null && !owner.equals(id)) return false;
    }
    for (long c : chunks) OWNERS.put(new Key(level.dimension(), c), id);
    return true;
  }

  public static boolean locked(Level level, BlockPos pos) {
    return locked(level, new ChunkPos(pos).toLong());
  }

  public static boolean ownedBy(Level level, BlockPos pos, UUID transaction) {
    return transaction != null && transaction.equals(OWNERS.get(new Key(level.dimension(), new ChunkPos(pos).toLong())));
  }

  public static boolean locked(Level level, long chunk) {
    return !level.isClientSide() && OWNERS.containsKey(new Key(level.dimension(), chunk));
  }

  public static boolean denyWrite(Level level, BlockPos pos) {
    var owner = OWNERS.get(new Key(level.dimension(), new ChunkPos(pos).toLong()));
    return !level.isClientSide() && owner != null && !owner.equals(WRITER.get());
  }

  public static boolean internal() {
    return WRITER.get() != null;
  }

  public static boolean tickBlocked(Level level, BlockPos pos) {
    if (locked(level, pos)) return true;
    for (var direction : net.minecraft.core.Direction.values())
      if (locked(level, pos.relative(direction))) return true;
    return false;
  }

  public static boolean menuBlocked(net.minecraft.server.level.ServerPlayer player) {
    if (player.containerMenu == player.inventoryMenu) return false;
    for (var slot : player.containerMenu.slots)
      if (slot.container instanceof net.minecraft.world.level.block.entity.BlockEntity be
          && be.getLevel() != null
          && locked(be.getLevel(), be.getBlockPos())) return true;
    var pos = player.blockPosition();
    for (int dx : new int[] {-8, 0, 8})
      for (int dz : new int[] {-8, 0, 8})
        if (locked(player.level(), pos.offset(dx, 0, dz))) return true;
    return false;
  }

  public static void closeMenus(net.minecraft.server.level.ServerLevel level) {
    for (var player : level.players()) if (menuBlocked(player)) player.closeContainer();
  }

  public static void write(UUID owner, Runnable action) {
    var previous = WRITER.get();
    WRITER.set(owner);
    try {
      action.run();
    } finally {
      if (previous == null) WRITER.remove();
      else WRITER.set(previous);
    }
  }

  public static void release(UUID id) {
    OWNERS.entrySet().removeIf(e -> e.getValue().equals(id));
  }

  public static void clear() {
    OWNERS.clear();
    WRITER.remove();
  }

  private RegionLocks() {}
}
