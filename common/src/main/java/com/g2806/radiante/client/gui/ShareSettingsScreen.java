package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.option.PresetLibrary;
import com.g2806.radiante.client.option.SettingsCode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * The looks a player keeps, and sharing them, laid out like the settings they belong to: the mod and what can be
 * added on the left, the saved presets as a list of flat rows in the middle - picked with a click as a shader pack
 * is, applied with a second one - what the picked preset is on the right, and flat buttons along the bottom for
 * what is done with it.
 */
final class ShareSettingsScreen extends Screen {

    // The settings screen's own (SettingsLayout), so the two read as one place.
    private static final int ACCENT = 0xFF8FE3C8;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFB0B8B4;
    private static final int PANEL = 0xC0101212;
    private static final int PANEL_DARK = 0xD0060707;
    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int DISABLED = 0xFF606060;
    private static final int BAD = 0xFFFF8080;
    private static final int NOTE = 0xFFE8C860;
    private static final int ROW = 20;
    private static final int HEADER = 22;
    private static final int GAP = 6;
    private static final int PAD = 8;
    private static final int ENTRY = 18;
    private static final int BUTTON = 16;
    private static final Identifier ICON =
        Identifier.fromNamespaceAndPath("radiante", "textures/gui/settings/icon.png");

    /** Something to click: a sidebar entry or a bottom button. */
    private record Action(Component label, Component tooltip, Runnable run, boolean enabled, int x, int y, int w,
                          int h, int color) {
    }

    private final Screen parent;
    /** A fresh settings screen, made after a preset is applied so it shows the new values. */
    private final Supplier<Screen> settings;
    private final List<Action> entries = new ArrayList<>();
    private final List<Action> buttons = new ArrayList<>();
    private List<PresetLibrary.Preset> presets = List.of();
    private int current = -1;
    private int selected = -1;
    private boolean pickedCurrent;
    private double scroll;
    private boolean confirmDelete;
    private Component status;
    private int statusColor;
    private String typedName = "";
    private EditBox nameBox;

    private int top;
    private int bottom;
    private int sidebarX;
    private int sidebarW;
    private int listX;
    private int listW;
    private int panelX;
    private int panelW;

    ShareSettingsScreen(Screen parent, Supplier<Screen> settings) {
        super(Component.translatable("screen.radiante.share.title"));
        this.parent = parent;
        this.settings = settings;
    }

    @Override
    protected void init() {
        this.presets = PresetLibrary.all();
        this.current = PresetLibrary.current();
        if (this.selected >= this.presets.size()) {
            this.selected = -1;
        }
        // Opens on the look in use, so the list starts from where the player is.
        if (!this.pickedCurrent) {
            this.pickedCurrent = true;
            this.selected = this.current;
        }

        int margin = Math.max(8, this.width / 24);
        this.top = 40;
        this.bottom = this.height - 30;
        this.sidebarX = margin;
        this.sidebarW = Math.max(128, Math.min(150, this.width / 4));
        this.listX = this.sidebarX + this.sidebarW + GAP;
        int remaining = this.width - margin - this.listX;
        this.panelW = remaining > 290 ? Math.min(170, remaining / 3) : 0;
        this.listW = remaining - (this.panelW > 0 ? this.panelW + GAP : 0);
        this.panelX = this.listX + this.listW + GAP;
        this.scroll = clampScroll(this.scroll);

        this.nameBox = new EditBox(this.font, this.sidebarX, 12, this.width - margin * 2, 18,
            Component.translatable("screen.radiante.share.name"));
        this.nameBox.setMaxLength(40);
        this.nameBox.setHint(Component.translatable("screen.radiante.share.name.hint").withStyle(ChatFormatting.DARK_GRAY));
        this.nameBox.setValue(this.typedName);
        this.nameBox.setResponder(text -> this.typedName = text);
        this.addRenderableWidget(this.nameBox);

        // ---- the sidebar: what can be added to the list ----
        this.entries.clear();
        int y = this.top + 38;
        this.entries.add(entry("screen.radiante.share.save", "screen.radiante.share.save.tooltip", y,
            () -> keep(SettingsCode.export(), "screen.radiante.share.default_name", "screen.radiante.share.saved")));
        y += ENTRY;
        this.entries.add(entry("screen.radiante.share.paste", "screen.radiante.share.paste.tooltip", y,
            this::importFromClipboard));

        // ---- the bottom: what is done with the picked one ----
        this.buttons.clear();
        boolean picked = this.selected >= 0 && this.selected < this.presets.size();
        int buttonY = this.height - 24;
        int x = this.width - margin;
        x = button("gui.done", null, this::onClose, true, x, buttonY, true, TEXT);
        x = button("screen.radiante.share.apply", "screen.radiante.share.apply.tooltip", this::applySelected,
            picked && this.selected != this.current, x, buttonY, true, TEXT);
        button("screen.radiante.share.copy", "screen.radiante.share.copy.tooltip", this::copySelected, picked, x,
            buttonY, true, TEXT);
        x = this.sidebarX;
        x = button("screen.radiante.share.rename", "screen.radiante.share.rename.tooltip", this::renameSelected,
            picked, x, buttonY, false, TEXT);
        button(this.confirmDelete ? "screen.radiante.share.delete_confirm" : "screen.radiante.share.delete",
            "screen.radiante.share.delete.tooltip", this::deleteSelected, picked, x, buttonY, false,
            this.confirmDelete ? BAD : TEXT);
    }

    private Action entry(String key, String tooltipKey, int y, Runnable run) {
        return new Action(Component.translatable(key), Component.translatable(tooltipKey), run, true, this.sidebarX, y,
            this.sidebarW, ENTRY, TEXT);
    }

    /** Adds a bottom button growing leftwards from {@code x}, or rightwards; returns where the next one goes. */
    private int button(String key, String tooltipKey, Runnable run, boolean enabled, int x, int y, boolean leftwards,
        int color) {
        Component label = Component.translatable(key);
        int w = this.font.width(label) + 16;
        int left = leftwards ? x - w : x;
        this.buttons.add(new Action(label, tooltipKey == null ? null : Component.translatable(tooltipKey), run, enabled,
            left, y, w, BUTTON, color));
        return leftwards ? left - 4 : left + w + 4;
    }

    // ---- what the clicks do ----

    /** Puts a look in the library under the typed name, or a numbered default, and picks it. */
    private void keep(String code, String defaultNameKey, String doneKey) {
        String name = this.typedName.isBlank() ? Component.translatable(defaultNameKey).getString() : this.typedName;
        int index = PresetLibrary.add(name, code);
        if (index < 0) {
            say(Component.translatable("screen.radiante.share.full"), BAD);
            return;
        }
        this.selected = index;
        this.typedName = "";
        this.confirmDelete = false;
        say(Component.translatable(doneKey, PresetLibrary.all().get(index).name()), ACCENT);
        showSelected();
    }

    private void importFromClipboard() {
        String code = this.minecraft.keyboardHandler.getClipboard();
        SettingsCode.Parsed parsed = SettingsCode.parse(code);
        if (parsed == null) {
            say(Component.translatable("screen.radiante.share.invalid"), BAD);
            return;
        }
        keep(code, "screen.radiante.share.imported_name", "screen.radiante.share.imported");
        if (parsed.skipped() > 0) {
            say(Component.translatable("screen.radiante.share.skipped", parsed.skipped()), NOTE);
        }
    }

    private void applySelected() {
        SettingsCode.Parsed parsed = SettingsCode.parse(this.presets.get(this.selected).code());
        if (parsed == null) {
            say(Component.translatable("screen.radiante.share.invalid"), BAD);
            return;
        }
        boolean rebuild = SettingsCode.apply(parsed);
        Screen next = this.settings.get();
        this.minecraft.gui.setScreen(rebuild ? new ApplyingSettingsScreen(next) : next);
    }

    private void copySelected() {
        this.minecraft.keyboardHandler.setClipboard(this.presets.get(this.selected).code());
        say(Component.translatable("screen.radiante.share.copied"), ACCENT);
    }

    private void renameSelected() {
        if (this.typedName.isBlank()) {
            this.setFocused(this.nameBox);
            say(Component.translatable("screen.radiante.share.need_name"), NOTE);
            return;
        }
        PresetLibrary.rename(this.selected, this.typedName);
        this.typedName = "";
        say(Component.translatable("screen.radiante.share.renamed"), ACCENT);
    }

    private void deleteSelected() {
        if (!this.confirmDelete) {
            this.confirmDelete = true;
            this.status = null;
            this.rebuildWidgets();
            return;
        }
        PresetLibrary.remove(this.selected);
        this.selected = -1;
        this.confirmDelete = false;
        say(Component.translatable("screen.radiante.share.deleted"), ACCENT);
    }

    private void say(Component message, int color) {
        this.status = message;
        this.statusColor = color;
        this.rebuildWidgets();
    }

    /** Scrolls the list so that the picked preset is in view. */
    private void showSelected() {
        int view = this.bottom - this.top - HEADER;
        int rowTop = this.selected * ROW;
        if (rowTop < this.scroll) {
            this.scroll = rowTop;
        } else if (rowTop + ROW > this.scroll + view) {
            this.scroll = rowTop + ROW - view;
        }
        this.scroll = clampScroll(this.scroll);
    }

    private double clampScroll(double value) {
        int view = this.bottom - this.top - HEADER;
        return Math.max(0.0, Math.min(value, Math.max(0, this.presets.size() * ROW - view)));
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double x = event.x();
        double y = event.y();
        for (List<Action> group : List.of(this.entries, this.buttons)) {
            for (Action action : group) {
                if (action.enabled() && inside(x, y, action.x(), action.y(), action.w(), action.h())) {
                    net.minecraft.client.gui.components.AbstractWidget.playButtonClickSound(
                        this.minecraft.getSoundManager());
                    action.run().run();
                    return true;
                }
            }
        }
        int listTop = this.top + HEADER;
        if (inside(x, y, this.listX, listTop, this.listW, this.bottom - listTop)) {
            int index = (int) ((y - listTop + this.scroll) / ROW);
            if (index >= 0 && index < this.presets.size()) {
                net.minecraft.client.gui.components.AbstractWidget.playButtonClickSound(this.minecraft.getSoundManager());
                // A second click on the picked one switches to it, as picking a shader pack twice does.
                if (doubleClick && index == this.selected && index != this.current) {
                    applySelected();
                    return true;
                }
                this.selected = index;
                this.confirmDelete = false;
                this.status = null;
                this.rebuildWidgets();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inside(mouseX, mouseY, this.listX, this.top, this.listW, this.bottom - this.top)) {
            this.scroll = clampScroll(this.scroll - scrollY * ROW);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ---- drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        drawSidebar(g, mouseX, mouseY);
        drawList(g, mouseX, mouseY);
        drawDetails(g);
        for (Action button : this.buttons) {
            boolean hover = button.enabled() && inside(mouseX, mouseY, button.x(), button.y(), button.w(), button.h());
            g.fill(button.x(), button.y(), button.x() + button.w(), button.y() + BUTTON, hover ? PANEL_DARK : PANEL);
            if (hover) {
                g.fill(button.x(), button.y() + BUTTON - 1, button.x() + button.w(), button.y() + BUTTON, ACCENT);
            }
            g.centeredText(this.font, button.label(), button.x() + button.w() / 2, button.y() + 4,
                !button.enabled() ? DISABLED : hover && button.color() == TEXT ? ACCENT : button.color());
            tooltip(g, button, mouseX, mouseY);
        }
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(this.sidebarX, this.top, this.sidebarX + this.sidebarW, this.bottom, PANEL);
        int headerY = this.top + 6;
        g.blit(RenderPipelines.GUI_TEXTURED, ICON, this.sidebarX + PAD, headerY, 0.0f, 0.0f, 24, 24, 24, 24);
        int nameX = this.sidebarX + PAD + 30;
        g.text(this.font, Component.literal("Radiante").withStyle(ChatFormatting.BOLD), nameX, headerY + 3, TEXT);
        g.text(this.font, this.title, nameX, headerY + 14, TEXT_DIM);
        int end = this.top + 38;
        for (Action entry : this.entries) {
            boolean hover = inside(mouseX, mouseY, entry.x(), entry.y(), entry.w(), entry.h());
            if (hover) {
                g.fill(entry.x(), entry.y(), entry.x() + entry.w(), entry.y() + ENTRY, ROW_HOVER);
                g.fill(entry.x() + entry.w() - 3, entry.y(), entry.x() + entry.w(), entry.y() + ENTRY, ACCENT);
            }
            g.text(this.font, Component.literal("+ ").withColor(ACCENT), entry.x() + PAD, entry.y() + 5, ACCENT);
            g.text(this.font, Component.literal(this.font.plainSubstrByWidth(entry.label().getString(),
                entry.w() - PAD * 2 - 10)), entry.x() + PAD + 10, entry.y() + 5, hover ? ACCENT : TEXT);
            tooltip(g, entry, mouseX, mouseY);
            end = entry.y() + ENTRY;
        }
        // What a preset is, in the space under the entries.
        int y = end + 10;
        for (FormattedCharSequence line : this.font.split(Component.translatable("screen.radiante.share.message"),
            this.sidebarW - PAD * 2)) {
            if (y + 9 > this.bottom - 4) {
                break;
            }
            g.text(this.font, line, this.sidebarX + PAD, y, DISABLED);
            y += 10;
        }
    }

    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(this.listX, this.top, this.listX + this.listW, this.bottom, PANEL);
        g.fill(this.listX, this.top, this.listX + this.listW, this.top + HEADER, PANEL_DARK);
        g.text(this.font, Component.literal("◆ ").append(this.title), this.listX + PAD, this.top + 7, ACCENT);
        Component count = Component.literal(Integer.toString(this.presets.size()));
        g.text(this.font, count, this.listX + this.listW - PAD - this.font.width(count), this.top + 7, TEXT_DIM);

        int listTop = this.top + HEADER;
        if (this.presets.isEmpty()) {
            int y = listTop + 14;
            for (FormattedCharSequence line : this.font.split(Component.translatable("screen.radiante.share.empty"),
                this.listW - PAD * 4)) {
                g.centeredText(this.font, line, this.listX + this.listW / 2, y, TEXT_DIM);
                y += 10;
            }
            return;
        }
        g.enableScissor(this.listX, listTop, this.listX + this.listW, this.bottom);
        Component inUse = Component.translatable("screen.radiante.share.in_use");
        for (int index = 0; index < this.presets.size(); index++) {
            int y = listTop + index * ROW - (int) this.scroll;
            if (y + ROW <= listTop || y >= this.bottom) {
                continue;
            }
            boolean hover = inside(mouseX, mouseY, this.listX, Math.max(y, listTop), this.listW, ROW)
                && mouseY >= listTop && mouseY < this.bottom;
            if (index == this.selected) {
                g.fill(this.listX + 4, y, this.listX + this.listW - 4, y + ROW, PANEL_DARK);
                g.fill(this.listX + 4, y, this.listX + 7, y + ROW, ACCENT);
            } else if (hover) {
                g.fill(this.listX + 4, y, this.listX + this.listW - 4, y + ROW, ROW_HOVER);
            }
            int right = this.listX + this.listW - PAD - 4;
            if (index == this.current) {
                g.text(this.font, inUse, right - this.font.width(inUse), y + 6, ACCENT);
                right -= this.font.width(inUse) + 8;
            }
            String name = this.font.plainSubstrByWidth(this.presets.get(index).name(),
                right - (this.listX + PAD + 8));
            g.text(this.font, Component.literal(name), this.listX + PAD + 8, y + 6,
                index == this.selected ? ACCENT : TEXT);
        }
        g.disableScissor();
        int view = this.bottom - listTop;
        int content = this.presets.size() * ROW;
        if (content > view) {
            int bar = Math.max(16, view * view / content);
            int barY = listTop + (int) ((view - bar) * this.scroll / (content - view));
            g.fill(this.listX + this.listW - 2, barY, this.listX + this.listW, barY + bar, ACCENT);
        }
    }

    /** What the picked preset is and what just happened: in the panel beside the list, or under it when narrow. */
    private void drawDetails(GuiGraphicsExtractor g) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        int wrap = this.panelW > 0 ? this.panelW - PAD * 2 : this.listW - PAD * 2;
        boolean picked = this.selected >= 0 && this.selected < this.presets.size();
        if (this.panelW > 0 && picked) {
            PresetLibrary.Preset preset = this.presets.get(this.selected);
            add(lines, colors, Component.literal(preset.name()).withStyle(ChatFormatting.BOLD), ACCENT, wrap);
            SettingsCode.Parsed parsed = SettingsCode.parse(preset.code());
            add(lines, colors, Component.translatable("screen.radiante.share.count", parsed == null ? 0
                : parsed.count()), TEXT_DIM, wrap);
            lines.add(FormattedCharSequence.EMPTY);
            colors.add(TEXT);
            add(lines, colors, Component.translatable(this.selected == this.current
                ? "screen.radiante.share.hint.in_use" : "screen.radiante.share.hint.apply"), TEXT, wrap);
        } else if (this.panelW > 0) {
            add(lines, colors, Component.translatable("screen.radiante.share.hint.pick"), TEXT_DIM, wrap);
        }
        if (this.status != null) {
            if (!lines.isEmpty()) {
                lines.add(FormattedCharSequence.EMPTY);
                colors.add(TEXT);
            }
            add(lines, colors, this.status, this.statusColor, wrap);
        }
        if (this.panelW > 0) {
            g.fill(this.panelX, this.top, this.panelX + this.panelW, this.bottom, PANEL);
            int y = this.top + PAD;
            for (int i = 0; i < lines.size() && y + 9 <= this.bottom - 4; i++) {
                g.text(this.font, lines.get(i), this.panelX + PAD, y, colors.get(i));
                y += 10;
            }
        } else if (!lines.isEmpty()) {
            // No room for the panel: the last thing said goes along the foot of the list.
            int y = this.bottom - 4 - lines.size() * 10;
            g.fill(this.listX, y - 4, this.listX + this.listW, this.bottom, PANEL_DARK);
            for (int i = 0; i < lines.size(); i++) {
                g.text(this.font, lines.get(i), this.listX + PAD, y, colors.get(i));
                y += 10;
            }
        }
    }

    private void add(List<FormattedCharSequence> lines, List<Integer> colors, Component text, int color, int wrap) {
        for (FormattedCharSequence line : this.font.split(text, wrap)) {
            lines.add(line);
            colors.add(color);
        }
    }

    private void tooltip(GuiGraphicsExtractor g, Action action, int mouseX, int mouseY) {
        if (action.tooltip() != null && inside(mouseX, mouseY, action.x(), action.y(), action.w(), action.h())) {
            g.setTooltipForNextFrame(this.font.split(action.tooltip(), 200), mouseX, mouseY);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
