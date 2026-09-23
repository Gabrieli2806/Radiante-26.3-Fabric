package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.option.Options;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown once when the ray tracer cannot start on this machine. Saying nothing would leave the player with a game
 * that looks like vanilla and no idea why, so this explains it. There are two reasons it can happen and they need
 * different answers: a graphics card that cannot ray trace at all, or a game that was told to run on OpenGL, where
 * the choice is simply the wrong one and switching back to Vulkan fixes it.
 */
public class UnsupportedHardwareScreen extends Screen {

    private static final int WIDGET_WIDTH = 240;

    private static boolean shown;

    private final Screen parent;
    private final boolean onOpenGl;

    public UnsupportedHardwareScreen(Screen parent) {
        super(Component.translatable(onOpenGl() ? "screen.radiante.unsupported.title.opengl"
            : "screen.radiante.unsupported.title"));
        this.parent = parent;
        this.onOpenGl = onOpenGl();
    }

    /** True when the game is on OpenGL, which is a setting rather than a limit of the machine. */
    private static boolean onOpenGl() {
        if (Options.useOpenGl) {
            return true;
        }
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        return minecraft != null && minecraft.options != null
            && minecraft.options.preferredGraphicsBackend().get() == PreferredGraphicsApi.OPENGL;
    }

    /** True once, so the warning appears on the first menu after startup and never nags again. */
    public static boolean shouldShow() {
        if (shown) {
            return false;
        }
        shown = true;
        return true;
    }

    @Override
    protected void init() {
        LinearLayout layout = LinearLayout.vertical().spacing(8);
        layout.defaultCellSetting().alignHorizontallyCenter();

        layout.addChild(new MultiLineTextWidget(this.title, this.font).setMaxWidth(320).setCentered(true));
        layout.addChild(new MultiLineTextWidget(Component.translatable(this.onOpenGl
            ? "screen.radiante.unsupported.message.opengl" : "screen.radiante.unsupported.message"), this.font)
            .setMaxWidth(320).setCentered(true));

        if (this.onOpenGl) {
            layout.addChild(Button.builder(Component.translatable("screen.radiante.unsupported.vulkan"), button -> {
                this.minecraft.options.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
                Options.useOpenGl = false;
                Options.overwriteConfig();
                this.minecraft.options.save();
                this.minecraft.gui.setScreen(new OpenGlSwitchedScreen(this.parent, true));
            }).width(WIDGET_WIDTH).build());
            layout.addChild(Button.builder(Component.translatable("screen.radiante.unsupported.continue"),
                button -> this.onClose()).width(WIDGET_WIDTH).build());
        } else {
            layout.addChild(Button.builder(Component.translatable("screen.radiante.unsupported.continue"),
                button -> this.onClose()).width(WIDGET_WIDTH).build());
            layout.addChild(Button.builder(Component.translatable("screen.radiante.unsupported.opengl"), button -> {
                Options.useOpenGl = true;
                Options.overwriteConfig();
                this.minecraft.gui.setScreen(new OpenGlSwitchedScreen(this.parent, false));
            }).width(WIDGET_WIDTH).build());
        }

        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);
        layout.setPosition(this.width / 2 - layout.getWidth() / 2, this.height / 2 - layout.getHeight() / 2);
    }


    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
