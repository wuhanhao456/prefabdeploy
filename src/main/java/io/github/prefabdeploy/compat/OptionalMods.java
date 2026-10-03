package io.github.prefabdeploy.compat;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/** Reflection is confined to this version-checked bridge, never the gameplay core. */
public final class OptionalMods {
  public static boolean loaded(String id) {
    return ModList.get().isLoaded(id);
  }

  public static Object call(Object receiver, String name, Object... args)
      throws ReflectiveOperationException {
    Class<?> cls = receiver instanceof Class<?> c ? c : receiver.getClass();
    outer:
    for (var m : cls.getMethods()) {
      if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
      var types = m.getParameterTypes();
      for (int i = 0; i < args.length; i++)
        if (args[i] != null && !boxed(types[i]).isInstance(args[i])) continue outer;
      return m.invoke(receiver instanceof Class<?> ? null : receiver, args);
    }
    throw new NoSuchMethodException(cls.getName() + "." + name);
  }

  private static Class<?> boxed(Class<?> c) {
    if (c == boolean.class) return Boolean.class;
    if (c == double.class) return Double.class;
    if (c == int.class) return Integer.class;
    if (c == long.class) return Long.class;
    return c;
  }

  public static UUID team(ServerPlayer p) throws ReflectiveOperationException {
    if (!loaded("ftbteams")) throw new IllegalStateException("FTB Teams is required");
    var api = call(Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI"), "api");
    var team = (Optional<?>) call(call(api, "getManager"), "getTeamForPlayer", p);
    if (team.isEmpty()) throw new IllegalStateException("No FTB team");
    return (UUID) call(team.get(), "getTeamId");
  }

  public static boolean quest(ServerPlayer p, String id) throws ReflectiveOperationException {
    if (!loaded("ftbquests")) throw new IllegalStateException("FTB Quests is required");
    var api = call(Class.forName("dev.ftb.mods.ftbquests.api.FTBQuestsAPI"), "api");
    var file = call(api, "getQuestFile", false);
    var object = call(file, "get", Long.parseUnsignedLong(id, 16));
    var data = (Optional<?>) call(file, "getTeamData", p);
    if (object == null || data.isEmpty()) return false;
    return (boolean) call(data.get(), "isCompleted", object);
  }

  public static String protection(ServerPlayer p, net.minecraft.core.BlockPos pos) {
    if (!loaded("ftbchunks")) return "";
    try {
      var api = call(Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI"), "api");
      var manager = call(api, "getManager");
      var type =
          Class.forName("dev.ftb.mods.ftbchunks.api.Protection").getField("EDIT_BLOCK").get(null);
      return (boolean)
              call(
                  manager,
                  "shouldPreventInteraction",
                  p,
                  net.minecraft.world.InteractionHand.MAIN_HAND,
                  pos,
                  type,
                  null)
          ? "FTB Chunks: no edit permission at " + pos.toShortString()
          : "";
    } catch (Exception ex) {
      return "FTB Chunks permission adapter unavailable: " + ex.getClass().getSimpleName();
    }
  }

  public static double money(ServerPlayer p) throws ReflectiveOperationException {
    return ((Number) call(shop(), "getMoney", p)).doubleValue();
  }

  public static double debitMoney(ServerPlayer p, double amount)
      throws ReflectiveOperationException {
    return ((Number) call(shop(), "removeMoney", p, amount)).doubleValue();
  }

  public static void changeMoney(ServerPlayer p, double delta) throws ReflectiveOperationException {
    if (delta >= 0) call(shop(), "addMoney", p, delta);
    else {
      double actual = ((Number) call(shop(), "removeMoney", p, -delta)).doubleValue();
      if (Math.abs(actual + delta) > 1e-7)
        throw new IllegalStateException("ViScriptShop deduction mismatch");
    }
  }

  public static void checkNativeMoney() throws ReflectiveOperationException {
    if (!loaded("viscript_shop")) throw new IllegalStateException("ViScriptShop is required");
    if (Boolean.TRUE.equals(
        call(Class.forName("com.viscriptshop.ViscriptShop"), "isMagicCoinsLoaded"))) {
      var config =
          Class.forName("com.viscriptshop.Config").getField("isReplaceMoneyToMagicCoin").get(null);
      if (config == null)
        throw new IllegalStateException("ViScriptShop currency configuration unavailable");
      if (Boolean.TRUE.equals(call(config, "get")))
        throw new IllegalStateException("Replacement currency needs a recoverable CostProvider");
    }
  }

  private static Class<?> shop() throws ReflectiveOperationException {
    checkNativeMoney();
    return Class.forName("com.viscriptshop.util.ViScriptShopServerUtil");
  }

  private OptionalMods() {}
}
