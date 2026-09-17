package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.option.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown once when the ray tracer cannot start on this machine. Saying nothing would leave the player with a game
 * that looks like vanilla and no idea why, so this explains it and offers the one thing that helps: dropping to
 * OpenGL, which runs without this renderer at all.
 */
public class UnsupportedHardwareScreen extends Screen {

    private static boolean shown;

    private final Screen parent;

    public UnsupportedHardwareScreen(Screen parent) {
        super(Component.translatable("screen.radiante.unsupported.title"));
        this.parent = parent;
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
        layout.addChild(new MultiLineTextWidget(this.title, this.font).setMaxWidth(320).setCentered(true));
        layout.addChild(new MultiLineTextWidget(
            Component.translatable("screen.radiante.unsupported.message"), this.font).setMaxWidth(320)
            .setCentered(true));

        layout.addChild(Button.builder(Component.translatable("screen.radiante.unsupported.continue"),
            button -> this.onClose()).width(220).build());

        layout.addChild(Button.builder(Component.translatable("screen.radiante.unsupported.opengl"), button -> {
            Options.useOpenGl = true;
            Options.overwriteConfig();
            this.minecraft.gui.setScreen(new OpenGlSwitchedScreen(this.parent));
        }).width(220).build());

        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);
        layout.setPosition(this.width / 2 - layout.getWidth() / 2, this.height / 2 - layout.getHeight() / 2);
    }


    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
