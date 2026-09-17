package com.g2806.radiante.mixin.gui;

import com.g2806.radiante.client.gui.RadianteOptionsScreen;
import com.g2806.radiante.client.render.RadianteRenderer;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Puts the Radiante settings at the top of Video Settings, where players look for them. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {

    private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At("HEAD"))
    private void radiante$addSettingsButton(CallbackInfo ci) {
        if (this.list == null) {
            return;
        }

        Screen self = this;
        Button button = Button.builder(RadianteOptionsScreen.TITLE,
            ignored -> this.minecraft.gui.setScreen(new RadianteOptionsScreen(self, this.options))).build();

        // The button stays on the screen when ray tracing cannot run, greyed out and carrying the reason, so the
        // settings do not simply vanish with no explanation of where they went.
        if (!RadianteRenderer.isActive()) {
            // Two very different reasons look the same from here, and blaming the graphics card when the game is
            // simply running on OpenGL would send someone hunting for a driver problem they do not have.
            boolean onOpenGl = com.g2806.radiante.client.option.Options.useOpenGl
                || this.options.preferredGraphicsBackend().get() == PreferredGraphicsApi.OPENGL;
            button.active = false;
            button.setTooltip(Tooltip.create(Component.translatable(
                onOpenGl ? "options.radiante.unavailable.opengl" : "options.radiante.unavailable.hardware")));
        }

        this.list.addBig(button);
    }
}
