package com.g2806.radiante.forge;

import com.g2806.radiante.platform.RadiantePlatform;
import java.nio.file.Path;
import net.minecraftforge.fml.loading.FMLPaths;

public final class ForgePlatform implements RadiantePlatform {

    @Override
    public String loaderName() {
        return "Forge";
    }

    @Override
    public boolean supportsStreamline() {
        return false;
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }
}
