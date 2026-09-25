package com.g2806.radiante.client.compat.distanthorizons;

import com.g2806.radiante.client.render.RadianteRenderer;
import com.g2806.radiante.client.option.Options;
import net.minecraft.client.CloudStatus;
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

    /**
     * Distant Horizons is about to switch vanilla's clouds off (its "override vanilla graphics settings" toggle).
     * The player's setting is kept, to be put back once the toggle is off; see {@link #tick}.
     */
    public static void onCloudsDisabled() {
        CloudStatus current = Minecraft.getInstance().options.cloudStatus().get();
        if (current != CloudStatus.OFF) {
            Options.cloudsBeforeDistantHorizons = current.name();
            Options.overwriteConfig();
        }
    }

    /**
     * Once per client tick. The clouds follow the settings: Distant Horizons turns them off while its override
     * toggle is on, and here they come back as the player had them once that toggle is switched off. Choosing a
     * cloud setting by hand meanwhile drops the note, since the player has decided.
     */
    public static void tick() {
        if (!INSTALLED || failed || Options.cloudsBeforeDistantHorizons.isEmpty()) {
            return;
        }
        try {
            if (!DhData.isConfigLoaded()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            CloudStatus current = minecraft.options.cloudStatus().get();
            if (current != CloudStatus.OFF) {
                Options.cloudsBeforeDistantHorizons = "";
                Options.overwriteConfig();
            } else if (!DhData.overridesVanillaSettings()) {
                minecraft.options.cloudStatus().set(CloudStatus.valueOf(Options.cloudsBeforeDistantHorizons));
                minecraft.options.save();
                Options.cloudsBeforeDistantHorizons = "";
                Options.overwriteConfig();
            }
        } catch (LinkageError | IllegalArgumentException e) {
            Options.cloudsBeforeDistantHorizons = "";
        } catch (RuntimeException e) {
            disable(e);
        }
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
     * Before the renderer and the device go away. Distant Horizons releases its GPU buffers through work it queues
     * for the render thread, which it only runs while drawing; left queued, the buffers outlived the device on
     * quitting and the game died in native code. The builders are stopped too, so none reaches a closed renderer.
     */
    public static void shutdown() {
        if (!INSTALLED || failed) {
            return;
        }
        try {
            if (terrain != null) {
                terrain.close();
                terrain = null;
            }
            DhData.runRenderThreadTasks();
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
