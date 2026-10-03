package io.github.prefabdeploy.server;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class ServerState extends SavedData {
  private final Set<String> flags = new HashSet<>();

  public static ServerState get(MinecraftServer s) {
    return s.overworld()
        .getDataStorage()
        .computeIfAbsent(
            new Factory<>(ServerState::new, ServerState::load, null), "prefabdeploy_flags");
  }

  public static Set<String> flags(MinecraftServer s) {
    return get(s).flags;
  }

  public static void flag(MinecraftServer s, String key, boolean grant) {
    var state = get(s);
    if (grant) state.flags.add(key);
    else state.flags.remove(key);
    state.setDirty();
  }

  private static ServerState load(CompoundTag n, HolderLookup.Provider lookup) {
    var s = new ServerState();
    var list = n.getList("flags", Tag.TAG_STRING);
    for (int i = 0; i < list.size(); i++) s.flags.add(list.getString(i));
    return s;
  }

  @Override
  public CompoundTag save(CompoundTag n, HolderLookup.Provider lookup) {
    var list = new ListTag();
    flags.stream().sorted().forEach(f -> list.add(StringTag.valueOf(f)));
    n.put("flags", list);
    return n;
  }
}
