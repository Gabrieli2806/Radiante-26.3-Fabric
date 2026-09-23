package com.g2806.radiante.client;

import com.g2806.radiante.client.proxy.vulkan.RendererProxy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Frame generation has to be in place before Minecraft creates its Vulkan instance: NVIDIA Streamline intercepts
 * swapchain and present calls, so Minecraft has to load Streamline's loader instead of the system one.
 */
public class RadiantePreLaunch implements PreLaunchEntrypoint {

    private static final String VULKAN_LIBRARY_PROPERTY = "org.lwjgl.vulkan.libname";

    @Override
    public void onPreLaunch() {
        try {
            RadianteClient.ensureNativeLoaded();
        } catch (RuntimeException e) {
            RadianteClient.LOGGER.warn("Radiante native renderer could not be loaded", e);
            return;
        }

        // Streamline carries both frame generation and Reflex, so either one is a reason to load it.
        if (!com.g2806.radiante.client.option.Options.frameGeneration
            && !com.g2806.radiante.client.option.Options.reflex) {
            return;
        }

        Path streamline = RadianteClient.radianceDir.resolve("streamline");
        Path interposer = streamline.resolve("sl.interposer.dll");
        if (!Files.exists(interposer)) {
            return;
        }

        linkDlssFeatures(RadianteClient.radianceDir.resolve("dlss"), streamline);

        if (!RendererProxy.initFrameGeneration(streamline.toAbsolutePath().toString())) {
            return;
        }

        // Every Vulkan call Minecraft makes now goes through Streamline, which is what lets it pace and present
        // the generated frames. Without frame generation enabled it simply forwards to the driver.
        if (System.getProperty(VULKAN_LIBRARY_PROPERTY) == null) {
            System.setProperty(VULKAN_LIBRARY_PROPERTY, interposer.toAbsolutePath().toString());
        }
        RadianteClient.setStreamlineLoaded(true);
        RadianteClient.LOGGER.info("Streamline loaded, Vulkan library is {}; frame generation support is reported "
            + "once the GPU is known", System.getProperty(VULKAN_LIBRARY_PROPERTY));
    }

    /**
     * NGX is a single instance per process and Streamline initialises it first, with its own folder as the only
     * place it looks for feature DLLs. Unless DLSS and Ray Reconstruction are also reachable from there, enabling
     * frame generation would silently turn DLSS off. Hard links keep it to one copy on disk.
     */
    private static void linkDlssFeatures(Path dlssFolder, Path streamlineFolder) {
        for (String name : new String[] {"nvngx_dlss.dll", "nvngx_dlssd.dll"}) {
            Path source = dlssFolder.resolve(name);
            Path target = streamlineFolder.resolve(name);
            if (!Files.exists(source) || Files.exists(target)) {
                continue;
            }
            try {
                Files.createLink(target, source);
            } catch (IOException | UnsupportedOperationException e) {
                try {
                    Files.copy(source, target);
                } catch (IOException copyFailure) {
                    RadianteClient.LOGGER.warn("Could not make {} visible to Streamline", name, copyFailure);
                }
            }
        }
    }
}
