package com.g2806.radiante.mixin.backend;

import com.g2806.radiante.client.render.RadianteRenderer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft's queue operations take the same lock the native renderer uses for its submissions. */
public class VulkanQueueLockMixins {

    @Mixin(VulkanQueue.Submission.class)
    public static class SubmissionMixin {

        @WrapMethod(method = "close")
        private void radiante$lockedClose(Operation<Void> original) {
            RadianteRenderer.lockQueue();
            try {
                original.call();
            } finally {
                RadianteRenderer.unlockQueue();
            }
        }
    }

    @Mixin(VulkanQueue.class)
    public static class QueueMixin {

        @WrapMethod(method = "waitIdle")
        private void radiante$lockedWaitIdle(Operation<Void> original) {
            RadianteRenderer.lockQueue();
            try {
                original.call();
            } finally {
                RadianteRenderer.unlockQueue();
            }
        }
    }

    @Mixin(VulkanGpuSurface.class)
    public static class SurfaceMixin {

        @WrapMethod(method = "present")
        private void radiante$lockedPresent(Operation<Void> original) {
            RadianteRenderer.lockQueue();
            try {
                original.call();
            } finally {
                RadianteRenderer.unlockQueue();
            }
        }
    }
}
