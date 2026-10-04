package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.option.SettingsCode;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Sharing the look of the game: copies the applied settings to the clipboard as a code, or takes a code from the
 * clipboard, says what it would change and applies it once confirmed.
 */
final class ShareSettingsScreen extends Screen {

    private static final int WIDGET_WIDTH = 240;

    private final Screen parent;
    /** A fresh settings screen, made after an import so it shows the imported values. */
    private final Supplier<Screen> settings;
    private Component status;
    private int statusColor;
    private SettingsCode.Parsed pending;

    ShareSettingsScreen(Screen parent, Supplier<Screen> settings) {
        super(Component.translatable("screen.radiante.share.title"));
        this.parent = parent;
        this.settings = settings;
    }

    @Override
    protected void init() {
        LinearLayout layout = LinearLayout.vertical().spacing(8);
        layout.defaultCellSetting().alignHorizontallyCenter();
        layout.addChild(new MultiLineTextWidget(this.title, this.font).setMaxWidth(320).setCentered(true));

        if (this.pending != null) {
            layout.addChild(new MultiLineTextWidget(Component.translatable("screen.radiante.share.confirm",
                this.pending.count()), this.font).setMaxWidth(320).setCentered(true));
            layout.addChild(Button.builder(Component.translatable("screen.radiante.share.apply"), button -> {
                boolean rebuild = SettingsCode.apply(this.pending);
                Screen next = this.settings.get();
                this.minecraft.gui.setScreen(rebuild ? new ApplyingSettingsScreen(next) : next);
            }).width(WIDGET_WIDTH).build());
            layout.addChild(Button.builder(Component.translatable("gui.cancel"), button -> {
                this.pending = null;
                this.status = null;
                this.rebuildWidgets();
            }).width(WIDGET_WIDTH).build());
        } else {
            layout.addChild(new MultiLineTextWidget(Component.translatable("screen.radiante.share.message"), this.font)
                .setMaxWidth(320).setCentered(true));
            layout.addChild(Button.builder(Component.translatable("screen.radiante.share.copy"), button -> {
                this.minecraft.keyboardHandler.setClipboard(SettingsCode.export());
                say("screen.radiante.share.copied", 0xFF8FE3C8);
            }).width(WIDGET_WIDTH).build());
            layout.addChild(Button.builder(Component.translatable("screen.radiante.share.paste"), button -> {
                SettingsCode.Parsed parsed = SettingsCode.parse(this.minecraft.keyboardHandler.getClipboard());
                if (parsed == null) {
                    say("screen.radiante.share.invalid", 0xFFFF8080);
                } else {
                    this.pending = parsed;
                    this.status = parsed.skipped() == 0 ? null
                        : Component.translatable("screen.radiante.share.skipped", parsed.skipped());
                    this.statusColor = 0xFFE8C860;
                    this.rebuildWidgets();
                }
            }).width(WIDGET_WIDTH).build());
            layout.addChild(Button.builder(Component.translatable("gui.back"), button -> this.onClose())
                .width(WIDGET_WIDTH).build());
        }
        if (this.status != null) {
            layout.addChild(new MultiLineTextWidget(this.status.copy().withColor(this.statusColor), this.font)
                .setMaxWidth(320).setCentered(true));
        }

        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);
        layout.setPosition(this.width / 2 - layout.getWidth() / 2, this.height / 2 - layout.getHeight() / 2);
    }

    private void say(String key, int color) {
        this.status = Component.translatable(key);
        this.statusColor = color;
        this.rebuildWidgets();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
