package com.g2806.radiante.client.compat.distanthorizons;

import com.g2806.radiante.api.RadianteGeometry;
import com.g2806.radiante.client.RadianteClient;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.world.phys.Vec3;

/**
 * Beacon beams past the chunks the game has loaded. Distant Horizons remembers every beacon it has seen and draws
 * their beams over its far terrain itself; while Radiante traces the world it does not draw, so the beams ended
 * where the loaded chunks did. They are read from its records here and drawn as Minecraft's own beam.
 */
final class DhBeacons implements RadianteGeometry.Submitter {

    private static final String NAME = "radiante:distant_horizons_beacons";
    /** How often the records are read, off the render thread: it is a database query. */
    private static final long REFRESH_NANOS = 3_000_000_000L;
    /** Past this distance vanilla widens a beam so it stays a pixel wide; BeaconRenderer.BEAM_SCALE_THRESHOLD. */
    private static final float SCALE_FROM = 96.0f;

    /** x, y, z and colour of each beam in reach, as last read. */
    private volatile List<int[]> beams = List.of();
    private volatile boolean reading;
    private long lastRead;
    private volatile ClientLevel readFor;
    private final PoseStack pose = new PoseStack();

    private DhBeacons() {
    }

    static void init() {
        RadianteGeometry.registerSubmitter(NAME, new DhBeacons(), false);
    }

    @Override
    public void submit(SubmitNodeCollector collector) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        try {
            if (!DhData.isActive()) {
                return;
            }
            Vec3 camera = minecraft.gameRenderer.mainCamera().position();
            refresh(level, camera);

            float time = Math.floorMod(level.getGameTime(), 40)
                + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            for (int[] beam : this.beams) {
                // A beacon in a loaded chunk is the game's own to draw (EntityManager), beam and all.
                if (level.getChunkSource().getChunk(beam[0] >> 4, beam[2] >> 4, false) != null) {
                    continue;
                }
                double dx = beam[0] - camera.x;
                double dz = beam[2] - camera.z;
                float distance = (float) Math.sqrt(dx * dx + dz * dz);
                float scale = Math.max(1.0f, distance / SCALE_FROM);
                this.pose.pushPose();
                this.pose.translate(dx, beam[1] - camera.y, dz);
                BeaconRenderer.submitBeaconBeam(this.pose, collector, BeaconRenderer.BEAM_LOCATION, 1.0f, time, 1,
                    BeaconRenderer.MAX_RENDER_Y, beam[3], BeaconRenderer.SOLID_BEAM_RADIUS * scale,
                    BeaconRenderer.BEAM_GLOW_RADIUS * scale);
                this.pose.popPose();
            }
        } catch (RuntimeException | LinkageError changed) {
            RadianteGeometry.unregisterSubmitter(NAME);
            RadianteClient.LOGGER.warn("Could not read the beacons of Distant Horizons; far beacon beams will not show",
                changed);
        }
    }

    private void refresh(ClientLevel level, Vec3 camera) {
        long now = System.nanoTime();
        if (this.readFor != level) {
            this.readFor = level;
            this.beams = List.of();
            this.lastRead = 0L;
        }
        if (this.reading || this.lastRead != 0L && now - this.lastRead < REFRESH_NANOS) {
            return;
        }
        Object dhLevel = DhData.levelFor(level);
        if (dhLevel == null) {
            return;
        }
        this.lastRead = now;
        this.reading = true;
        int reach = DhData.renderDistanceBlocks();
        int x = (int) Math.floor(camera.x);
        int z = (int) Math.floor(camera.z);
        CompletableFuture.runAsync(() -> {
            try {
                List<int[]> read = DhData.beacons(dhLevel, x - reach, x + reach, z - reach, z + reach);
                if (this.readFor == level) {
                    this.beams = read;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Tried again at the next refresh.
            } finally {
                this.reading = false;
            }
        });
    }
}
