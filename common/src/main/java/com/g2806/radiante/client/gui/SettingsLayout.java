package com.g2806.radiante.client.gui;

import com.g2806.radiante.mixin.gui.OptionInstanceAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * The Radiante settings screen, after Sodium's own: a search field along the top; on the left the mod's name and one
 * entry per section, the one in view marked with a bar; on the right every section in one scrolling list, each under
 * its own heading, options as flat rows with the name on the left and the value (or a checkbox) on the right. Beside
 * the list, the description and cost of the option under the mouse. Actions (Reset, Advanced, Undo, Apply, Done) are
 * flat text buttons along the bottom.
 *
 * <p>Rows are drawn and handled here rather than as vanilla widgets: a click on a toggle flips it, on a list of values
 * steps to the next one (right click: the previous one), and a slider follows the mouse while held.
 *
 * <p>Pictures, all optional ({@code assets/radiante/textures/gui/settings/}, from the mod or a resource pack):
 * {@code icon.png} (32 x 32) beside the mod's name, and {@code <section>.png} (16 x 16) beside a section's name.
 */
final class SettingsLayout {

    /** A section and its options, in order. */
    record Section(RadianteOptionsScreen.Category category, List<OptionInstance<?>> options) {
    }

    /** What the bottom buttons do. */
    record Actions(Runnable reset, Runnable undo, Runnable apply, Runnable done) {
    }

    private static final int ACCENT = 0xFF8FE3C8;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFB0B8B4;
    private static final int PANEL = 0xC0101212;
    private static final int PANEL_DARK = 0xD0060707;
    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int GROUP = 0x60000000;

    private static final int ROW = 20;
    private static final int HEADER = 22;
    private static final int GAP = 6;
    private static final int PAD = 8;

    private final Font font;
    private final int width;
    private final int height;
    private final List<Section> sections;
    private final Actions actions;
    private final Consumer<Double> onScroll;
    private EditBox search;

    // Laid out in init.
    private int sidebarX;
    private int sidebarW;
    private int listX;
    private int listW;
    private int top;
    private int bottom;
    private int panelX;
    private int panelW;
    private double scroll;
    private int contentHeight;

    /** One line of the list: a section heading or an option. */
    private record Line(Section section, OptionInstance<?> option, int y, int height) {
    }

    private final List<Line> lines = new ArrayList<>();
    private final List<int[]> sectionSpans = new ArrayList<>();
    private OptionInstance<?> described;
    private OptionInstance<Integer> dragging;
    private int dragX;
    private int dragWidth;

    private record FlatButton(Component label, Runnable action, boolean enabled, Component tooltip, int x, int y,
                              int w) {
    }

    private final List<FlatButton> buttons = new ArrayList<>();

    SettingsLayout(Font font, int width, int height, List<Section> sections, Actions actions, Consumer<Double> onScroll) {
        this.font = font;
        this.width = width;
        this.height = height;
        this.sections = sections;
        this.actions = actions;
        this.onScroll = onScroll;
    }

    double scroll() {
        return this.scroll;
    }

    String searchText() {
        return this.search == null ? "" : this.search.getValue();
    }

    /** Lays the screen out; the search field is handed back to be added to the screen. */
    EditBox init(double initialScroll, String initialSearch) {
        int margin = Math.max(8, this.width / 24);
        this.top = 40;
        this.bottom = this.height - 30;
        this.sidebarX = margin;
        this.sidebarW = Math.max(90, Math.min(150, this.width / 5));
        this.listX = this.sidebarX + this.sidebarW + GAP;
        int remaining = this.width - margin - this.listX;
        this.panelW = remaining > 460 ? Math.min(200, remaining / 3) : 0;
        this.listW = remaining - (this.panelW > 0 ? this.panelW + GAP : 0);
        this.panelX = this.listX + this.listW + GAP;

        this.search = new EditBox(this.font, this.sidebarX, 12, this.width - margin * 2, 18,
            Component.translatable("options.radiante.search"));
        this.search.setHint(Component.translatable("options.radiante.search").withStyle(ChatFormatting.DARK_GRAY));
        this.search.setValue(initialSearch);
        this.search.setResponder(text -> {
            this.scroll = 0.0;
            layoutLines();
        });

        layoutLines();
        this.scroll = clampScroll(initialScroll);

        int y = this.height - 24;
        int x = this.width - margin;
        for (FlatButton button : List.of(
            flat("gui.done", null, this.actions.done(), true),
            flat("options.radiante.apply", "options.radiante.apply.tooltip", this.actions.apply(), true),
            flat("options.radiante.undo", "options.radiante.undo.tooltip", this.actions.undo(), true))) {
            int w = this.font.width(button.label()) + 16;
            x -= w;
            this.buttons.add(new FlatButton(button.label(), button.action(), true, button.tooltip(), x, y, w));
            x -= 4;
        }
        int left = this.sidebarX;
        for (FlatButton button : List.of(
            flat("options.radiante.reset_defaults", "options.radiante.reset_defaults.tooltip", this.actions.reset(),
                true),
            flat("options.radiante.advanced", "options.radiante.advanced.tooltip", () -> { }, false))) {
            int w = this.font.width(button.label()) + 16;
            this.buttons.add(new FlatButton(button.label(), button.action(), button.enabled(), button.tooltip(), left, y,
                w));
            left += w + 4;
        }
        return this.search;
    }

    private static FlatButton flat(String key, String tooltip, Runnable action, boolean enabled) {
        return new FlatButton(Component.translatable(key), action, enabled,
            tooltip == null ? null : Component.translatable(tooltip), 0, 0, 0);
    }

    /** Puts every section and option that matches the search one under the other. */
    private void layoutLines() {
        this.lines.clear();
        this.sectionSpans.clear();
        String filter = this.search == null ? "" : this.search.getValue().trim().toLowerCase(Locale.ROOT);
        int y = 0;
        for (Section section : this.sections) {
            // One section at a time, as tabs; a search looks through all of them.
            if (filter.isEmpty() && section.category() != selected) {
                continue;
            }
            List<OptionInstance<?>> shown = new ArrayList<>();
            for (OptionInstance<?> option : section.options()) {
                if (filter.isEmpty() || caption(option).getString().toLowerCase(Locale.ROOT).contains(filter)) {
                    shown.add(option);
                }
            }
            if (shown.isEmpty()) {
                continue;
            }
            int start = y;
            this.lines.add(new Line(section, null, y, HEADER));
            y += HEADER + 2;
            for (OptionInstance<?> option : shown) {
                this.lines.add(new Line(section, option, y, ROW));
                y += ROW;
            }
            y += GAP;
            this.sectionSpans.add(new int[] {section.category().ordinal(), start, y});
        }
        this.contentHeight = y;
    }

    private double clampScroll(double value) {
        return Math.max(0.0, Math.min(value, Math.max(0, this.contentHeight - (this.bottom - this.top))));
    }

    // ---- drawing ----

    void extract(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // Sidebar: the mod, then the sections.
        g.fill(this.sidebarX, this.top, this.sidebarX + this.sidebarW, this.bottom, PANEL);
        int headerY = this.top + 6;
        Identifier icon = texture("icon");
        int nameX = this.sidebarX + PAD;
        if (icon != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, icon, nameX, headerY, 0.0f, 0.0f, 24, 24, 24, 24);
            nameX += 30;
        }
        g.text(this.font, Component.literal("Radiante").withStyle(ChatFormatting.BOLD), nameX, headerY + 3, TEXT);
        g.text(this.font, Component.translatable("options.radiante.subtitle"), nameX, headerY + 14, TEXT_DIM);
        int entryY = this.top + 38;
        RadianteOptionsScreen.Category inView = sectionInView();
        for (Section section : this.sections) {
            boolean selected = section.category() == inView;
            boolean hover = inside(mouseX, mouseY, this.sidebarX, entryY, this.sidebarW, 18);
            if (selected) {
                g.fill(this.sidebarX, entryY, this.sidebarX + this.sidebarW, entryY + 18, PANEL_DARK);
                g.fill(this.sidebarX + this.sidebarW - 3, entryY, this.sidebarX + this.sidebarW, entryY + 18, ACCENT);
            } else if (hover) {
                g.fill(this.sidebarX, entryY, this.sidebarX + this.sidebarW, entryY + 18, ROW_HOVER);
            }
            int textX = this.sidebarX + PAD;
            Identifier sectionIcon = texture(section.category().name().toLowerCase(Locale.ROOT));
            if (sectionIcon != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, sectionIcon, textX, entryY + 1, 0.0f, 0.0f, 16, 16, 16, 16);
                textX += 20;
            }
            g.text(this.font, Component.translatable(section.category().key()), textX, entryY + 5,
                selected ? TEXT : ACCENT);
            entryY += 20;
        }

        // The list.
        g.enableScissor(this.listX, this.top, this.listX + this.listW, this.bottom);
        OptionInstance<?> hovered = null;
        int base = this.top - (int) this.scroll;
        int groupStart = -1;
        for (int i = 0; i < this.lines.size(); i++) {
            Line line = this.lines.get(i);
            int y = base + line.y();
            if (line.option() == null) {
                g.fill(this.listX, y, this.listX + this.listW, y + HEADER, PANEL_DARK);
                g.text(this.font, Component.literal("◆ ").append(Component.translatable(line.section().category().key())),
                    this.listX + PAD, y + 7, ACCENT);
                groupStart = y + HEADER + 2;
                continue;
            }
            boolean lastOfSection = i + 1 >= this.lines.size() || this.lines.get(i + 1).option() == null;
            if (lastOfSection && groupStart >= 0) {
                g.fill(this.listX + 4, groupStart, this.listX + this.listW - 4, y + ROW, GROUP);
            }
        }
        for (Line line : this.lines) {
            if (line.option() == null) {
                continue;
            }
            int y = base + line.y();
            if (y + ROW < this.top || y > this.bottom) {
                continue;
            }
            boolean hover = inside(mouseX, mouseY, this.listX + 4, y, this.listW - 8, ROW)
                && mouseY >= this.top && mouseY < this.bottom;
            if (hover) {
                g.fill(this.listX + 4, y, this.listX + this.listW - 4, y + ROW, ROW_HOVER);
                hovered = line.option();
            }
            drawRow(g, line.option(), y, hover);
        }
        g.disableScissor();
        if (this.contentHeight > this.bottom - this.top) {
            int view = this.bottom - this.top;
            int bar = Math.max(16, view * view / this.contentHeight);
            int barY = this.top + (int) ((view - bar) * this.scroll / (this.contentHeight - view));
            g.fill(this.listX + this.listW - 2, barY, this.listX + this.listW, barY + bar, ACCENT);
        }
        if (hovered != null) {
            this.described = hovered;
        }

        drawDescription(g, mouseX, mouseY, hovered);

        for (FlatButton button : this.buttons) {
            boolean hover = button.enabled() && inside(mouseX, mouseY, button.x(), button.y(), button.w(), 16);
            g.fill(button.x(), button.y(), button.x() + button.w(), button.y() + 16, hover ? PANEL_DARK : PANEL);
            if (hover) {
                g.fill(button.x(), button.y() + 15, button.x() + button.w(), button.y() + 16, ACCENT);
            }
            g.centeredText(this.font, button.label(), button.x() + button.w() / 2, button.y() + 4,
                button.enabled() ? (hover ? ACCENT : TEXT) : 0xFF606060);
            if (button.tooltip() != null && inside(mouseX, mouseY, button.x(), button.y(), button.w(), 16)) {
                g.setTooltipForNextFrame(this.font.split(button.tooltip(), 200), mouseX, mouseY);
            }
        }
    }

    private void drawRow(GuiGraphicsExtractor g, OptionInstance<?> option, int y, boolean hover) {
        int right = this.listX + this.listW - PAD - 4;
        g.text(this.font, caption(option), this.listX + PAD + 4, y + 6, TEXT);
        Object value = option.get();
        if (isCheckbox(option) && value instanceof Boolean on) {
            int box = 10;
            int bx = right - box;
            int by = y + 5;
            g.outline(bx, by, box, box, hover ? ACCENT : 0xFFD0D0D0);
            if (on) {
                g.fill(bx + 2, by + 2, bx + box - 2, by + box - 2, ACCENT);
            }
            return;
        }
        Component text = valueText(option);
        int textWidth = this.font.width(text);
        if (option.values() instanceof OptionInstance.IntRange range) {
            // A thin track under the value, filled to where it stands.
            int trackW = Math.min(90, this.listW / 3);
            int tx = right - trackW;
            int ty = y + ROW - 4;
            int span = Math.max(1, range.maxInclusive() - range.minInclusive());
            int filled = (int) ((long) trackW * ((Integer) value - range.minInclusive()) / span);
            g.fill(tx, ty, tx + trackW, ty + 2, 0x60FFFFFF);
            g.fill(tx, ty, tx + filled, ty + 2, ACCENT);
            g.fill(tx + filled - 1, ty - 2, tx + filled + 1, ty + 4, TEXT);
        }
        g.text(this.font, text, right - textWidth, y + 6, hover ? ACCENT : TEXT);
    }

    private void drawDescription(GuiGraphicsExtractor g, int mouseX, int mouseY, OptionInstance<?> hovered) {
        OptionInstance<?> option = this.panelW > 0 ? this.described : hovered;
        if (option == null) {
            return;
        }
        List<FormattedCharSequence> lines = new ArrayList<>();
        Tooltip tooltip = tooltipOf(option);
        int wrap = this.panelW > 0 ? this.panelW - PAD * 2 : 200;
        if (tooltip != null) {
            lines.addAll(tooltip.toCharSequence(Minecraft.getInstance()));
        }
        OptionImpact impact = OptionImpact.of(keyOf(caption(option)));
        if (this.panelW <= 0) {
            List<FormattedCharSequence> all = new ArrayList<>(lines);
            if (impact != null) {
                all.addAll(this.font.split(impactLine(impact), wrap));
            }
            if (!all.isEmpty()) {
                g.setTooltipForNextFrame(all, mouseX, mouseY);
            }
            return;
        }
        g.fill(this.panelX, this.top, this.panelX + this.panelW, this.bottom, PANEL);
        int x = this.panelX + PAD;
        int y = this.top + PAD;
        for (FormattedCharSequence line : this.font.split(caption(option).copy().withStyle(ChatFormatting.BOLD), wrap)) {
            g.text(this.font, line, x, y, ACCENT);
            y += 11;
        }
        y += 4;
        for (FormattedCharSequence line : lines) {
            g.text(this.font, line, x, y, TEXT_DIM);
            y += 10;
        }
        if (impact != null) {
            y += 8;
            for (FormattedCharSequence line : this.font.split(impactLine(impact), wrap)) {
                g.text(this.font, line, x, y, TEXT);
                y += 10;
            }
        }
    }

    private static Component impactLine(OptionImpact impact) {
        return Component.translatable("options.radiante.impact",
            Component.translatable(impact.key).withColor(impact.color));
    }

    // ---- input ----

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (FlatButton flat : this.buttons) {
            if (flat.enabled() && inside(mouseX, mouseY, flat.x(), flat.y(), flat.w(), 16)) {
                flat.action().run();
                return true;
            }
        }
        // Sidebar: jump to the section.
        int entryY = this.top + 38;
        for (Section section : this.sections) {
            if (inside(mouseX, mouseY, this.sidebarX, entryY, this.sidebarW, 18)) {
                if (selected != section.category()) {
                    selected = section.category();
                    this.scroll = 0.0;
                    this.onScroll.accept(this.scroll);
                    if (this.search != null) {
                        this.search.setValue("");
                    }
                    layoutLines();
                }
                return true;
            }
            entryY += 20;
        }
        if (mouseY < this.top || mouseY >= this.bottom || mouseX < this.listX || mouseX >= this.listX + this.listW) {
            return false;
        }
        int base = this.top - (int) this.scroll;
        for (Line line : this.lines) {
            if (line.option() == null) {
                continue;
            }
            int y = base + line.y();
            if (mouseY >= y && mouseY < y + ROW) {
                activate(line.option(), mouseX, button);
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void activate(OptionInstance<?> option, double mouseX, int button) {
        Object value = option.get();
        if (value instanceof Boolean on) {
            ((OptionInstance<Boolean>) option).set(!on);
        } else if (option.values() instanceof OptionInstance.IntRange) {
            int trackW = Math.min(90, this.listW / 3);
            this.dragging = (OptionInstance<Integer>) option;
            this.dragWidth = trackW;
            this.dragX = this.listX + this.listW - PAD - 4 - trackW;
            dragTo(mouseX);
        } else if (option.values() instanceof OptionInstance.Enum<?> values) {
            List<Object> list = (List<Object>) values.valueListSupplier().getDefaultList();
            int index = list.indexOf(value);
            int next = Math.floorMod(index + (button == 1 ? -1 : 1), list.size());
            ((OptionInstance<Object>) option).set(list.get(next));
        }
    }

    boolean mouseDragged(double mouseX) {
        if (this.dragging == null) {
            return false;
        }
        dragTo(mouseX);
        return true;
    }

    void mouseReleased() {
        this.dragging = null;
    }

    private void dragTo(double mouseX) {
        OptionInstance.IntRange range = (OptionInstance.IntRange) this.dragging.values();
        double t = Math.max(0.0, Math.min(1.0, (mouseX - this.dragX) / this.dragWidth));
        int value = range.minInclusive() + (int) Math.round(t * (range.maxInclusive() - range.minInclusive()));
        if (value != this.dragging.get()) {
            this.dragging.set(value);
        }
    }

    boolean scrollBy(double mouseX, double mouseY, double amount) {
        if (mouseX < this.listX || mouseX > this.listX + this.listW || mouseY < this.top || mouseY > this.bottom) {
            return false;
        }
        this.scroll = clampScroll(this.scroll - amount * ROW * 2);
        this.onScroll.accept(this.scroll);
        return true;
    }

    // ---- helpers ----

    /** The section shown; kept while the game runs, so the screen reopens on it. */
    private static RadianteOptionsScreen.Category selected = RadianteOptionsScreen.Category.QUALITY;

    private RadianteOptionsScreen.Category sectionInView() {
        return selected;
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    /**
     * Looked up once per screen: asking the resource manager for a missing file walks every pack and throws on each,
     * and doing that every frame stalled the render thread.
     */
    private static final java.util.Map<String, java.util.Optional<Identifier>> textures = new java.util.HashMap<>();

    private Identifier texture(String name) {
        return textures.computeIfAbsent(name, key -> {
            Identifier id = Identifier.fromNamespaceAndPath("radiante", "textures/gui/settings/" + key + ".png");
            return Minecraft.getInstance().getResourceManager().getResource(id).isPresent() ? java.util.Optional.of(id)
                : java.util.Optional.empty();
        }).orElse(null);
    }

    /** A toggle drawn as a checkbox: plain On/Off. One with its own words for the two states shows them instead. */
    private static boolean isCheckbox(OptionInstance<?> option) {
        if (!(option.get() instanceof Boolean)) {
            return false;
        }
        String text = valueText(option).getString();
        return text.equals(Component.translatable("options.on").getString())
            || text.equals(Component.translatable("options.off").getString());
    }

    private static OptionInstanceAccessor accessor(OptionInstance<?> option) {
        return (OptionInstanceAccessor) (Object) option;
    }

    private static Component caption(OptionInstance<?> option) {
        return accessor(option).radiante$caption();
    }

    /** The value alone: the option's own "Name: value" text without the name in front. */
    private static Component valueText(OptionInstance<?> option) {
        Component full = accessor(option).radiante$toString().apply(option.get());
        String name = caption(option).getString();
        String text = full.getString();
        if (text.startsWith(name + ": ")) {
            return Component.literal(text.substring(name.length() + 2));
        }
        return full;
    }

    private static Tooltip tooltipOf(OptionInstance<?> option) {
        OptionInstance.TooltipSupplier<Object> supplier = accessor(option).radiante$tooltip();
        return supplier == null ? null : supplier.apply(option.get());
    }

    private static String keyOf(Component caption) {
        return caption.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : null;
    }
}
