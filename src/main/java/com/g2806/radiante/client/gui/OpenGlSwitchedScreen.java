package com.g2806.radiante.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Confirms a change of graphics API and spells out what it means. Either way the API only changes when the game
 * starts, so the one thing the player has to know is that a restart is needed before anything looks different.
 */
public class OpenGlSwitchedScreen extends Screen {

    private static final int WIDGET_WIDTH = 240;

    private final Screen parent;
    private final boolean toVulkan;

    public OpenGlSwitchedScreen(Screen parent, boolean toVulkan) {
        super(Component.translatable(toVulkan ? "screen.radiante.vulkan.title" : "screen.radiante.opengl.title"));
        this.parent = parent;
        this.toVulkan = toVulkan;
    }

    @Override
    protected void init() {
        LinearLayout layout = LinearLayout.vertical().spacing(8);
        layout.defaultCellSetting().alignHorizontallyCenter();

        layout.addChild(new MultiLineTextWidget(this.title, this.font).setMaxWidth(320).setCentered(true));
        layout.addChild(new MultiLineTextWidget(Component.translatable(
            this.toVulkan ? "screen.radiante.vulkan.message" : "screen.radiante.opengl.message"), this.font)
            .setMaxWidth(320).setCentered(true));
        layout.addChild(Button.builder(Component.translatable("gui.ok"), button -> this.onClose())
            .width(WIDGET_WIDTH).build());

        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);
        layout.setPosition(this.width / 2 - layout.getWidth() / 2, this.height / 2 - layout.getHeight() / 2);
    }


    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
