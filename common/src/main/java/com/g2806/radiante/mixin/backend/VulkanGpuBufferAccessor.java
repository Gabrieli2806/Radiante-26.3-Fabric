package com.g2806.radiante.mixin.backend;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Minecraft refuses to map a buffer for reading unless it was asked for that when it was created, and the staging
 * buffers glyphs travel in never are. The allocation behind them is host visible all the same, so reaching it
 * directly is the only way to copy what a glyph upload carries into the renderer's own texture.
 */
@Mixin(VulkanGpuBuffer.Direct.class)
public interface VulkanGpuBufferAccessor {

    @Accessor("vmaAllocation")
    long radiante$vmaAllocation();

    @Accessor("device")
    VulkanDevice radiante$device();
}
