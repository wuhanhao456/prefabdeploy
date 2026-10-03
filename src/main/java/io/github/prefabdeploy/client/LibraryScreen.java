package io.github.prefabdeploy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import io.github.prefabdeploy.UiText;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class LibraryScreen extends Screen {
  private static final int WHITE = 0xffffffff, ORANGE = 0xffffa34b;
  private final List<GlassButton> rows = new ArrayList<>();
  private EditBox search;
  private List<CompoundTag> filtered = List.of();
  private CompoundTag selected;
  private int offset;
  private float yaw = 35, pitch = 25, zoom = 1;
  private GlassButton quick, beacon;
  private Rect sidebar, main, viewport, info;
  private int rowTop, rowHeight;

  private record Rect(int x, int y, int w, int h) {
    int right() {
      return x + w;
    }

    int bottom() {
      return y + h;
    }

    boolean contains(double px, double py) {
      return px >= x && px < right() && py >= y && py < bottom();
    }
  }

  public LibraryScreen() {
    super(Component.translatable("prefabdeploy.library"));
  }

  @Override
  protected void init() {
    String query = search == null ? "" : search.getValue();
    rows.clear();
    int margin = width < 400 ? 10 : 16, gap = 10;
    int leftWidth = Math.max(108, Math.min(208, (width - 2 * margin - gap) * 30 / 100));
    sidebar = new Rect(margin, 47, leftWidth, Math.max(28, height - 122));
    main =
        new Rect(
            sidebar.right() + gap,
            47,
            Math.max(100, width - margin - sidebar.right() - gap),
            Math.max(50, height - 94));
    int infoHeight = height < 300 ? 66 : 82;
    info = new Rect(main.x + 8, main.bottom() - infoHeight - 8, main.w - 16, infoHeight);
    viewport = new Rect(main.x + 8, main.y + 49, main.w - 16, Math.max(12, info.y - main.y - 57));
    search =
        new EditBox(
            font,
            sidebar.x + 9,
            sidebar.y + 9,
            sidebar.w - 18,
            18,
            Component.translatable("prefabdeploy.search"));
    search.setBordered(false);
    search.setTextColor(WHITE);
    search.setTextColorUneditable(0xffe4e7eb);
    search.setHint(Component.translatable("prefabdeploy.search"));
    search.setValue(query);
    search.setResponder(
        value -> {
          offset = 0;
          filter();
        });
    addRenderableWidget(search);
    rowTop = sidebar.y + 37;
    rowHeight = height < 300 ? 29 : 36;
    int bottom = height - 36, buttonWidth = Math.max(44, (main.w - 8) / 2);
    quick =
        addRenderableWidget(
            new GlassButton(
                main.x,
                bottom,
                buttonWidth,
                24,
                Component.translatable("prefabdeploy.quick"),
                b -> Client.choose(selected, false),
                true));
    beacon =
        addRenderableWidget(
            new GlassButton(
                main.x + buttonWidth + 8,
                bottom,
                main.w - buttonWidth - 8,
                24,
                Component.translatable("prefabdeploy.beacons"),
                b -> Client.choose(selected, true),
                false));
    var folder = addRenderableWidget(
        new GlassButton(sidebar.x, bottom - 28, sidebar.w, 24,
            Component.translatable("prefabdeploy.local.open_folder"),
            b -> LocalFolder.open(), false));
    folder.active = Client.localImportAllowed;
    folder.setTooltip(Tooltip.create(Component.translatable("prefabdeploy.local.formats")
        .append(Client.localImportAllowed ? Component.empty() :
            Component.translatable("prefabdeploy.local.host_only_tooltip"))));
    addRenderableWidget(
        new GlassButton(
            sidebar.x,
            bottom,
            sidebar.w,
            24,
            Component.translatable("gui.done"),
            b -> onClose(),
            false));
    filter();
  }

  private int visibleRows() {
    return Math.max(1, (sidebar.bottom() - rowTop - 8) / rowHeight);
  }

  private void filter() {
    String query = search.getValue().toLowerCase(Locale.ROOT);
    filtered =
        Client.catalog.stream()
            .filter(
                n ->
                    (n.getString("name") + " " + n.getString("id") + " " + category(n).getString())
                        .toLowerCase(Locale.ROOT)
                        .contains(query))
            .toList();
    offset = Math.max(0, Math.min(offset, Math.max(0, filtered.size() - visibleRows())));
    for (var row : rows) removeWidget(row);
    rows.clear();
    for (int i = 0; i < visibleRows() && i + offset < filtered.size(); i++) {
      var entry = filtered.get(i + offset);
      var button =
          new GlassButton(
              sidebar.x + 7,
              rowTop + i * rowHeight,
              sidebar.w - 14,
              rowHeight - 4,
              Component.literal(entry.getString("name")),
              b -> select(entry),
              false);
      button.entry = entry;
      button.setTooltip(
          Tooltip.create(
              Component.literal(entry.getString("name") + "\n" + entry.getString("id"))
                  .append("\n")
                  .append(
                      Component.translatable(
                          "prefabdeploy.category", category(entry)))));
      rows.add(addRenderableWidget(button));
    }
    if (selected == null || !filtered.contains(selected)) {
      selected = null;
      if (!filtered.isEmpty()) select(filtered.getFirst());
    }
    refreshButtons();
  }

  private static Component category(CompoundTag entry) {
    return UiText.read(entry, "category_display", "category").component();
  }

  void select(CompoundTag entry) {
    selected = entry;
    Client.requestPreview(entry.getString("id"));
    refreshButtons();
  }

  private void refreshButtons() {
    if (quick == null) return;
    boolean enabled =
        selected != null
            && selected.getBoolean("unlocked")
            && selected.getString("error").isEmpty();
    quick.active = enabled;
    beacon.active = enabled;
    for (var row : rows) row.selected = row.entry == selected;
  }

  static void rounded(GuiGraphics gui, int x, int y, int w, int h, int color) {
    if (w <= 0 || h <= 0) return;
    int r = Math.min(5, Math.min(w, h) / 2);
    gui.fill(x, y + r, x + w, y + h - r, color);
    for (int i = 0; i < r; i++) {
      int inset = (int) Math.ceil(r - Math.sqrt(r * r - (r - i - .5) * (r - i - .5)));
      gui.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
      gui.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
    }
  }

  private void panel(GuiGraphics gui, Rect r, int color) {
    rounded(gui, r.x + 1, r.y + 2, r.w, r.h, 0x30000000);
    rounded(gui, r.x, r.y, r.w, r.h, color);
  }

  private void line(GuiGraphics gui, Component text, int x, int y, int maxWidth, int color) {
    gui.drawString(
        font, font.plainSubstrByWidth(text.getString(), Math.max(1, maxWidth)), x, y, color, true);
  }

  @Override
  public void render(GuiGraphics gui, int mouseX, int mouseY, float partial) {
    Component infoTooltip = null;
    // Keep the live world visible; vanilla's full-screen blur/dark gradient is deliberately
    // omitted.
    gui.fill(0, 0, width, height, 0x18000000);
    panel(gui, new Rect(sidebar.x, 12, width - sidebar.x * 2, 27), 0x9fc5cad0);
    gui.fill(sidebar.x + 9, 19, sidebar.x + 12, 32, ORANGE);
    line(gui, title, sidebar.x + 20, 21, width / 2, WHITE);
    Component count =
        Component.translatable(
            "prefabdeploy.filtered_count", filtered.size(), Client.catalog.size());
    gui.drawString(font, count, width - sidebar.x - font.width(count) - 10, 21, WHITE, true);
    panel(gui, sidebar, 0x8fc5cad0);
    panel(gui, main, 0x8fc5cad0);
    rounded(
        gui,
        search.getX() - 3,
        search.getY() - 4,
        search.getWidth() + 6,
        24,
        search.isFocused() ? 0xa5686d74 : 0x886d737b);
    if (search.isFocused())
      gui.fill(
          search.getX(),
          search.getY() + 16,
          search.getX() + search.getWidth(),
          search.getY() + 17,
          ORANGE);
    if (selected != null) {
      int x = main.x + 10, available = main.w - 20;
      line(gui, Component.literal(selected.getString("name")), x, main.y + 9, available, WHITE);
      line(
          gui,
          Component.translatable(
              "prefabdeploy.dimensions",
              selected.getInt("x"),
              selected.getInt("z"),
              selected.getInt("y")),
          x,
          main.y + 23,
          available,
          WHITE);
      line(
          gui,
          Component.translatable(
              "prefabdeploy.reference",
              Math.max(0, selected.getInt("y") - selected.getInt("ground")),
              Math.max(0, selected.getInt("ground"))),
          x,
          main.y + 36,
          available,
          WHITE);
      rounded(gui, viewport.x, viewport.y, viewport.w, viewport.h, 0x365c646f);
      renderPreview(gui, selected, partial);
      panel(gui, info, 0x99777f89);
      var cost = UiText.read(selected, "cost_display", "cost").component();
      var conditions = UiText.read(selected, "conditions_display", "conditions").component();
      var reason = UiText.read(selected, "reason_display", "reason").component();
      line(
          gui,
          Component.translatable("prefabdeploy.cost"),
          info.x + 8,
          info.y + 7,
          info.w - 16,
          ORANGE);
      int costRows = info.h > 70 ? 2 : 1, y = info.y + 21;
      var costLines = font.split(cost, Math.max(1, info.w - 16));
      for (int i = 0; i < Math.min(costRows, costLines.size()); i++) {
        gui.drawString(font, costLines.get(i), info.x + 8, y, WHITE, true);
        y += 10;
      }
      line(
          gui,
          Component.translatable("prefabdeploy.conditions", conditions),
          info.x + 8,
          info.bottom() - 27,
          info.w - 16,
          WHITE);
      boolean failed = !selected.getString("reason").isEmpty();
      line(
          gui,
          failed ? reason : Component.translatable("prefabdeploy.available"),
          info.x + 8,
          info.bottom() - 14,
          info.w - 16,
          failed ? ORANGE : WHITE);
      if (info.contains(mouseX, mouseY))
        infoTooltip =
            cost.copy()
                .append("\n")
                .append(conditions)
                .append("\n")
                .append(failed ? reason : Component.translatable("prefabdeploy.available"));
    } else
      line(
          gui,
          Component.translatable("prefabdeploy.empty"),
          main.x + 12,
          main.y + 15,
          main.w - 24,
          WHITE);
    for (var child : children())
      if (child instanceof Renderable widget) widget.render(gui, mouseX, mouseY, partial);
    if (infoTooltip != null)
      gui.renderTooltip(
          font, font.split(infoTooltip, Math.max(90, Math.min(360, width - 32))), mouseX, mouseY);
  }

  private void renderPreview(GuiGraphics gui, CompoundTag entry, float partial) {
    var mesh = Client.mesh(entry.getString("id"));
    if (mesh == null) {
      line(
          gui,
          Component.translatable("prefabdeploy.loading_preview"),
          viewport.x + 8,
          viewport.y + 6,
          viewport.w - 16,
          WHITE);
      return;
    }
    long started = System.nanoTime();
    mesh.build(started + 2_000_000);
    gui.flush();
    var bp = mesh.blueprint();
    var rotation =
        new org.joml.Matrix4f()
            .rotationX((float) Math.toRadians(pitch))
            .rotateY((float) Math.toRadians(yaw));
    float minX = Float.MAX_VALUE,
        maxX = -Float.MAX_VALUE,
        minY = Float.MAX_VALUE,
        maxY = -Float.MAX_VALUE;
    for (int bx : new int[] {0, bp.width()})
      for (int by : new int[] {0, bp.height()})
        for (int bz : new int[] {0, bp.depth()}) {
          var corner =
              new org.joml.Vector3f(
                      bx - bp.width() / 2f, by - bp.height() / 2f, bz - bp.depth() / 2f)
                  .mulPosition(rotation);
          minX = Math.min(minX, corner.x);
          maxX = Math.max(maxX, corner.x);
          minY = Math.min(minY, corner.y);
          maxY = Math.max(maxY, corner.y);
        }
    float scale =
        Math.min(viewport.w / Math.max(1, maxX - minX), viewport.h / Math.max(1, maxY - minY))
            * .82f
            * zoom;
    var pose = gui.pose();
    pose.pushPose();
    gui.enableScissor(viewport.x, viewport.y, viewport.right(), viewport.bottom());
    float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
    try {
      pose.translate(viewport.x + viewport.w / 2f, viewport.y + viewport.h / 2f, 300);
      pose.scale(scale, -scale, scale);
      pose.mulPose(Axis.XP.rotationDegrees(pitch));
      pose.mulPose(Axis.YP.rotationDegrees(yaw));
      pose.translate(-bp.width() / 2f, -bp.height() / 2f, -bp.depth() / 2f);
      RenderSystem.setShaderFogStart(10000);
      RenderSystem.setShaderFogEnd(10001);
      mesh.draw(pose, RenderSystem.getProjectionMatrix(), 1, box -> true);
    } finally {
      RenderSystem.setShaderFogStart(fogStart);
      RenderSystem.setShaderFogEnd(fogEnd);
      pose.popPose();
      gui.disableScissor();
    }
    Client.recordFrame(started);
    if (!mesh.ready())
      line(
          gui,
          Component.translatable("prefabdeploy.building_preview", mesh.processed()),
          viewport.x + 6,
          viewport.y + 6,
          viewport.w - 12,
          WHITE);
    else if (viewport.h > 50)
      line(
          gui,
          Component.translatable("prefabdeploy.preview_controls"),
          viewport.x + 6,
          viewport.bottom() - 13,
          viewport.w - 12,
          WHITE);
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    if (button == 0 && viewport.contains(x, y)) {
      yaw += (float) dx;
      pitch = Math.max(-85, Math.min(85, pitch + (float) dy));
      return true;
    }
    return super.mouseDragged(x, y, button, dx, dy);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double dx, double dy) {
    if (sidebar.contains(x, y)) {
      offset =
          Math.max(
              0,
              Math.min(
                  Math.max(0, filtered.size() - visibleRows()), offset - (int) Math.signum(dy)));
      filter();
      return true;
    }
    if (viewport.contains(x, y)) {
      zoom = Math.max(.2f, Math.min(5, zoom + (float) dy * .1f));
      return true;
    }
    return super.mouseScrolled(x, y, dx, dy);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  private static final class GlassButton extends Button {
    private final boolean primary;
    boolean selected;
    CompoundTag entry;

    GlassButton(int x, int y, int w, int h, Component text, OnPress press, boolean primary) {
      super(x, y, w, h, text, press, DEFAULT_NARRATION);
      this.primary = primary;
    }

    @Override
    protected void renderWidget(GuiGraphics gui, int mouseX, int mouseY, float partial) {
      var font = Minecraft.getInstance().font;
      boolean highlight = active && (isHoveredOrFocused() || selected);
      rounded(
          gui,
          getX(),
          getY(),
          width,
          height,
          !active ? 0x5d737982 : highlight ? 0xd0b26d31 : primary ? 0xbd9e682f : 0x97777e88);
      if (selected || isFocused())
        gui.fill(getX() + 3, getY() + 5, getX() + 5, getY() + height - 5, ORANGE);
      if (entry != null) {
        gui.drawString(
            font,
            font.plainSubstrByWidth(getMessage().getString(), width - 16),
            getX() + 8,
            getY() + 5,
            WHITE,
            true);
        if (height >= 30)
          gui.drawString(
              font,
              font.plainSubstrByWidth(
                  (entry.getBoolean("unlocked")
                          ? category(entry)
                          : Component.translatable("prefabdeploy.locked"))
                      .getString(),
                  width - 16),
              getX() + 8,
              getY() + 17,
              WHITE,
              true);
      } else
        gui.drawCenteredString(
            font,
            font.plainSubstrByWidth(getMessage().getString(), width - 10),
            getX() + width / 2,
            getY() + (height - 8) / 2,
            active ? WHITE : 0xffcdd2d7);
    }
  }
}
