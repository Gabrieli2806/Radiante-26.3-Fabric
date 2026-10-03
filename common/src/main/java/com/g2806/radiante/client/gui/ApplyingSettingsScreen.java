package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.pipeline.Pipeline;
import com.g2806.radiante.client.proxy.vulkan.RendererProxy;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown while the pipeline is rebuilt after a settings change. The rebuild compiles shaders on the render thread and
 * holds it for a few seconds (running it on another thread still left the window unresponsive: the native build and
 * the frame share the device and its queue); this puts a message on screen first so the game does not look hung.
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
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xFF0E1010);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        graphics.centeredText(this.font, this.title, centerX, centerY - 20, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("options.radiante.applying.detail"), centerX,
            centerY - 6, 0xFFB0B8B4);
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
        rebuildKeepingWindowAlive();
        this.minecraft.gui.setScreen(this.next);
    }

    /**
     * The renderer compiles its shaders and pipelines for the new settings, which takes seconds. Left to the next
     * frame it held the render thread inside one native call: no window messages were answered and the system
     * marked the game "not responding". Here it runs on a worker thread while this thread keeps answering the
     * window and puts the progress in the title bar - the screen itself cannot be redrawn meanwhile, since drawing
     * shares the device with the rebuild.
     */
    private void rebuildKeepingWindowAlive() {
        if (!com.g2806.radiante.client.render.RadianteRenderer.isActive()) {
            return;
        }
        // The same large stack the renderer is initialised with: shader includes are resolved recursively.
        Thread worker = new Thread(null, RendererProxy::rebuildPendingPipeline, "Radiante pipeline rebuild",
            512L * 1024L * 1024L);
        worker.start();
        int shown = -1;
        try {
            while (worker.isAlive()) {
                org.lwjgl.sdl.SDLEvents.SDL_PumpEvents();
                int percent = RendererProxy.rebuildProgress();
                if (percent != shown) {
                    shown = percent;
                    // Shaders first, then the passes' pipelines (1000 + percent), then a last stretch with no count.
                    String key = percent >= 1100 ? "options.radiante.applying.window_title.finishing"
                        : percent >= 1000 ? "options.radiante.applying.window_title.pipelines"
                        : "options.radiante.applying.window_title";
                    this.minecraft.getWindow().setTitle(Component.translatable(key, percent % 1000).getString());
                }
                worker.join(15L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            this.minecraft.updateTitle();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
