package com.g2806.radiante.fabric;

import com.g2806.radiante.client.render.EntityCollector;
import com.g2806.radiante.platform.RadiantePlatform;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class FabricPlatform implements RadiantePlatform {

    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public EntityCollector createEntityCollector() {
        return new FabricEntityCollector();
    }
}
