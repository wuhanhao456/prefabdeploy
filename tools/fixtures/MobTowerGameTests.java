package io.github.prefabdeploy.testing;

import io.github.prefabdeploy.PrefabDeploy;
import io.github.prefabdeploy.compat.BoundContainers;
import io.github.prefabdeploy.core.GridTransform;
import io.github.prefabdeploy.item.ContainerBinding;
import io.github.prefabdeploy.library.*;
import io.github.prefabdeploy.server.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;

/** Runs only in the isolated datapack QA project, never in the released mod. */
@GameTestHolder("mobtowersqa")
@PrefixGameTestTemplate(false)
public final class MobTowerGameTests {
  static Prefab prefab(String name) {
    var f = PrefabLibrary.INSTANCE.get(ResourceLocation.parse("mobtowers:" + name));
    if (f == null || !f.valid()) throw new GameTestAssertException("Invalid prefab " + name + ": " + f);
    return f;
  }
  static ServerPlayer player(GameTestHelper h) {
    var p = h.makeMockServerPlayerInLevel();
    p.setGameMode(GameType.SURVIVAL);
    p.getInventory().clearContent();
    p.getAbilities().invulnerable = true;
    return p;
  }
  static Map<Item,Integer> quoted(ServerPlayer p, Prefab f) {
    var q = Costs.quoteUnchecked(p, f);
    var result = new HashMap<Item,Integer>();
    for (var entry : q.getList("items", Tag.TAG_COMPOUND)) {
      var n = (CompoundTag) entry;
      result.put(BuiltInRegistries.ITEM.get(ResourceLocation.parse(n.getString("id"))), n.getInt("count"));
    }
    if (q.getInt("xp") != 0 || q.getDouble("money") != 0) throw new AssertionError("Unexpected XP/money");
    return result;
  }
  static Map<Item,Integer> actualMaterials(Prefab f, boolean zombie) {
    var result = new HashMap<Item,Integer>();
    for (var v : f.blueprint().voxels()) {
      if (v.state().isAir() || v.state().is(Blocks.WATER)) continue;
      var item = v.state().getBlock().asItem();
      if (zombie && v.state().is(Blocks.SPAWNER)) result.merge(Items.ROTTEN_FLESH, 50, Integer::sum);
      else result.merge(item, 1, Integer::sum);
    }
    return result;
  }
  static List<ChestBlockEntity> fund(GameTestHelper h, ServerPlayer p, Map<Item,Integer> amounts) {
    var left = h.absolutePos(new BlockPos(2, 2, 2));
    var right = left.east();
    h.getLevel().setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
    h.getLevel().setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
    var chests = List.of((ChestBlockEntity)h.getLevel().getBlockEntity(left), (ChestBlockEntity)h.getLevel().getBlockEntity(right));
    chests.forEach(BaseContainerBlockEntity::clearContent);
    int slot = 0;
    for (var e : amounts.entrySet()) {
      int n = e.getValue();
      while (n > 0) {
        int part = Math.min(n, e.getKey().getDefaultMaxStackSize());
        if (slot >= 54) throw new AssertionError("Double chest too small");
        chests.get(slot / 27).setItem(slot % 27, new ItemStack(e.getKey(), part));
        slot++; n -= part;
      }
    }
    var tool = new ItemStack(PrefabDeploy.TOOL.get());
    p.getInventory().setItem(0, tool); p.getInventory().selected = 0;
    p.setPos(left.getX() + .5, left.getY() + 1, left.getZ() + .5);
    try {
      ContainerBinding.write(tool, BoundContainers.bind(p, new BlockHitResult(Vec3.atCenterOf(left), Direction.NORTH, left, false)));
    } catch (Exception e) { throw new RuntimeException(e); }
    return chests;
  }
  static int remaining(List<ChestBlockEntity> boxes) {
    int n = 0;
    for (var box : boxes) for (int i = 0; i < box.getContainerSize(); i++) n += box.getItem(i).getCount();
    return n;
  }
  static BlockPos cell(GridTransform t, int x, int y, int z) {
    var c = t.cell(x,y,z); return new BlockPos(c.x(),c.y(),c.z());
  }
  static void move(ServerPlayer p, GridTransform t, double x, double y, double z) {
    var v = t.point(x,y,z); p.setPos(v.x(),v.y(),v.z());
  }
  static int collected(GameTestHelper h, GridTransform t, boolean zombie, Item item) {
    int n = 0;
    for (int x = 10; x <= (zombie ? 10 : 11); x++) {
      var box = (BarrelBlockEntity)h.getLevel().getBlockEntity(cell(t,x,1,12));
      for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(item)) n += box.getItem(i).getCount();
    }
    return n;
  }
  static void evidence(String name, String message) {
    PrefabDeploy.LOGGER.info("MOB TOWERS VERIFIED {}: {}", name, message);
    try {
      var path = Path.of(System.getProperty("prefabdeploy.reportDir"), "mob-towers-runtime.txt");
      Files.createDirectories(path.getParent());
      Files.writeString(path, name + ": " + message + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  @GameTest(template="empty", templateNamespace="mobtowersqa", timeoutTicks=600)
  public static void imports_costs_missing_resources_and_rules(GameTestHelper h) {
    var p = player(h);
    for (String name : List.of("dark_tower", "zombie_spawner_tower")) {
      var f = prefab(name); boolean zombie = name.startsWith("zombie");
      h.assertTrue(f.groundY() == 0 && f.blueprint().voxels().size() == f.blueprint().width()*f.blueprint().height()*f.blueprint().depth(), "Missing explicit air");
      h.assertTrue(quoted(p,f).equals(actualMaterials(f,zombie)), "Quote differs from real vanilla block materials");
      h.assertTrue(Rules.evaluate(f.metadata().get("visible"),p,f,p.blockPosition()).passed() && Rules.evaluate(f.metadata().get("unlock"),p,f,p.blockPosition()).passed(), "Direct unlock failed");
      h.assertTrue(Rules.evaluate(f.metadata().get("conditions"),p,f,p.blockPosition()).passed(), "Overworld condition failed");
      var q = quoted(p,f);
      for (var missing : q.keySet()) {
        var shortfall = new HashMap<>(q); shortfall.put(missing, shortfall.get(missing)-1);
        var boxes = fund(h,p,shortfall); int before = remaining(boxes);
        boolean denied = false;
        try { Costs.quote(p,f); } catch (IllegalStateException expected) { denied = true; }
        h.assertTrue(denied && remaining(boxes) == before, "Missing " + missing + " did not reject without deduction");
      }
      var nether = h.getLevel().getServer().getLevel(Level.NETHER);
      var np = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(nether);
      h.assertTrue(!Rules.evaluate(f.metadata().get("conditions"),np,f,BlockPos.ZERO).passed(), "Nether condition passed");
      evidence(name, "real datapack import; exact survival quote; every material short by one rejected without deduction; Overworld/direct unlock; Nether rejected");
    }
    long revision = PrefabLibrary.INSTANCE.revision();
    var old = prefab("dark_tower");
    try {
      h.assertTrue(h.getLevel().getServer().getCommands().getDispatcher().execute("prefab reload", h.getLevel().getServer().createCommandSourceStack())==1, "Prefab reload command refused");
    } catch (Exception e) { throw new RuntimeException(e); }
    h.succeedWhen(() -> {
      h.assertTrue(PrefabLibrary.INSTANCE.revision()>revision, "Waiting for actual prefab reload");
      h.assertTrue(prefab("dark_tower").blueprint().voxels().equals(old.blueprint().voxels()), "Reload changed blueprint");
      evidence("reload", "actual prefab reload command completed; both delivered ZIP definitions valid");
      h.getLevel().getServer().getPlayerList().remove(p);
    });
  }

  @GameTest(template="empty", templateNamespace="mobtowersqa", timeoutTicks=16000)
  public static void dark_tower_four_rotations_build_flow_and_collection(GameTestHelper h) {
    rotations(h,false);
  }
  @GameTest(template="empty", templateNamespace="mobtowersqa", timeoutTicks=16000)
  public static void zombie_tower_four_rotations_build_spawner_and_collection(GameTestHelper h) {
    rotations(h,true);
  }

  static void rotations(GameTestHelper h, boolean zombie) {
    var level = h.getLevel();
    level.getServer().setDifficulty(Difficulty.NORMAL, true);
    level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,level.getServer());
    var p = player(h); var f = prefab(zombie ? "zombie_spawner_tower" : "dark_tower");
    var origin = h.absolutePos(new BlockPos(32,3,32));
    class Run {
      int rotation = 0, phase = 0, ticks = 0, spawned = 0;
      UUID job; GridTransform t; List<ChestBlockEntity> boxes;
      final List<Mob> carriers = new ArrayList<>();
      final Set<UUID> seen = new HashSet<>();
      void start() {
        carriers.clear(); seen.clear(); spawned=0; ticks=0;
        t = new GridTransform(origin.getX(),origin.getY(),origin.getZ(),rotation,0);
        boxes = fund(h,p,quoted(p,f));
        job = DeploymentManager.submit(p,f,t,List.of(),false); phase=1;
      }
      void mark(double x,double y,double z) {
        var v = t.point(x,y,z); var mob = EntityType.ZOMBIE.create(level);
        mob.moveTo(v.x(),v.y(),v.z(),0,0); mob.removeFreeWill(); mob.setPersistenceRequired();
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND));
        mob.setDropChance(EquipmentSlot.MAINHAND,2.0F);
        level.addFreshEntity(mob); carriers.add(mob);
      }
      void tick() {
        try {
          if (phase==0) { start(); return; }
          if (phase==1) {
            if (DeploymentManager.outcome(job).isEmpty()) return;
            h.assertTrue(DeploymentManager.outcome(job).get(), "Deployment failed " + job + ": " + DeploymentManager.status());
            h.assertTrue(Costs.state(p,job).equals("COMMITTED") && remaining(boxes)==0, "Survival settlement differs");
            Costs.commit(p,job); h.assertTrue(remaining(boxes)==0, "Duplicate settlement charged twice");
            for (var v : f.blueprint().voxels()) {
              if (v.state().isAir() || v.state().is(Blocks.WATER)) continue;
              var actual = level.getBlockState(cell(t,v.pos().getX(),v.pos().getY(),v.pos().getZ()));
              var expected = v.state().rotate(switch(rotation){case 1 -> Rotation.CLOCKWISE_90; case 2 -> Rotation.CLOCKWISE_180; case 3 -> Rotation.COUNTERCLOCKWISE_90; default -> Rotation.NONE;});
              h.assertTrue(actual.equals(expected), "Rotation/build state differs at " + v.pos() + " expected " + expected + " got " + actual);
            }
            move(p,t,10.5,1.5,13.5); phase=2; ticks=0; return;
          }
          ticks++;
          if (phase==2 && ticks==120) {
            if (zombie) {
              var sp = (SpawnerBlockEntity)level.getBlockEntity(cell(t,6,11,6));
              var tag = sp.saveWithFullMetadata(level.registryAccess());
              h.assertTrue(tag.getCompound("SpawnData").getCompound("entity").getString("id").equals("minecraft:zombie") && tag.getShort("RequiredPlayerRange")==16 && tag.getShort("MinSpawnDelay")==200 && tag.getShort("MaxSpawnDelay")==800, "Spawner NBT is not vanilla zombie settings");
              h.assertTrue(level.getFluidState(cell(t,6,9,6)).is(net.minecraft.tags.FluidTags.WATER), "Room water did not spread");
              mark(3.5,10,3.5); mark(6.5,10,5.5); mark(9.5,10,8.5);
            } else {
              var pos = cell(t,4,27,4);
              h.assertTrue(level.getBrightness(LightLayer.BLOCK,pos)==0 && level.getBrightness(LightLayer.SKY,pos)==0, "Chamber not fully dark");
              h.assertTrue(SpawnPlacements.isSpawnPositionOk(EntityType.ZOMBIE,level,pos) && Monster.checkMonsterSpawnRules(EntityType.ZOMBIE,level,MobSpawnType.NATURAL,pos,level.random), "Vanilla natural spawning rules reject platform");
              move(p,t,10.5,1.5,14.5);
              for(int i=0;i<100;i++) NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER,level,pos);
              var a=t.point(1,24,1); var b=t.point(21,30,21);
              var bounds = new AABB(a.x(),a.y(),a.z(),b.x(),b.y(),b.z());
              spawned = level.getEntitiesOfClass(Monster.class,bounds).size();
              h.assertTrue(spawned>0,"Vanilla natural spawn attempts produced no mobs");
              // Isolate hydraulic transport from random AI walking into the channels.
              level.getEntitiesOfClass(Monster.class,bounds).forEach(Entity::discard);
              mark(10.5,25,3.5); mark(11.5,25,18.5); mark(3.5,25,11.5); mark(18.5,25,10.5);
            }
            phase=3; ticks=0; return;
          }
          if (zombie && phase>=2) {
            var a=t.point(0,0,0); var b=t.point(13,16,14);
            var bounds = new AABB(a.x(),a.y(),a.z(),b.x(),b.y(),b.z()).inflate(1);
            for (var mob : level.getEntitiesOfClass(Zombie.class,bounds)) if (!carriers.contains(mob)) seen.add(mob.getUUID());
            spawned=seen.size();
          }
          if (phase==3 && collected(h,t,zombie,Items.DIAMOND)>=(zombie?3:4) && spawned>0 && (!zombie || collected(h,t,true,Items.ROTTEN_FLESH)>0)) {
            evidence(f.id().toString()+" rotation="+(rotation*90), "survival real construction/one settlement; rotated states; " + (zombie ? "original zombie spawner spawned "+spawned+" zombies; three room water paths to campfire; rotten flesh in barrel" : "zero skylight/block light; vanilla natural spawning produced "+spawned+" monsters; four channel directions to campfires") + "; all test loot reached barrels in "+ticks+" ticks");
            var a=t.point(0,0,0); var b=t.point(f.blueprint().width(),f.blueprint().height(),f.blueprint().depth());
            level.getEntitiesOfClass(Monster.class,new AABB(a.x(),a.y(),a.z(),b.x(),b.y(),b.z()).inflate(1)).forEach(Entity::discard);
            rotation++;
            if(rotation==4) { level.getServer().getPlayerList().remove(p); h.succeed(); }
            else { phase=0; }
          } else if (phase==3 && ticks>2200) {
            var positions = carriers.stream().map(m -> m.getPosition(1)+" alive="+m.isAlive()+" hp="+m.getHealth()).toList();
            throw new GameTestAssertException("Collection timeout rotation="+rotation+" diamonds="+collected(h,t,zombie,Items.DIAMOND)+" spawned="+spawned+" carriers="+positions);
          }
        } catch (Throwable e) { h.fail(e.toString()); }
      }
    }
    var run = new Run(); h.onEachTick(run::tick);
  }
}
