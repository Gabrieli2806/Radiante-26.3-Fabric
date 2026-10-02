package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.download.UpscalerDownloads;
import com.g2806.radiante.client.download.UpscalerDownloads.Component;
import com.g2806.radiante.client.download.UpscalerDownloads.Progress;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;

/**
 * Asks before downloading DLSS or XeSS (see {@link UpscalerDownloads}), shows the download with a progress bar, and
 * confirms deleting one. On the title screen it offers the upscaler that suits the GPU; from the settings it is
 * reached by choosing one that is not downloaded yet.
 */
public class UpscalerDownloadScreen extends Screen {

    public enum Mode {
        /** Offered on startup: download, not now, or never ask again. */
        OFFER,
        /** Chosen in the settings: download or cancel. */
        CONFIRM,
        /** Delete or cancel. */
        DELETE,
        /** Installed or removed: the only way on is restarting the game. */
        RESTART
    }

    private static final int WIDGET_WIDTH = 240;

    private final Screen parent;
    private final Component component;
    private final Mode mode;
    private Progress progress;
    private boolean layoutForProgress;

    public UpscalerDownloadScreen(Screen parent, Component component, Mode mode) {
        super(net.minecraft.network.chat.Component.translatable("screen.radiante.download." + component.id + ".title"));
        this.parent = parent;
        this.component = component;
        this.mode = mode;
        this.progress = mode == Mode.RESTART ? null : UpscalerDownloads.running(component);
    }

    private static net.minecraft.network.chat.Component text(String key, Object... args) {
        return net.minecraft.network.chat.Component.translatable(key, args);
    }

    public boolean isRestartScreen() {
        return this.mode == Mode.RESTART;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return this.mode != Mode.RESTART;
    }

    @Override
    protected void init() {
        this.layoutForProgress = this.progress != null;
        LinearLayout layout = LinearLayout.vertical().spacing(8);
        layout.defaultCellSetting().alignHorizontallyCenter();
        layout.addChild(new MultiLineTextWidget(this.title, this.font).setMaxWidth(320).setCentered(true));

        if (this.mode == Mode.RESTART) {
            // The renderer sets DLSS and XeSS up when the game starts; carrying on now could load half of a change.
            layout.addChild(new MultiLineTextWidget(text("screen.radiante.download.restart.message"), this.font)
                .setMaxWidth(320).setCentered(true));
            layout.addChild(Button.builder(text("options.radiante.restart.quit"), button -> this.minecraft.stop())
                .width(WIDGET_WIDTH).build());
        } else         if (this.progress != null) {
            // Room for the bar and its text, drawn in extractRenderState.
            layout.addChild(new MultiLineTextWidget(net.minecraft.network.chat.Component.empty(), this.font)
                .setMaxWidth(320));
            layout.addChild(new MultiLineTextWidget(net.minecraft.network.chat.Component.literal(" \n \n "), this.font));
            boolean done = this.progress.finished;
            // A finished download is followed by the restart screen (RadianteClient); a failed one just closes.
            layout.addChild(Button.builder(text(done ? "gui.done" : "screen.radiante.download.background"),
                button -> this.onClose()).width(WIDGET_WIDTH).build());
        } else if (this.mode == Mode.DELETE) {
            layout.addChild(new MultiLineTextWidget(text("screen.radiante.download.delete.message"), this.font)
                .setMaxWidth(320).setCentered(true));
            layout.addChild(Button.builder(text("screen.radiante.download.delete"), button -> {
                UpscalerDownloads.delete(this.component);
                this.minecraft.gui.setScreen(new UpscalerDownloadScreen(this.parent, this.component, Mode.RESTART));
            }).width(WIDGET_WIDTH).build());
            layout.addChild(Button.builder(text("gui.cancel"), button -> this.onClose()).width(WIDGET_WIDTH).build());
        } else {
            long megabytes = Math.round(this.component.downloadBytes / 1_000_000.0);
            layout.addChild(new MultiLineTextWidget(text("screen.radiante.download." + this.component.id + ".message",
                megabytes), this.font).setMaxWidth(320).setCentered(true));
            layout.addChild(Button.builder(text("screen.radiante.download.start", megabytes), button -> {
                this.progress = UpscalerDownloads.start(this.component);
                this.rebuildWidgets();
            }).width(WIDGET_WIDTH).build());
            if (this.mode == Mode.OFFER) {
                layout.addChild(Button.builder(text("screen.radiante.download.later"), button -> this.onClose())
                    .width(WIDGET_WIDTH).build());
                layout.addChild(Button.builder(text("screen.radiante.download.never"), button -> {
                    UpscalerDownloads.setDeclined(this.component, true);
                    this.onClose();
                }).width(WIDGET_WIDTH).build());
            } else {
                layout.addChild(Button.builder(text("gui.cancel"), button -> this.onClose())
                    .width(WIDGET_WIDTH).build());
            }
        }

        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);
        layout.setPosition(this.width / 2 - layout.getWidth() / 2, this.height / 2 - layout.getHeight() / 2);
    }

    @Override
    public void tick() {
        // The button changes from "continue in the background" to "done" when the download ends.
        if (this.progress != null && this.progress.finished && this.layoutForProgress) {
            this.layoutForProgress = false;
            this.rebuildWidgets();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        Progress current = this.progress;
        if (current == null) {
            return;
        }
        int centerX = this.width / 2;
        int y = this.height / 2 - 8;
        net.minecraft.network.chat.Component status;
        int color = 0xFFB0B8B4;
        if (current.error != null) {
            status = text("screen.radiante.download.failed", current.error);
            color = 0xFFFF8080;
        } else if (current.finished) {
            status = text("screen.radiante.download.finished");
            color = 0xFF8FE3C8;
        } else {
            status = text("screen.radiante.download.progress", current.done / 1_000_000, current.total / 1_000_000,
                Math.round(current.fraction() * 100.0f));
        }
        graphics.centeredText(this.font, status, centerX, y - 14, color);
        int barWidth = Math.min(240, this.width - 40);
        int x = centerX - barWidth / 2;
        graphics.fill(x - 1, y - 1, x + barWidth + 1, y + 7, 0xFFFFFFFF);
        graphics.fill(x, y, x + barWidth, y + 6, 0xFF000000);
        graphics.fill(x, y, x + Math.round(barWidth * current.fraction()), y + 6,
            current.error != null ? 0xFFE06060 : 0xFF8FE3C8);
    }

    @Override
    public void onClose() {
        if (this.mode == Mode.RESTART) {
            return;
        }
        this.minecraft.gui.setScreen(this.parent);
    }
}
