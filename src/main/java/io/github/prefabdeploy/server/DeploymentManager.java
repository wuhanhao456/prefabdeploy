package io.github.prefabdeploy.server;

import io.github.prefabdeploy.*;
import io.github.prefabdeploy.blueprint.*;
import io.github.prefabdeploy.compat.*;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.library.*;
import io.github.prefabdeploy.mixin.*;
import io.github.prefabdeploy.mixin.ServerLevelAccessor;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.ticks.*;

public final class DeploymentManager {
  private static final ExecutorService IO =
      Executors.newFixedThreadPool(
          2,
          r -> {
            var t = new Thread(r, "prefabdeploy-io");
            t.setDaemon(true);
            return t;
          });
  private static final TicketType<UUID> TICKET =
      TicketType.create("prefabdeploy", Comparator.comparing(UUID::toString));
  private static final Map<UUID, Job> JOBS = new LinkedHashMap<>();
  private static final Map<UUID, Boolean> OUTCOMES = new LinkedHashMap<>();
  private static MinecraftServer server;
  private static int cursor;
  private static final ArrayDeque<Long> SAMPLES = new ArrayDeque<>();
  private static int peakJobs, maxWork;

  public enum Phase {
    PREPARE,
    WAIT_CHUNK,
    VALIDATE,
    BASESAVE,
    JOURNAL,
    RESERVE,
    BLOCKS,
    NBT,
    ENTITIES,
    TICKS,
    SAVE,
    FLUSH,
    COMMIT,
    COMPLETE,
    ROLLBACK_ENTITIES,
    ROLLBACK_BLOCKS,
    ROLLBACK_NBT,
    ROLLBACK_TICKS,
    REFUND,
    HELD
  }

  public static void start(MinecraftServer s) {
    server = s;
    JOBS.clear();
    RegionLocks.clear();
    Costs.clearPins();
    SAMPLES.clear();
    Path directory = directory();
    try {
      Files.createDirectories(directory);
      try (var paths = Files.list(directory)) {
        for (var path : paths.filter(p -> p.toString().endsWith(".journal")).toList()) {
          byte[] bytes = AtomicFile.read(path, 256 * 1024 * 1024);
          CompoundTag n;
          try (var in = new ByteArrayInputStream(bytes)) {
            n = NbtIo.readCompressed(in, NbtAccounter.create(256L * 1024 * 1024));
          }
          if (n.getString("durable").equals("DONE")) continue;
          var j = Job.restore(n);
          JOBS.put(j.id, j);
          if (j.level != null && !RegionLocks.lock(j.level, j.chunks, j.id))
            throw new IOException("Overlapping recovery journals");
        }
      }
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Prefab recovery journal could not be loaded; restore the journal before starting this"
              + " world",
          ex);
    }
  }

  public static void stop() {
    for (var j : JOBS.values())
      if (j.pending != null)
        try {
          j.pending.join();
        } catch (Exception ex) {
          PrefabDeploy.LOGGER.error("Pending prefab persistence failed during shutdown", ex);
        }
    JOBS.clear();
    RegionLocks.clear();
    server = null;
    Costs.clearPins();
  }

  public static Optional<Boolean> outcome(UUID id) {
    return Optional.ofNullable(OUTCOMES.get(id));
  }

  public static boolean busy(UUID player) {
    return JOBS.values().stream().anyMatch(j -> j.owner.equals(player) && j.phase != Phase.HELD);
  }

  public static UUID submit(
      ServerPlayer p,
      Prefab f,
      GridTransform transform,
      List<BlockPos> markers,
      boolean validation) {
    if (busy(p.getUUID())) throw new IllegalStateException("A prefab task is already active");
    Rules.allowedResult(p, f, new BlockPos(transform.x(), transform.y(), transform.z())).require();
    var j = new Job(p, f, transform, markers, validation);
    JOBS.put(j.id, j);
    return j.id;
  }

  public static UUID submit(
      ServerPlayer p,
      Prefab f,
      GridTransform transform,
      List<BlockPos> markers,
      boolean validation,
      int beaconRefundCount) {
    if (beaconRefundCount < 0 || beaconRefundCount > markers.size())
      throw new IllegalArgumentException("Invalid beacon refund count");
    UUID id = submit(p, f, transform, markers, validation);
    JOBS.get(id).beaconRefundCount = beaconRefundCount;
    return id;
  }

  public static void tick() {
    if (server == null) return;
    long start = System.nanoTime(),
        deadline = start + (long) (Config.TICK_BUDGET_MS.get() * 1_000_000);
    int work = 0;
    var jobs = new ArrayList<>(JOBS.values());
    peakJobs = Math.max(peakJobs, jobs.size());
    if (jobs.isEmpty()) {
      boolean updates = Sessions.hasUpdates();
      Sessions.pumpUpdates(deadline, Config.MAX_OPERATIONS.get());
      if (updates) {
        SAMPLES.addLast(System.nanoTime() - start);
        if (SAMPLES.size() > 120000) SAMPLES.removeFirst();
      }
      return;
    }
    var waiting = new HashSet<UUID>();
    boolean progressed = false;
    for (int examined = 0;
        work < Config.MAX_OPERATIONS.get()
            && System.nanoTime() < deadline
            && examined < jobs.size() * Config.MAX_OPERATIONS.get();
        examined++) {
      var j = jobs.get(Math.floorMod(cursor++, jobs.size()));
      if (waiting.contains(j.id)) continue;
      if (j.phase == Phase.HELD
          || j.phase == Phase.COMPLETE
          || j.phase.name().equals(System.getProperty("prefabdeploy.testHold." + j.id, ""))) {
        waiting.add(j.id);
        if (waiting.size() == jobs.size()) break;
        continue;
      }
      var previousPhase = j.phase;
      int previousIndex = j.index;
      var previousPending = j.pending;
      try {
        j.step();
      } catch (Exception ex) {
        j.fail(ex);
      }
      work++;
      if (previousPhase != j.phase || previousIndex != j.index || previousPending != j.pending)
        progressed = true;
      else waiting.add(j.id);
      if (waiting.size() == jobs.size()) break;
    }
    JOBS.values()
        .removeIf(
            j -> {
              if (j.phase != Phase.COMPLETE) return false;
              Costs.unpin(j.id);
              if (!j.dry) OUTCOMES.put(j.id, j.committed);
              while (OUTCOMES.size() > 1024) OUTCOMES.remove(OUTCOMES.keySet().iterator().next());
              return true;
            });
    Sessions.pumpUpdates(deadline, Config.MAX_OPERATIONS.get() - work);
    maxWork = Math.max(maxWork, work);
    if (progressed) {
      SAMPLES.addLast(System.nanoTime() - start);
      if (SAMPLES.size() > 120000) SAMPLES.removeFirst();
    }
  }

  public static List<String> status() {
    var result = new ArrayList<String>();
    for (var j : JOBS.values())
      result.add(
          j.id
              + " "
              + j.phase
              + " "
              + j.index
              + "/"
              + j.prefab.blueprint().voxels().size()
              + " "
              + j.error);
    return result;
  }

  public static String metrics() {
    if (SAMPLES.isEmpty()) return "No deployment samples";
    var sorted = SAMPLES.stream().mapToLong(Long::longValue).sorted().toArray();
    return "scheduler p95="
        + String.format(
            Locale.ROOT,
            "%.3f ms",
            sorted[Math.min(sorted.length - 1, (int) (sorted.length * .95))] / 1e6)
        + ", samples="
        + sorted.length;
  }

  public static void resetMetrics() {
    SAMPLES.clear();
    peakJobs = 0;
    maxWork = 0;
  }

  public static com.google.gson.JsonObject metricData() {
    var n = new com.google.gson.JsonObject();
    var a = SAMPLES.stream().mapToLong(Long::longValue).sorted().toArray();
    n.addProperty("samples", a.length);
    n.addProperty("peak_active_jobs", peakJobs);
    n.addProperty("max_job_operations_per_tick", maxWork);
    if (a.length > 0) {
      n.addProperty("p50_ms", a[a.length / 2] / 1e6);
      n.addProperty("p95_ms", a[Math.min(a.length - 1, (int) (a.length * .95))] / 1e6);
      n.addProperty("max_ms", a[a.length - 1] / 1e6);
    }
    n.addProperty(
        "heap_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
    return n;
  }

  public static void attachFakePlayerForTesting(UUID id, ServerPlayer p) {
    if (!(p instanceof net.neoforged.neoforge.common.util.FakePlayer))
      throw new IllegalArgumentException("Only a GameTest fake player is allowed");
    var j = JOBS.get(id);
    if (j == null || !j.owner.equals(p.getUUID()))
      throw new IllegalArgumentException("Recovery owner mismatch");
    j.testPlayer = p;
  }

  public static CompletableFuture<CompoundTag> audit(UUID id) {
    var path = directory().resolve(id + ".journal");
    return CompletableFuture.supplyAsync(
        () -> {
          try (var in = new ByteArrayInputStream(AtomicFile.read(path, 256 * 1024 * 1024))) {
            return NbtIo.readCompressed(in, NbtAccounter.create(256L * 1024 * 1024));
          } catch (IOException ex) {
            throw new CompletionException(ex);
          }
        },
        IO);
  }

  public static void retry(UUID id) {
    var j = JOBS.get(id);
    if (j == null || j.phase != Phase.HELD) throw new IllegalStateException("No held task");
    j.phase = j.restored ? Phase.REFUND : Phase.PREPARE;
    j.index = 0;
    j.recovering = !j.committed && !j.restored;
  }

  public static void cancelValidation(UUID owner) {
    for (var j : JOBS.values())
      if (j.owner.equals(owner) && j.dry) {
        j.release();
        j.phase = Phase.COMPLETE;
      }
  }

  private static Path directory() {
    return server.getWorldPath(LevelResource.ROOT).resolve("prefabdeploy/transactions");
  }

  private static String failure(Exception ex) {
    return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
  }

  private static final class Job {
    final UUID id, owner;
    final Prefab prefab;
    final GridTransform transform;
    final List<BlockPos> markers;
    final ResourceKey<Level> dimension;
    final Set<Long> chunks = new LinkedHashSet<>();
    final List<Long> chunkList;
    final List<CompoundTag> snapshots = new ArrayList<>(),
        oldTicks = new ArrayList<>(),
        actorNbt = new ArrayList<>(),
        actorChecks = new ArrayList<>();
    final List<UUID> actorIds = new ArrayList<>();
    final List<CompletableFuture<?>> saves = new ArrayList<>();
    ServerLevel level;
    Phase phase = Phase.PREPARE;
    int index;
    int beaconRefundCount;
    boolean dry, recovering, prepared, committed, restored, locks, baseline;
    String error = "";
    io.github.prefabdeploy.UiText errorDisplay = io.github.prefabdeploy.UiText.literal("");
    CompoundTag price = new CompoundTag();
    CompletableFuture<?> pending;
    CompletableFuture<Optional<CompoundTag>> chunkDisk;
    CompletableFuture<ChunkResult<ChunkAccess>> chunkLoad;
    ServerPlayer testPlayer;
    long snapshotBytes;

    Job(ServerPlayer p, Prefab f, GridTransform t, List<BlockPos> markers, boolean dry) {
      id = UUID.randomUUID();
      owner = p.getUUID();
      prefab = f;
      transform = t;
      this.markers = List.copyOf(markers);
      beaconRefundCount = markers.size();
      this.dry = dry;
      dimension = p.level().dimension();
      level = p.serverLevel();
      if (p instanceof net.neoforged.neoforge.common.util.FakePlayer) testPlayer = p;
      for (var v : f.blueprint().voxels())
        chunks.add(new ChunkPos(NbtTransforms.pos(t, v.pos())).toLong());
      for (var pos : markers) chunks.add(new ChunkPos(pos).toLong());
      var ids = new HashMap<UUID, UUID>();
      for (var e : f.blueprint().entities()) collectIds(e.nbt(), ids);
      for (var e : f.blueprint().entities()) {
        UUID uuid = e.nbt().hasUUID("UUID") ? ids.get(e.nbt().getUUID("UUID")) : UUID.randomUUID();
        var n = NbtTransforms.entity(e, t, uuid, ids, id);
        actorNbt.add(n);
        collectActorIds(n, actorIds);
        collectChecks(n);
      }
      for (var tick : f.blueprint().ticks())
        chunks.add(new ChunkPos(NbtTransforms.pos(t, tick.pos())).toLong());
      if (chunks.size() > Config.MAX_CHUNKS.get())
        throw new IllegalArgumentException("Affected chunk limit exceeded");
      chunkList = new ArrayList<>(chunks);
    }

    private Job(
        UUID id,
        UUID owner,
        Prefab f,
        GridTransform t,
        List<BlockPos> markers,
        ResourceKey<Level> dimension,
        Set<Long> chunks) {
      this.id = id;
      this.owner = owner;
      prefab = f;
      transform = t;
      this.markers = markers;
      beaconRefundCount = markers.size();
      this.dimension = dimension;
      this.chunks.addAll(chunks);
      chunkList = new ArrayList<>(chunks);
      level = server.getLevel(dimension);
    }

    static void collectIds(CompoundTag n, Map<UUID, UUID> ids) {
      if (n.hasUUID("UUID")) ids.put(n.getUUID("UUID"), UUID.randomUUID());
      var list = n.getList("Passengers", Tag.TAG_COMPOUND);
      for (int i = 0; i < list.size(); i++) collectIds(list.getCompound(i), ids);
    }

    static void collectActorIds(CompoundTag n, List<UUID> ids) {
      if (n.hasUUID("UUID")) ids.add(n.getUUID("UUID"));
      var list = n.getList("Passengers", Tag.TAG_COMPOUND);
      for (int i = 0; i < list.size(); i++) collectActorIds(list.getCompound(i), ids);
    }

    void collectChecks(CompoundTag n) {
      var pos = n.getList("Pos", Tag.TAG_DOUBLE);
      if (pos.size() != 3) throw new IllegalArgumentException("Missing passenger position");
      actorChecks.add(n);
      chunks.add(
          new ChunkPos(BlockPos.containing(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)))
              .toLong());
      var list = n.getList("Passengers", Tag.TAG_COMPOUND);
      for (int i = 0; i < list.size(); i++) collectChecks(list.getCompound(i));
    }

    ServerPlayer player() {
      var player = server.getPlayerList().getPlayer(owner);
      return player == null ? testPlayer : player;
    }

    void next(Phase next) {
      phase = next;
      index = 0;
      pending = null;
    }

    void step() throws Exception {
      if (level == null) throw new IllegalStateException("Deployment dimension is unavailable");
      switch (phase) {
        case PREPARE -> {
          if (!dry && !locks) {
            if (!RegionLocks.lock(level, chunks, id))
              throw new IllegalStateException("Another task has locked these chunks");
            locks = true;
            RegionLocks.closeMenus(level);
          }
          if (index >= chunkList.size()) {
            if (recovering) {
              next(Phase.ROLLBACK_ENTITIES);
              return;
            }
            if (committed) {
              next(Phase.COMMIT);
              return;
            }
            if (!dry && !locks) {
              if (!RegionLocks.lock(level, chunks, id))
                throw new IllegalStateException("Another task has locked these chunks");
              locks = true;
            }
            var p = player();
            if (p == null) throw new IllegalStateException("Player disconnected");
            Rules.allowedResult(
                    p, prefab, new BlockPos(transform.x(), transform.y(), transform.z()))
                .require();
            price = Costs.quote(p, prefab);
            if (!dry) Costs.pin(id, price);
            next(Phase.VALIDATE);
            return;
          }
          var cp = new ChunkPos(chunkList.get(index));
          if (level.getChunkSource().getChunkNow(cp.x, cp.z) != null) {
            level.getChunkSource().addRegionTicket(TICKET, cp, 2, id);
            if (!entitiesLoaded(cp)) return;
            captureTicks(cp);
            index++;
            return;
          }
          chunkDisk = level.getChunkSource().chunkMap.read(cp);
          nextWait();
        }
        case WAIT_CHUNK -> {
          var cp = new ChunkPos(chunkList.get(index));
          if (chunkLoad == null) {
            if (!chunkDisk.isDone()) return;
            var data = chunkDisk.join();
            if (data.isEmpty() || !data.get().getString("Status").equals("minecraft:full"))
              throw new IllegalStateException("Explore chunk before deployment: " + cp);
            level.getChunkSource().addRegionTicket(TICKET, cp, 2, id);
            chunkLoad = level.getChunkSource().getChunkFuture(cp.x, cp.z, ChunkStatus.FULL, true);
            return;
          }
          if (!chunkLoad.isDone()) return;
          chunkLoad
              .join()
              .orElseThrow(() -> new IllegalStateException("Chunk could not be loaded"));
          if (!entitiesLoaded(cp)) return;
          captureTicks(cp);
          chunkLoad = null;
          chunkDisk = null;
          phase = Phase.PREPARE;
          index++;
        }
        case VALIDATE -> {
          var p = player();
          if (p == null) throw new IllegalStateException("Player disconnected");
          var bp = prefab.blueprint();
          if (index < bp.voxels().size()) {
            var v = bp.voxels().get(index++);
            var pos = NbtTransforms.pos(transform, v.pos());
            validate(p, pos, v.state());
            if (!dry) snapshot(pos);
            return;
          }
          int extra = index - bp.voxels().size();
          if (extra < markers.size()) {
            var pos = markers.get(extra);
            if (!level.getBlockState(pos).is(PrefabDeploy.BEACON.get()))
              throw new IllegalStateException("A positioning beacon was removed");
            validate(p, pos, Blocks.AIR.defaultBlockState());
            if (!dry && snapshots.stream().noneMatch(n -> n.getLong("p") == pos.asLong()))
              snapshot(pos);
            index++;
            return;
          }
          if (extra < markers.size() + actorChecks.size()) {
            var n = actorChecks.get(extra - markers.size());
            var pos = n.getList("Pos", Tag.TAG_DOUBLE);
            validate(
                p,
                BlockPos.containing(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)),
                Blocks.AIR.defaultBlockState());
            if (n.getString("id").equals("minecraft:player")
                || n.getString("id").equals("minecraft:command_block_minecart"))
              throw new IllegalStateException("Forbidden entity: " + n.getString("id"));
            if (EntityType.loadEntityRecursive(n, level, e -> e) == null)
              throw new IllegalStateException("Entity could not be loaded");
            index++;
            return;
          }
          int tickIndex = extra - markers.size() - actorChecks.size();
          if (tickIndex < bp.ticks().size()) {
            var tick = bp.ticks().get(tickIndex);
            var target =
                tick.fluid()
                    ? Blocks.AIR.defaultBlockState()
                    : BuiltInRegistries.BLOCK
                        .get(ResourceLocation.parse(tick.type()))
                        .defaultBlockState();
            validate(p, NbtTransforms.pos(transform, tick.pos()), target);
            index++;
            return;
          }
          if (dry) {
            Sessions.validation(owner, id, true, Costs.describeText(price));
            release();
            next(Phase.COMPLETE);
            return;
          }
          baseline = true;
          next(Phase.BASESAVE);
        }
        case JOURNAL -> {
          if (pending == null) {
            pending = persist("PREPARED");
            return;
          }
          if (!done()) return;
          prepared = true;
          next(Phase.RESERVE);
        }
        case RESERVE -> {
          var p = player();
          if (p == null) throw new IllegalStateException("Player disconnected before payment");
          Rules.allowedResult(p, prefab, new BlockPos(transform.x(), transform.y(), transform.z()))
              .require();
          Costs.reserve(p, id, price);
          fault("after_payment");
          next(Phase.BLOCKS);
        }
        case BLOCKS -> {
          if (index < markers.size()) {
            var pos = markers.get(index++);
            write(() -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2 | 16 | 32));
            return;
          }
          int i = index++ - markers.size();
          if (i >= prefab.blueprint().voxels().size()) {
            next(Phase.NBT);
            return;
          }
          var v = prefab.blueprint().voxels().get(i);
          var pos = NbtTransforms.pos(transform, v.pos());
          var state = v.state().rotate(NbtTransforms.rotation(transform.turns()));
          if (!state.getCollisionShape(level, pos).isEmpty()
              && !level
                  .getEntitiesOfClass(ServerPlayer.class, new net.minecraft.world.phys.AABB(pos))
                  .isEmpty())
            throw new IllegalStateException("Player entered the construction area");
          write(
              () -> {
                level.removeBlockEntity(pos);
                level.setBlock(pos, state, 2 | 16 | 32);
                if (!level.getBlockState(pos).equals(state))
                  throw new IllegalStateException("Block placement was rejected at " + pos);
              });
          fault("block_" + i);
        }
        case NBT -> {
          if (index >= prefab.blueprint().voxels().size()) {
            next(Phase.ENTITIES);
            return;
          }
          var v = prefab.blueprint().voxels().get(index++);
          if (v.nbt() != null) {
            var pos = NbtTransforms.pos(transform, v.pos());
            var n = NbtTransforms.block(v, transform);
            write(() -> installNbt(pos, n));
          }
          fault("after_nbt");
        }
        case ENTITIES -> {
          if (index >= actorNbt.size()) {
            next(Phase.TICKS);
            return;
          }
          var n = actorNbt.get(index++);
          var entity = EntityType.loadEntityRecursive(n.copy(), level, e -> e);
          if (entity == null) throw new IllegalStateException("Entity creation failed");
          entity
              .getSelfAndPassengers()
              .forEach(e -> e.getPersistentData().putUUID("prefabdeploy_job", id));
          if (!level.tryAddFreshEntityWithPassengers(entity))
            throw new IllegalStateException("Entity identity collision");
          fault("after_entity");
        }
        case TICKS -> {
          if (index >= prefab.blueprint().ticks().size()) {
            next(Phase.SAVE);
            return;
          }
          var t = prefab.blueprint().ticks().get(index++);
          schedule(
              NbtTransforms.pos(transform, t.pos()), t.type(), t.fluid(), t.delay(), t.priority());
        }
        case SAVE, BASESAVE -> saveStep();
        case FLUSH -> {
          if (!done()) return;
          if (baseline) {
            baseline = false;
            saves.clear();
            next(Phase.JOURNAL);
            return;
          }
          if (!restored) fault("after_save");
          if (restored) {
            pending = persist("REFUND");
            nextWithPending(Phase.REFUND, pending);
          } else {
            pending = persist("COMMITTED");
            nextWithPending(Phase.COMMIT, pending);
          }
        }
        case COMMIT -> {
          if (pending != null && !done()) return;
          committed = true;
          var p = player();
          if (p == null) return;
          if (index == 0) {
            Costs.commit(p, id);
            returnBeacons(p);
            index = 1;
            pending = persist("DONE");
            return;
          }
          release();
          Sessions.finished(owner, id, true, id.toString());
          PrefabEvents.completed(p, prefab, id, true);
          queueUpdates();
          next(Phase.COMPLETE);
        }
        case ROLLBACK_ENTITIES -> {
          if (index < actorIds.size()) {
            var entity = level.getEntity(actorIds.get(index++));
            if (entity != null) entity.discard();
            return;
          }
          next(Phase.ROLLBACK_BLOCKS);
        }
        case ROLLBACK_BLOCKS -> {
          if (index >= snapshots.size()) {
            next(Phase.ROLLBACK_NBT);
            return;
          }
          var n = snapshots.get(index++);
          var pos = BlockPos.of(n.getLong("p"));
          var state = StateCodec.read(n.getCompound("s"));
          write(
              () -> {
                level.removeBlockEntity(pos);
                level.setBlock(pos, state, 2 | 16 | 32);
                if (!level.getBlockState(pos).equals(state))
                  throw new IllegalStateException("Rollback block rejected");
              });
        }
        case ROLLBACK_NBT -> {
          if (index >= snapshots.size()) {
            next(Phase.ROLLBACK_TICKS);
            return;
          }
          var n = snapshots.get(index++);
          if (n.contains("n"))
            write(() -> installNbt(BlockPos.of(n.getLong("p")), n.getCompound("n")));
        }
        case ROLLBACK_TICKS -> {
          if (index < chunkList.size()) {
            clearTicks(new ChunkPos(chunkList.get(index++)));
            return;
          }
          int i = index++ - chunkList.size();
          if (i < oldTicks.size()) {
            var n = oldTicks.get(i);
            schedule(
                BlockPos.of(n.getLong("p")),
                n.getString("id"),
                n.getBoolean("f"),
                n.getLong("d"),
                n.getInt("q"));
            return;
          }
          restored = true;
          saves.clear();
          next(Phase.SAVE);
        }
        case REFUND -> {
          if (pending != null && !done()) return;
          release();
          var p = player();
          if (p == null) return;
          if (index == 0) {
            Costs.refund(p, id);
            index = 1;
            pending = persist("DONE");
            return;
          }
          Sessions.finished(owner, id, false, errorDisplay);
          PrefabEvents.completed(p, prefab, id, false);
          next(Phase.COMPLETE);
        }
        default -> {}
      }
    }

    void nextWait() {
      phase = Phase.WAIT_CHUNK;
      chunkLoad = null;
    }

    void nextWithPending(Phase p, CompletableFuture<?> wait) {
      next(p);
      pending = wait;
    }

    boolean entitiesLoaded(ChunkPos cp) {
      return ((ServerLevelAccessor) level).prefabEntityManager().areEntitiesLoaded(cp.toLong());
    }

    void validate(
        ServerPlayer p, BlockPos pos, net.minecraft.world.level.block.state.BlockState next) {
      if (pos.getY() < level.getMinBuildHeight()
          || pos.getY() >= level.getMaxBuildHeight()
          || !level.getWorldBorder().isWithinBounds(pos))
        throw new IllegalStateException("Outside world limits: " + pos.toShortString());
      var current = level.getBlockState(pos);
      String existing = BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString(),
          newId = BuiltInRegistries.BLOCK.getKey(next.getBlock()).toString();
      if (Config.PROTECTED_BLOCKS.get().contains(existing)
          || current.is(
              net.minecraft.tags.TagKey.create(
                  net.minecraft.core.registries.Registries.BLOCK,
                  ResourceLocation.fromNamespaceAndPath("prefabdeploy", "protected"))))
        throw new IllegalStateException(
            "Protected block: " + existing + " at " + pos.toShortString());
      if (Config.FORBIDDEN_CONTENT.get().contains(newId))
        throw new IllegalStateException("Forbidden blueprint content: " + newId);
      String error = OptionalMods.protection(p, pos);
      if (!error.isEmpty()) throw new IllegalStateException(error);
      if (level.isInSpawnableBounds(pos) && server.isUnderSpawnProtection(level, pos, p))
        throw new IllegalStateException("Server spawn protection");
    }

    void snapshot(BlockPos pos) {
      var n = new CompoundTag();
      n.putLong("p", pos.asLong());
      n.put("s", StateCodec.write(level.getBlockState(pos)));
      var be = level.getBlockEntity(pos);
      if (be != null) n.put("n", be.saveWithFullMetadata(level.registryAccess()));
      snapshotBytes += n.sizeInBytes();
      if (snapshotBytes > 128L * 1024 * 1024)
        throw new IllegalStateException("Original world snapshot exceeds the 128 MiB safety limit");
      snapshots.add(n);
    }

    void captureTicks(ChunkPos cp) {
      level.getChunkSource().addRegionTicket(TICKET, cp, 2, id);
      if (dry || recovering || committed) return;
      var chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
      ((LevelChunkTicks<Block>) chunk.getBlockTicks())
          .getAll()
          .forEach(
              t ->
                  oldTicks.add(
                      tick(
                          t.pos(),
                          BuiltInRegistries.BLOCK.getKey(t.type()).toString(),
                          false,
                          t.triggerTick() - level.getGameTime(),
                          t.priority().getValue())));
      ((LevelChunkTicks<net.minecraft.world.level.material.Fluid>) chunk.getFluidTicks())
          .getAll()
          .forEach(
              t ->
                  oldTicks.add(
                      tick(
                          t.pos(),
                          BuiltInRegistries.FLUID.getKey(t.type()).toString(),
                          true,
                          t.triggerTick() - level.getGameTime(),
                          t.priority().getValue())));
    }

    void clearTicks(ChunkPos cp) {
      var chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
      ((LevelChunkTicks<Block>) chunk.getBlockTicks()).removeIf(t -> true);
      ((LevelChunkTicks<net.minecraft.world.level.material.Fluid>) chunk.getFluidTicks())
          .removeIf(t -> true);
    }

    static CompoundTag tick(BlockPos p, String type, boolean fluid, long delay, int priority) {
      var n = new CompoundTag();
      n.putLong("p", p.asLong());
      n.putString("id", type);
      n.putBoolean("f", fluid);
      n.putLong("d", delay);
      n.putInt("q", priority);
      return n;
    }

    void schedule(BlockPos p, String name, boolean fluid, long delay, int priority) {
      var type = ResourceLocation.parse(name);
      long when = level.getGameTime() + Math.max(1, delay);
      if (fluid) {
        if (!BuiltInRegistries.FLUID.containsKey(type))
          throw new IllegalStateException("Unknown fluid tick type");
        level
            .getFluidTicks()
            .schedule(
                new ScheduledTick<>(
                    BuiltInRegistries.FLUID.get(type),
                    p,
                    when,
                    TickPriority.byValue(priority),
                    level.nextSubTickCount()));
      } else {
        if (!BuiltInRegistries.BLOCK.containsKey(type))
          throw new IllegalStateException("Unknown block tick type");
        level
            .getBlockTicks()
            .schedule(
                new ScheduledTick<>(
                    BuiltInRegistries.BLOCK.get(type),
                    p,
                    when,
                    TickPriority.byValue(priority),
                    level.nextSubTickCount()));
      }
    }

    void installNbt(BlockPos pos, CompoundTag n) {
      var be = level.getBlockEntity(pos);
      if (be == null) {
        be = BlockEntity.loadStatic(pos, level.getBlockState(pos), n, level.registryAccess());
        if (be == null)
          throw new IllegalStateException("Block entity could not be loaded at " + pos);
        level.setBlockEntity(be);
      } else be.loadWithComponents(n, level.registryAccess());
      be.setChanged();
      level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 2);
    }

    @SuppressWarnings("unchecked")
    void saveStep() {
      var manager =
          (EntityManagerAccessor<Entity>)
              (Object) ((ServerLevelAccessor) level).prefabEntityManager();
      if (index < chunkList.size()) {
        var cp = new ChunkPos(chunkList.get(index));
        if (!manager.prefabStore(cp.toLong(), e -> {})) return;
        var chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
        var n = ChunkSerializer.write(level, chunk);
        saves.add(level.getChunkSource().chunkMap.write(cp, n));
        chunk.setUnsaved(false);
        index++;
        return;
      }
      CompletableFuture<?> all = CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
      var storage = manager.prefabStorage();
      pending =
          all.thenCompose(v -> ((EntityStorageAccessor) storage).prefabStorage().synchronize(true))
              .thenRunAsync(() -> level.getChunkSource().chunkMap.flushWorker(), IO);
      nextWithPending(Phase.FLUSH, pending);
    }

    boolean done() {
      if (pending == null) return true;
      if (!pending.isDone()) return false;
      pending.join();
      pending = null;
      return true;
    }

    void write(Runnable action) {
      RegionLocks.write(id, action);
    }

    CompletableFuture<Void> persist(String durable) {
      var path = directory().resolve(id + ".journal");
      return CompletableFuture.runAsync(
          () -> {
            try {
              var n = record(durable);
              if (n.sizeInBytes() > 192L * 1024 * 1024)
                throw new IOException(
                    "Recovery journal exceeds its safety limit; no payment was reserved");
              var out = new ByteArrayOutputStream();
              NbtIo.writeCompressed(n, out);
              AtomicFile.write(path, out.toByteArray());
            } catch (IOException ex) {
              throw new CompletionException(ex);
            }
          },
          IO);
    }

    CompoundTag record(String durable) {
      var n = new CompoundTag();
      n.putInt("format", 1);
      n.putUUID("id", id);
      n.putUUID("owner", owner);
      n.putString("dimension", dimension.location().toString());
      n.putString("prefab", prefab.id().toString());
      n.putString("name", prefab.name());
      n.putString("hash", prefab.hash());
      n.putString("error", error);
      errorDisplay.put(n, "errorDisplay");
      n.putString("durable", durable);
      n.putBoolean("success", committed);
      n.put("price", price.copy());
      n.putInt("beaconRefundCount", beaconRefundCount);
      n.putIntArray(
          "transform",
          new int[] {
            transform.x(), transform.y(), transform.z(), transform.turns(), transform.groundY()
          });
      if (durable.equals("DONE")) return n;
      n.put("blueprint", prefab.blueprint().serialize(false));
      n.putString("meta", prefab.metadata().toString());
      n.putLongArray("chunks", chunks.stream().mapToLong(Long::longValue).toArray());
      n.putLongArray("markers", markers.stream().mapToLong(BlockPos::asLong).toArray());
      n.put("snapshots", list(snapshots));
      n.put("oldTicks", list(oldTicks));
      n.put("actors", list(actorNbt));
      return n;
    }

    static ListTag list(List<CompoundTag> data) {
      var list = new ListTag();
      data.forEach(n -> list.add(n.copy()));
      return list;
    }

    static Job restore(CompoundTag n) {
      if (n.getInt("format") != 1)
        throw new IllegalArgumentException("Unsupported journal version");
      var a = n.getIntArray("transform");
      if (a.length != 5) throw new IllegalArgumentException("Invalid journal transform");
      var t = new GridTransform(a[0], a[1], a[2], a[3], a[4]);
      var bp = Blueprint.deserialize(n.getCompound("blueprint"), 5_000_000);
      var meta = com.google.gson.JsonParser.parseString(n.getString("meta")).getAsJsonObject();
      var f =
          new Prefab(
              ResourceLocation.parse(n.getString("prefab")),
              n.getString("name"),
              "recovery",
              a[4],
              bp,
              meta,
              n.getString("hash"),
              "");
      var chunks = new LinkedHashSet<Long>();
      for (long c : n.getLongArray("chunks")) chunks.add(c);
      var markers = Arrays.stream(n.getLongArray("markers")).mapToObj(BlockPos::of).toList();
      var j =
          new Job(
              n.getUUID("id"),
              n.getUUID("owner"),
              f,
              t,
              markers,
              ResourceKey.create(
                  net.minecraft.core.registries.Registries.DIMENSION,
                  ResourceLocation.parse(n.getString("dimension"))),
              chunks);
      j.price = n.getCompound("price");
      j.beaconRefundCount =
          n.contains("beaconRefundCount") ? n.getInt("beaconRefundCount") : markers.size();
      if (j.beaconRefundCount < 0 || j.beaconRefundCount > markers.size())
        throw new IllegalArgumentException("Invalid beacon refund count");
      j.error = n.getString("error");
      j.errorDisplay = io.github.prefabdeploy.UiText.read(n, "errorDisplay", "error");
      for (String key : List.of("snapshots", "oldTicks", "actors")) {
        var list = n.getList(key, Tag.TAG_COMPOUND);
        var target =
            key.equals("snapshots")
                ? j.snapshots
                : key.equals("oldTicks") ? j.oldTicks : j.actorNbt;
        for (int i = 0; i < list.size(); i++) target.add(list.getCompound(i));
      }
      for (var actor : j.actorNbt) collectActorIds(actor, j.actorIds);
      j.prepared = true;
      j.committed = n.getString("durable").equals("COMMITTED");
      j.restored = n.getString("durable").equals("REFUND");
      j.recovering = !j.committed && !j.restored;
      j.locks = true;
      if (j.restored) j.phase = Phase.REFUND;
      return j;
    }

    void fail(Exception ex) {
      error = failure(ex);
      errorDisplay = io.github.prefabdeploy.UiText.fromThrowable(ex);
      PrefabDeploy.LOGGER.error("Prefab task {} failed in {}", id, phase, ex);
      if (dry) {
        Sessions.validation(owner, id, false, errorDisplay);
        release();
        next(Phase.COMPLETE);
      } else if (committed || recovering || restored) {
        if (!locks && RegionLocks.lock(level, chunks, id)) {
          locks = true;
          RegionLocks.closeMenus(level);
        }
        phase = Phase.HELD;
        pending = null;
      } else if (prepared) {
        recovering = true;
        next(Phase.ROLLBACK_ENTITIES);
      } else {
        release();
        Sessions.finished(owner, id, false, errorDisplay);
        next(Phase.COMPLETE);
      }
    }

    void release() {
      RegionLocks.release(id);
      for (long c : chunks)
        level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(c), 2, id);
      locks = false;
    }

    void returnBeacons(ServerPlayer p) {
      if (beaconRefundCount == 0) return;
      var r = p.getPersistentData();
      String key = "prefabdeploy_beacons_" + id;
      if (r.getBoolean(key)) return;
      var stack =
          new net.minecraft.world.item.ItemStack(PrefabDeploy.BEACON_ITEM.get(), beaconRefundCount);
      p.getInventory().add(stack);
      if (!stack.isEmpty()) {
        var mailbox = r.getList("prefabdeploy_mail", Tag.TAG_COMPOUND);
        mailbox.add(stack.save(p.registryAccess()));
        r.put("prefabdeploy_mail", mailbox);
      }
      r.putBoolean(key, true);
      Costs.save(p);
    }

    void queueUpdates() {
      Sessions.queueUpdates(
          level,
          prefab.blueprint().voxels().stream()
              .map(v -> NbtTransforms.pos(transform, v.pos()))
              .toList());
    }

    void fault(String point) {
      if (point.equals(
          System.getProperty(
              "prefabdeploy.testFault." + id, System.getProperty("prefabdeploy.testFault", ""))))
        throw new IllegalStateException("Injected fault at " + point);
    }
  }
}
