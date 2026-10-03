package io.github.prefabdeploy;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;

/**
 * Language-neutral player text. Legacy English diagnostics and extension strings stay supported.
 */
public record UiText(String key, String fallback, List<UiText> arguments) {
  private record Template(String key, String fallback, Pattern pattern) {}

  private static final List<Template> ERRORS = load();

  public static UiText literal(String value) {
    return new UiText("", value == null ? "" : value, List.of());
  }

  public static UiText tr(String key, String fallback, Object... args) {
    return new UiText(
        key.startsWith("prefabdeploy.") ? key : "prefabdeploy." + key,
        fallback,
        Arrays.stream(args)
            .map(a -> a instanceof UiText t ? t : literal(String.valueOf(a)))
            .toList());
  }

  public static UiText join(List<UiText> parts, boolean alternatives) {
    if (parts.isEmpty()) return tr("rule.none", "None");
    return new UiText(
        alternatives ? "prefabdeploy.text.or_list" : "prefabdeploy.text.and_list",
        alternatives ? " / " : "; ",
        List.copyOf(parts));
  }

  public Component component() {
    if (isList()) {
      var joined = Component.empty();
      for (int i = 0; i < arguments.size(); i++) {
        if (i > 0)
          joined.append(
              Component.translatableWithFallback(
                  key.equals("prefabdeploy.text.or_list")
                      ? "prefabdeploy.text.or_separator"
                      : "prefabdeploy.text.and_separator",
                  fallback));
        joined.append(arguments.get(i).component());
      }
      return joined;
    }
    return key.isEmpty()
        ? Component.literal(fallback)
        : Component.translatableWithFallback(
            key, fallback, arguments.stream().map(UiText::component).toArray());
  }

  public String plain() {
    if (isList()) return String.join(fallback, arguments.stream().map(UiText::plain).toList());
    if (arguments.isEmpty()) return fallback;
    try {
      return String.format(Locale.ROOT, fallback, arguments.stream().map(UiText::plain).toArray());
    } catch (IllegalFormatException ex) {
      return fallback;
    }
  }

  public CompoundTag tag() {
    var n = new CompoundTag();
    n.putString("key", key);
    n.putString("fallback", fallback);
    var args = new ListTag();
    arguments.forEach(a -> args.add(a.tag()));
    n.put("args", args);
    return n;
  }

  private boolean isList() {
    return key.equals("prefabdeploy.text.and_list") || key.equals("prefabdeploy.text.or_list");
  }

  public void put(CompoundTag n, String field) {
    n.put(field, tag());
  }

  public void message(CompoundTag n) {
    n.putString("text", plain());
    put(n, "text_display");
  }

  public static UiText read(CompoundTag n, String field, String legacyField) {
    return n.contains(field, Tag.TAG_COMPOUND)
        ? decode(n.getCompound(field), 0)
        : fromLegacy(n.getString(legacyField));
  }

  private static UiText decode(CompoundTag n, int depth) {
    if (depth > 32) return literal(n.getString("fallback"));
    var list = n.getList("args", Tag.TAG_COMPOUND);
    var args = new ArrayList<UiText>();
    for (int i = 0; i < Math.min(8192, list.size()); i++)
      args.add(decode(list.getCompound(i), depth + 1));
    return new UiText(n.getString("key"), n.getString("fallback"), List.copyOf(args));
  }

  public static UiText fromLegacy(String value) {
    if (value == null || value.isEmpty()) return literal("");
    for (var template : ERRORS) {
      var match = template.pattern.matcher(value);
      if (!match.matches()) continue;
      var args = new ArrayList<UiText>();
      for (int i = 1; i <= match.groupCount(); i++) args.add(literal(match.group(i)));
      return new UiText(template.key, template.fallback, List.copyOf(args));
    }
    return literal(value);
  }

  public static UiText fromThrowable(Throwable error) {
    while ((error instanceof java.util.concurrent.CompletionException
            || error instanceof java.util.concurrent.ExecutionException)
        && error.getCause() != null) error = error.getCause();
    return error instanceof Failure f
        ? f.text
        : fromLegacy(error.getMessage() == null ? error.toString() : error.getMessage());
  }

  public static Failure failure(UiText text) {
    return new Failure(text);
  }

  public static final class Failure extends IllegalStateException {
    public final UiText text;

    public Failure(UiText text) {
      super(text.plain());
      this.text = text;
    }
  }

  private static List<Template> load() {
    try (var stream = UiText.class.getResourceAsStream("/assets/prefabdeploy/messages.json")) {
      if (stream == null) throw new IOException("Missing localization catalog");
      var json =
          JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
              .getAsJsonArray();
      var result = new ArrayList<Template>();
      for (var entry : json) {
        var o = entry.getAsJsonObject();
        result.add(
            new Template(
                o.get("key").getAsString(),
                o.get("fallback").getAsString(),
                Pattern.compile(o.get("pattern").getAsString(), Pattern.DOTALL)));
      }
      return List.copyOf(result);
    } catch (IOException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }
}
