package com.g2806.radiante.fabric;

import com.g2806.radiante.client.StreamlineBootstrap;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Runs before Minecraft starts, which is early enough to load Streamline ahead of the Vulkan instance. */
public final class RadianteFabricPreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        StreamlineBootstrap.run();
    }
}
