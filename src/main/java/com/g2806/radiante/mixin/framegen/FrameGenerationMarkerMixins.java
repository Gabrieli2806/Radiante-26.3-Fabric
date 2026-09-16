package com.g2806.radiante.mixin.framegen;

import com.g2806.radiante.client.render.FrameGeneration;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Frame generation inserts frames between the rendered ones, and Reflex decides when to show them from these
 * markers. Without them DLSS Frame Generation refuses to run.
 */
public class FrameGenerationMarkerMixins {

    @Mixin(Minecraft.class)
    public static class TickMixin {

        @WrapMethod(method = "runTick")
        private void radiante$markSimulation(boolean renderLevel, Operation<Void> original) {
            FrameGeneration.beginClientFrame();
            FrameGeneration.marker(FrameGeneration.SIMULATION_START);
            try {
                original.call(renderLevel);
            } finally {
                FrameGeneration.marker(FrameGeneration.SIMULATION_END);
            }
        }
    }

    @Mixin(GameRenderer.class)
    public static class RenderMixin {

        @WrapMethod(method = "render")
        private void radiante$markRenderSubmit(Operation<Void> original) {
            FrameGeneration.marker(FrameGeneration.RENDER_SUBMIT_START);
            try {
                original.call();
            } finally {
                FrameGeneration.marker(FrameGeneration.RENDER_SUBMIT_END);
            }
        }
    }

    @Mixin(VulkanGpuSurface.class)
    public static class PresentMixin {

        @WrapMethod(method = "present")
        private void radiante$markPresent(Operation<Void> original) {
            FrameGeneration.marker(FrameGeneration.PRESENT_START);
            try {
                original.call();
            } finally {
                FrameGeneration.marker(FrameGeneration.PRESENT_END);
            }
        }
    }
}
