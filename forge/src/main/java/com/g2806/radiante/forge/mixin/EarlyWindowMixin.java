package com.g2806.radiante.forge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import java.lang.reflect.Field;
import java.util.function.Supplier;
import net.minecraftforge.fml.loading.ImmediateWindowHandler;
import net.minecraftforge.fml.loading.ImmediateWindowProvider;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Forge only leaves the early loading window out when the saved graphics API is Vulkan; on Default it opens one for
 * OpenGL, while this mod turns Default into Vulkan. A Vulkan surface cannot be made on that window, so once it has
 * been handed over it is dropped and Forge carries on exactly as if it had never opened one: its own no-window
 * provider takes over every later early-window call and creates the window with the backend's hints.
 */
@Mixin(Window.class)
public class EarlyWindowMixin {

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraftforge/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(IILjava/lang/String;JLjava/util/function/Supplier;)J"))
    private long radiante$replaceForVulkan(int width, int height, String title, long monitor,
        Supplier<Object> backend, Operation<Long> original) {
        long window = original.call(width, height, title, monitor, backend);
        if (!(backend.get() instanceof VulkanBackend)) {
            return window;
        }

        try {
            Field providerField = ImmediateWindowHandler.class.getDeclaredField("provider");
            providerField.setAccessible(true);
            ImmediateWindowProvider current = (ImmediateWindowProvider) providerField.get(null);
            if (current == null || "dummyprovider".equals(current.name())) {
                return window;
            }

            ImmediateWindowProvider fallback = ImmediateWindowProvider.getFallbackHandler();
            fallback.updateModuleReads(Window.class.getModule().getLayer());
            providerField.set(null, fallback);
            GLFW.glfwMakeContextCurrent(0L);
            GLFW.glfwDestroyWindow(window);
            return fallback.setupMinecraftWindow(width, height, title, monitor, backend);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not replace Forge's early loading window for Vulkan", e);
        }
    }
}
