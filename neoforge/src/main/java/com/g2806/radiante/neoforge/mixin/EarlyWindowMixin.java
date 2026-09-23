package com.g2806.radiante.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import net.neoforged.fml.loading.EarlyLoadingScreenController;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge hands Minecraft its early loading window whatever the backend, and that window was made for OpenGL; a
 * Vulkan surface cannot be created on it. With Vulkan the early window is taken over and replaced by one made with
 * the backend's own hints, which the caller has already set.
 */
@Mixin(Window.class)
public class EarlyWindowMixin {

    @WrapOperation(method = "createGlfwWindow", at = @At(value = "INVOKE",
        target = "Lnet/neoforged/fml/loading/EarlyLoadingScreenController;takeOverGlfwWindow()J"))
    private static long radiante$replaceForVulkan(EarlyLoadingScreenController screen, Operation<Long> original,
        @Local(argsOnly = true, ordinal = 0) int width, @Local(argsOnly = true, ordinal = 1) int height,
        @Local(argsOnly = true) String title, @Local(argsOnly = true) long monitor,
        @Local(argsOnly = true) GpuBackend backend) {
        long earlyWindow = original.call(screen);
        if (!(backend instanceof VulkanBackend)) {
            return earlyWindow;
        }
        com.g2806.radiante.neoforge.EarlyWindowState.replaced = true;
        GLFW.glfwMakeContextCurrent(0L);
        GLFW.glfwDestroyWindow(earlyWindow);
        return GLFW.glfwCreateWindow(width, height, title, monitor, 0L);
    }
}
