package com.g2806.radiante.client.compat.journeymap;

import com.g2806.radiante.api.RadianteGeometry;
import com.g2806.radiante.client.RadianteClient;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * JourneyMap submits its waypoint beams from inside Minecraft's level render, which does not run while the world is
 * traced, so waypoints had no beam. Its handler takes any {@link SubmitNodeCollector}, so it is simply given
 * Radiante's. The minimap, the full screen map and the waypoint labels are drawn on the HUD and need nothing.
 *
 * <p>Looked up by name at run time: JourneyMap is closed source and need not be installed.
 */
public final class JourneyMapCompat implements RadianteGeometry.Submitter {

    private static final String NAME = "radiante:journeymap";
    private static final String HANDLER = "journeymap.client.event.handlers.WaypointBeaconHandler";

    private Object handler;
    private Method onRenderWorld;

    private JourneyMapCompat() {
    }

    /** Registers the bridge when JourneyMap is installed. */
    public static void init() {
        try {
            Class.forName(HANDLER, false, JourneyMapCompat.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError notInstalled) {
            return;
        }
        // Beams are light, not objects: they cast no shadow.
        RadianteGeometry.registerSubmitter(NAME, new JourneyMapCompat(), false);
        RadianteClient.LOGGER.info("JourneyMap found: its waypoint beams are traced by Radiante");
    }

    @Override
    public void submit(SubmitNodeCollector collector) {
        // With the overlay pass JourneyMap submits its beams to Minecraft itself, as it does without Radiante.
        if (com.g2806.radiante.client.render.RadianteRenderer.usesOverlayPass()) {
            return;
        }
        try {
            if (this.handler == null) {
                Class<?> type = Class.forName(HANDLER, true, JourneyMapCompat.class.getClassLoader());
                this.onRenderWorld = type.getMethod("onRenderWorld", SubmitNodeCollector.class);
                this.handler = type.getConstructor().newInstance();
            }
            this.onRenderWorld.invoke(this.handler, collector);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError changed) {
            RadianteGeometry.unregisterSubmitter(NAME);
            RadianteClient.LOGGER.warn("JourneyMap is not the version Radiante knows; its waypoint beams will not "
                + "show with ray tracing on", changed);
        }
    }
}
