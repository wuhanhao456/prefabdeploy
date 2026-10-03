package io.github.prefabdeploy.compat;

import com.google.gson.JsonParser;
import dev.latvian.mods.kubejs.event.*;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import io.github.prefabdeploy.api.*;
import io.github.prefabdeploy.library.*;
import io.github.prefabdeploy.server.ServerState;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class KubeBridge implements KubeJSPlugin {
  private static final Map<String, PlacementRule> SCRIPT_RULES = new HashMap<>();
  private static final Map<String, CostProvider> SCRIPT_COSTS = new HashMap<>();
  public static final EventGroup GROUP = EventGroup.of("PrefabEvents");
  public static final EventHandler REGISTRY = GROUP.server("registry", () -> RegistryEvent.class);
  public static final EventHandler BEFORE = GROUP.server("beforeDeploy", () -> BeforeEvent.class);
  public static final EventHandler COMPLETED =
      GROUP.server("completed", () -> CompletedEvent.class);

  @Override
  public void registerEvents(EventGroupRegistry registry) {
    registry.register(GROUP);
  }

  @Override
  public void registerBindings(BindingRegistry bindings) {
    bindings.add("PrefabAPI", ScriptApi.class);
  }

  static void registry(Map<ResourceLocation, Prefab> definitions) {
    SCRIPT_RULES.forEach((id, rule) -> PrefabApi.RULES.remove(id, rule));
    SCRIPT_COSTS.forEach((id, cost) -> PrefabApi.COSTS.remove(id, cost));
    SCRIPT_RULES.clear();
    SCRIPT_COSTS.clear();
    if (REGISTRY.hasListeners()) REGISTRY.post(new RegistryEvent(definitions));
  }

  static String before(ServerPlayer player, Prefab prefab) {
    var event = new BeforeEvent(player, prefab);
    if (BEFORE.hasListeners()) BEFORE.post(event);
    return event.denied;
  }

  static void completed(ServerPlayer player, Prefab prefab, UUID id, boolean success) {
    if (COMPLETED.hasListeners())
      COMPLETED.post(new CompletedEvent(player, prefab, id.toString(), success));
  }

  public static final class RegistryEvent implements KubeEvent {
    private final Map<ResourceLocation, Prefab> definitions;

    public RegistryEvent(Map<ResourceLocation, Prefab> definitions) {
      this.definitions = definitions;
    }

    public Set<String> getIds() {
      var result = new TreeSet<String>();
      definitions.keySet().forEach(id -> result.add(id.toString()));
      return result;
    }

    public void configure(String id, String json) {
      var key = ResourceLocation.parse(id);
      var f = definitions.get(key);
      if (f == null) throw new IllegalArgumentException("Unknown prefab: " + id);
      var meta = f.metadata().deepCopy();
      var update = JsonParser.parseString(json).getAsJsonObject();
      for (var e : update.entrySet()) {
        if (Set.of("source", "ignore_air", "_source_bytes").contains(e.getKey()))
          throw new IllegalArgumentException(
              "Import settings must be changed in the datapack before reload: " + e.getKey());
        meta.add(e.getKey(), e.getValue());
      }
      definitions.put(key, f.withMetadata(meta));
    }

    public void registerRule(String id, PlacementRule rule) {
      PrefabApi.RULES.put(id, rule);
      SCRIPT_RULES.put(id, rule);
    }

    public void registerCost(String id, CostProvider provider) {
      if (!provider.atomicWithPlayerSave())
        throw new IllegalArgumentException("Cost must be atomic with player NBT");
      PrefabApi.COSTS.put(id, provider);
      SCRIPT_COSTS.put(id, provider);
    }
  }

  public static final class BeforeEvent implements KubeEvent {
    private final ServerPlayer player;
    private final Prefab prefab;
    private String denied = "";

    public BeforeEvent(ServerPlayer player, Prefab prefab) {
      this.player = player;
      this.prefab = prefab;
    }

    public ServerPlayer getPlayer() {
      return player;
    }

    public String getPrefabId() {
      return prefab.id().toString();
    }

    public void deny(String reason) {
      denied = reason;
    }
  }

  public record CompletedEvent(
      ServerPlayer player, Prefab prefab, String transaction, boolean success)
      implements KubeEvent {
    public ServerPlayer getPlayer() {
      return player;
    }

    public String getPrefabId() {
      return prefab.id().toString();
    }

    public String getTransaction() {
      return transaction;
    }

    public boolean isSuccess() {
      return success;
    }
  }

  public static final class ScriptApi {
    public static void grantPlayer(ServerPlayer p, String flag) {
      ServerState.flag(p.server, p.getUUID() + ":" + flag, true);
    }

    public static void revokePlayer(ServerPlayer p, String flag) {
      ServerState.flag(p.server, p.getUUID() + ":" + flag, false);
    }

    public static void grantTeam(ServerPlayer p, String flag) throws Exception {
      ServerState.flag(p.server, OptionalMods.team(p) + ":" + flag, true);
    }

    public static void revokeTeam(ServerPlayer p, String flag) throws Exception {
      ServerState.flag(p.server, OptionalMods.team(p) + ":" + flag, false);
    }

    private ScriptApi() {}
  }
}
