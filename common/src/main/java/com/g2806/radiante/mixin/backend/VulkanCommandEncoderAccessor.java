package com.g2806.radiante.mixin.backend;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {

    @Accessor("submitSemaphore")
    long radiante$getSubmitSemaphore();

    @Accessor("currentSubmitIndex")
    long radiante$getCurrentSubmitIndex();
}
