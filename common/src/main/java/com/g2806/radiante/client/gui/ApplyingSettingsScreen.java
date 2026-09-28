package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.pipeline.Pipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown while the pipeline is rebuilt after a settings change. The rebuild compiles shaders on the render thread and
 * holds it for a few seconds; without this the game simply stopped answering with the settings still on screen, as if
 * it had hung. The message is drawn first, then the rebuild runs, then the next screen opens.
 */
final class ApplyingSettingsScreen extends Screen {

    /** Frames drawn before rebuilding, so the message is on screen when the render thread stops. */
    private static final int FRAMES_BEFORE_BUILD = 2;

    private final Screen next;
    private int framesShown;
    private boolean built;

    ApplyingSettingsScreen(Screen next) {
        super(Component.translatable("options.radiante.applying"));
        this.next = next;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        graphics.centeredText(this.font, this.title, centerX, centerY - 20, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("options.radiante.applying.detail"), centerX,
            centerY - 6, 0xFFB0B8B4);
        // A bar in Minecraft's loading colours; the rebuild itself cannot report progress.
        int barWidth = Math.min(240, this.width - 40);
        int x = centerX - barWidth / 2;
        int y = centerY + 12;
        graphics.fill(x - 1, y - 1, x + barWidth + 1, y + 7, 0xFFFFFFFF);
        graphics.fill(x, y, x + barWidth, y + 6, 0xFF000000);
        int filled = Math.min(barWidth, barWidth * (this.framesShown + 1) / (FRAMES_BEFORE_BUILD + 2));
        graphics.fill(x, y, x + filled, y + 6, 0xFF8FE3C8);
        this.framesShown++;
    }

    @Override
    public void tick() {
        if (this.built || this.framesShown < FRAMES_BEFORE_BUILD) {
            return;
        }
        this.built = true;
        Pipeline.build();
        this.minecraft.gui.setScreen(this.next);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
