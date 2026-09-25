package com.g2806.radiante.client.compat.distanthorizons;

import com.g2806.radiante.client.render.RadianteRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Distant Horizons terrain in the ray tracer. Distant Horizons draws its level-of-detail terrain with its own
 * rasteriser inside vanilla's level render, which never runs while Radiante traces the world; here its data is read
 * instead and turned into geometry the tracer holds like any section (see {@link LodTerrain}).
 *
 * <p>Nothing in this class touches Distant Horizons, so it is safe to call whether or not the mod is installed. The
 * classes that do are only loaded once the mod has been found, and any mismatch with the installed version turns
 * the integration off rather than taking the game down.
 */
public final class DistantHorizonsCompat {

    private static final String API_CLASS = "com.seibel.distanthorizons.api.DhApi";

    private static final boolean INSTALLED = isInstalled();
    private static LodTerrain terrain;
    private static boolean failed;

    private DistantHorizonsCompat() {
    }

    /** Once per traced frame, on the render thread. */
    public static void update(Minecraft minecraft, Vec3 camera) {
        if (!INSTALLED || failed) {
            return;
        }
        try {
            if (terrain == null) {
                terrain = new LodTerrain();
            }
            terrain.update(minecraft, camera);
        } catch (LinkageError | RuntimeException e) {
            disable(e);
        }
    }

    /**
     * How far rays have to reach to find the far terrain, in blocks; zero when there is none. The tracer's usual
     * reach ends well inside what Distant Horizons draws.
     */
    public static float reach() {
        LodTerrain current = terrain;
        return current == null || failed ? 0.0f : current.reach();
    }

    private static void disable(Throwable cause) {
        failed = true;
        RadianteRenderer.LOGGER.error("Distant Horizons terrain turned off: this version of Distant Horizons is "
            + "not one Radiante understands", cause);
        if (terrain != null) {
            terrain.close();
            terrain = null;
        }
    }

    private static boolean isInstalled() {
        try {
            Class.forName(API_CLASS, false, DistantHorizonsCompat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
