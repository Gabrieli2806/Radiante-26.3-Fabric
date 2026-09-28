package com.g2806.radiante.neoforge;

import com.g2806.radiante.platform.RadiantePlatform;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

public final class NeoForgePlatform implements RadiantePlatform {

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public boolean supportsStreamline() {
        // Off until device creation with Streamline stops crashing here (ROADMAP); a dev run can force it on.
        return "true".equals(System.getenv("RADIANTE_DEV_STREAMLINE"));
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }
}
