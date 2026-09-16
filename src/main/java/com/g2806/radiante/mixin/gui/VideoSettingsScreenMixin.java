package com.g2806.radiante.mixin.gui;

import com.g2806.radiante.client.gui.RadianteOptionsScreen;
import com.g2806.radiante.client.render.RadianteRenderer;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
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
        if (this.list == null || !RadianteRenderer.isActive()) {
            return;
        }

        Screen self = this;
        this.list.addBig(Button.builder(RadianteOptionsScreen.TITLE,
            button -> this.minecraft.gui.setScreen(new RadianteOptionsScreen(self, this.options))).build());
    }
}
