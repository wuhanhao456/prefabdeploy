package io.github.prefabdeploy.library;

import com.google.gson.*;
import io.github.prefabdeploy.UiText;
import io.github.prefabdeploy.api.PrefabApi;
import io.github.prefabdeploy.compat.OptionalMods;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class Rules {
  /** Failure semantics are independent of the client's translated display text. */
  public record Result(UiText text, boolean unavailable) {
    public boolean passed() {
      return text == null;
    }

    public String legacy() {
      return passed() ? "" : text.plain();
    }

    public void require() {
      if (!passed()) throw UiText.failure(text);
    }
  }

  private static final Result PASS = new Result(null, false);

  private static Result denied(String text) {
    return new Result(UiText.fromLegacy(text), false);
  }

  private static Result unavailable(String text) {
    return new Result(UiText.fromLegacy(text), true);
  }

  public static String test(JsonElement value, ServerPlayer p, Prefab f, BlockPos anchor) {
    return evaluate(value, p, f, anchor).legacy();
  }

  public static Result evaluate(JsonElement value, ServerPlayer p, Prefab f, BlockPos anchor) {
    if (value == null || value.isJsonNull()) return PASS;
    try {
      var dependency = requirements(value);
      if (!dependency.passed()) return dependency;
      if (value.isJsonPrimitive()) return value.getAsBoolean() ? PASS : denied("Rule denied");
      if (value.isJsonArray()) {
        for (var child : value.getAsJsonArray()) {
          var result = evaluate(child, p, f, anchor);
          if (!result.passed()) return result;
        }
        return PASS;
      }
      var o = value.getAsJsonObject();
      String type = o.get("type").getAsString();
      return switch (type) {
        case "all" -> evaluate(o.get("rules"), p, f, anchor);
        case "any" -> {
          boolean passed = false;
          Result error = denied("No alternative condition satisfied");
          for (var child : o.getAsJsonArray("rules")) {
            var result = evaluate(child, p, f, anchor);
            if (result.unavailable()) {
              error = result;
              passed = false;
              break;
            }
            passed |= result.passed();
          }
          yield passed ? PASS : error;
        }
        case "not" -> {
          var result = evaluate(o.get("rule"), p, f, anchor);
          yield result.unavailable() ? result : result.passed() ? denied("Excluded by rule") : PASS;
        }
        case "advancement" -> {
          var a = p.server.getAdvancements().get(ResourceLocation.parse(o.get("id").getAsString()));
          yield a != null && p.getAdvancements().getOrStartProgress(a).isDone()
              ? PASS
              : denied("Advancement required: " + o.get("id").getAsString());
        }
        case "ftb_quest" ->
            OptionalMods.quest(p, o.get("id").getAsString())
                ? PASS
                : denied("FTB quest required: " + o.get("id").getAsString());
        case "dimension" ->
            p.level().dimension().location().toString().equals(o.get("id").getAsString())
                ? PASS
                : denied("Dimension condition not satisfied");
        case "flag" -> {
          String owner =
              PrefabLibrary.string(o, "scope", "player").equals("team")
                  ? OptionalMods.team(p).toString()
                  : p.getUUID().toString();
          yield io.github.prefabdeploy.server.ServerState.flags(p.server)
                  .contains(owner + ":" + o.get("id").getAsString())
              ? PASS
              : denied("Unlock flag required: " + o.get("id").getAsString());
        }
        case "script" -> {
          var rule = PrefabApi.RULES.get(o.get("id").getAsString());
          if (rule == null)
            yield unavailable(
                "Condition unavailable: missing script rule " + o.get("id").getAsString());
          String result;
          try {
            result = rule.check(p, f, anchor);
          } catch (Exception ex) {
            throw UiText.failure(
                UiText.literal(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
          }
          yield result.isEmpty() ? PASS : new Result(UiText.literal(result), false);
        }
        default -> unavailable("Condition unavailable: unknown rule " + type);
      };
    } catch (Exception ex) {
      return new Result(
          UiText.tr(
              "rule.unavailable_details", "Condition unavailable: %s", UiText.fromThrowable(ex)),
          true);
    }
  }

  public static boolean visible(ServerPlayer p, Prefab f) {
    return evaluate(f.metadata().get("visible"), p, f, p.blockPosition()).passed();
  }

  private static Result requirements(JsonElement value) {
    if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return PASS;
    if (value.isJsonArray()) {
      for (var child : value.getAsJsonArray()) {
        var result = requirements(child);
        if (!result.passed()) return result;
      }
      return PASS;
    }
    var o = value.getAsJsonObject();
    String type = o.get("type").getAsString();
    if (!Set.of("all", "any", "not", "advancement", "ftb_quest", "dimension", "flag", "script")
        .contains(type)) return unavailable("Condition unavailable: unknown rule " + type);
    if (type.equals("ftb_quest") && !OptionalMods.loaded("ftbquests"))
      return unavailable("Condition unavailable: FTB Quests is required");
    if (type.equals("flag")
        && PrefabLibrary.string(o, "scope", "player").equals("team")
        && !OptionalMods.loaded("ftbteams"))
      return unavailable("Condition unavailable: FTB Teams is required");
    if (type.equals("script") && !PrefabApi.RULES.containsKey(o.get("id").getAsString()))
      return unavailable("Condition unavailable: missing script rule " + o.get("id").getAsString());
    for (String key : List.of("rules", "rule")) {
      var result = requirements(o.get(key));
      if (!result.passed()) return result;
    }
    return PASS;
  }

  public static Result allowedResult(ServerPlayer p, Prefab f, BlockPos anchor) {
    if (!visible(p, f)) return denied("Building is hidden");
    if (!f.valid()) return denied(f.error());
    if (f.blueprint().voxels().size() > io.github.prefabdeploy.Config.MAX_POSITIONS.get())
      return denied("Server position limit exceeded");
    if (f.metadata().has("_source_bytes")
        && f.metadata().get("_source_bytes").getAsInt()
            > io.github.prefabdeploy.Config.MAX_BYTES.get())
      return denied("Server blueprint size limit exceeded");
    var result = evaluate(f.metadata().get("unlock"), p, f, anchor);
    return result.passed() ? evaluate(f.metadata().get("conditions"), p, f, anchor) : result;
  }

  public static String allowed(ServerPlayer p, Prefab f, BlockPos anchor) {
    return allowedResult(p, f, anchor).legacy();
  }

  public static UiText describeText(JsonElement value) {
    if (value == null || value.isJsonNull()) return UiText.tr("rule.none", "None");
    if (value.isJsonPrimitive())
      return UiText.tr(
          value.getAsBoolean() ? "rule.none" : "rule.disabled",
          value.getAsBoolean() ? "None" : "Unavailable");
    if (value.isJsonArray())
      return UiText.join(
          value.getAsJsonArray().asList().stream().map(Rules::describeText).toList(), false);
    var o = value.getAsJsonObject();
    String id = PrefabLibrary.string(o, "id", "");
    return switch (PrefabLibrary.string(o, "type", "unknown")) {
      case "all" -> describeText(o.get("rules"));
      case "any" -> UiText.tr("rule.any", "One of: %s", describeText(o.get("rules")));
      case "not" -> UiText.tr("rule.not", "Excluded: %s", describeText(o.get("rule")));
      case "advancement" -> UiText.tr("rule.advancement", "Advancement: %s", id);
      case "ftb_quest" -> UiText.tr("rule.quest", "FTB Quest: %s", id);
      case "dimension" -> UiText.tr("rule.dimension", "Dimension: %s", id);
      case "flag" ->
          UiText.tr(
              "rule.flag",
              "%s unlock: %s",
              UiText.tr(
                  "rule."
                      + (PrefabLibrary.string(o, "scope", "player").equals("team")
                          ? "team"
                          : "player"),
                  PrefabLibrary.string(o, "scope", "player")),
              id);
      case "script" -> UiText.tr("rule.script", "Rule: %s", id);
      default -> UiText.tr("rule.unknown", "Unavailable rule");
    };
  }

  public static String describe(JsonElement value) {
    return describeText(value).plain();
  }

  private Rules() {}
}
