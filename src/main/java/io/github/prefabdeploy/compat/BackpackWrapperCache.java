package io.github.prefabdeploy.compat;

import java.util.*;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Refresh all live wrappers of a normal backpack, including one moved to another player/container. */
public final class BackpackWrapperCache {
  private static final Map<Object, Boolean> WRAPPERS = new WeakHashMap<>();

  public static void track(Object wrapper) {
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server != null && server.isSameThread()) WRAPPERS.put(wrapper, true);
  }

  public static boolean tracked(Object wrapper) { return WRAPPERS.containsKey(wrapper); }

  public static void changed(UUID uuid) throws Exception {
    for (var wrapper : List.copyOf(WRAPPERS.keySet())) {
      var id = (Optional<?>) OptionalMods.call(wrapper, "getContentsUuid");
      if (id.isPresent() && uuid.equals(id.get()))
        OptionalMods.call(wrapper, "onContentsNbtUpdated");
    }
  }

  public static void clear() { WRAPPERS.clear(); }
  private BackpackWrapperCache() {}
}
