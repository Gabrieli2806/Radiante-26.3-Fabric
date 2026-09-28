package com.g2806.radiante.client.gui;

import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown after leaving the settings with a change that only takes effect once the game is started again (HDR output,
 * frame generation, Reflex): says which, and offers to close the game now or carry on.
 */
final class RestartRequiredScreen extends Screen {

    private final Screen next;
    private final List<Component> changes;

    RestartRequiredScreen(Screen next, List<Component> changes) {
        super(Component.translatable("options.radiante.restart.title"));
        this.next = next;
        this.changes = changes;
    }

    @Override
    protected void init() {
        LinearLayout layout = LinearLayout.vertical().spacing(8);
        layout.defaultCellSetting().alignHorizontallyCenter();
        layout.addChild(new MultiLineTextWidget(this.title, this.font).setMaxWidth(320).setCentered(true));
        layout.addChild(new MultiLineTextWidget(Component.translatable("options.radiante.restart.message"), this.font)
            .setMaxWidth(320).setCentered(true));
        for (Component change : this.changes) {
            layout.addChild(new MultiLineTextWidget(Component.literal("• ").append(change), this.font)
                .setMaxWidth(320).setCentered(true));
        }
        layout.addChild(Button.builder(Component.translatable("options.radiante.restart.quit"),
            button -> this.minecraft.stop()).width(240).build());
        layout.addChild(Button.builder(Component.translatable("options.radiante.restart.later"),
            button -> this.onClose()).width(240).build());
        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);
        layout.setPosition(this.width / 2 - layout.getWidth() / 2, this.height / 2 - layout.getHeight() / 2);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.next);
    }
}
