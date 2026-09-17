package com.g2806.radiante.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Confirms the switch to OpenGL and spells out what it costs: the graphics API changes only when the game starts,
 * and on OpenGL there is no ray tracing at all, so the mod's settings stay closed.
 */
public class OpenGlSwitchedScreen extends Screen {

    private final Screen parent;

    public OpenGlSwitchedScreen(Screen parent) {
        super(Component.translatable("screen.radiante.opengl.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        LinearLayout layout = LinearLayout.vertical().spacing(8);
        layout.addChild(new MultiLineTextWidget(this.title, this.font).setMaxWidth(320).setCentered(true));
        layout.addChild(new MultiLineTextWidget(Component.translatable("screen.radiante.opengl.message"), this.font)
            .setMaxWidth(320).setCentered(true));
        layout.addChild(Button.builder(Component.translatable("gui.ok"), button -> this.onClose())
            .width(220).build());

        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);
        layout.setPosition(this.width / 2 - layout.getWidth() / 2, this.height / 2 - layout.getHeight() / 2);
    }


    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
