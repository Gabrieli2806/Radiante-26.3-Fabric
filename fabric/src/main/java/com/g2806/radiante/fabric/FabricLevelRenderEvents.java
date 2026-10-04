package com.g2806.radiante.fabric;

import com.g2806.radiante.api.RadianteGeometry;
import com.g2806.radiante.client.RadianteClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * Fabric API's {@code LevelRenderEvents.COLLECT_SUBMITS} is where mods add their own things to the world - item
 * guides, markers, holograms - by submitting to Minecraft's collector. Fabric fires it from inside the level render,
 * which does not run while the world is traced, so none of it showed. It is fired here instead, with Radiante's
 * collector, and whatever any mod submits is traced.
 *
 * <p>Only this event: the others (after terrain, before the block outline...) hand out render passes on Minecraft's
 * own targets, which mean nothing to a ray tracer.
 */
final class FabricLevelRenderEvents implements RadianteGeometry.Submitter {

    private boolean warned;

    private FabricLevelRenderEvents() {
    }

    static void init() {
        // Unknown mods' markers and guides: seen, but not shadow casters.
        RadianteGeometry.registerSubmitter("radiante:fabric_collect_submits", new FabricLevelRenderEvents(), false);
    }

    @Override
    public void submit(SubmitNodeCollector collector) {
        // With the overlay pass Fabric fires the event itself, from Minecraft's level render.
        if (com.g2806.radiante.client.render.RadianteRenderer.usesOverlayPass()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LevelRenderState levelState = minecraft.gameRenderer.gameRenderState().levelRenderState;
        PoseStack poseStack = new PoseStack();
        LevelRenderContext context = new LevelRenderContext() {
            @Override
            public GameRenderer gameRenderer() {
                return minecraft.gameRenderer;
            }

            @Override
            public LevelRenderer levelRenderer() {
                return minecraft.levelRenderer;
            }

            @Override
            public LevelRenderState levelState() {
                return levelState;
            }

            @Override
            public ChunkSectionsToRender sectionsToRender() {
                // Minecraft's own list of terrain to draw; there is none while Radiante has the terrain.
                return null;
            }

            @Override
            public SubmitNodeCollector submitNodeCollector() {
                return collector;
            }

            @Override
            public PoseStack poseStack() {
                return poseStack;
            }
        };
        try {
            LevelRenderEvents.COLLECT_SUBMITS.invoker().collectSubmits(context);
        } catch (RuntimeException e) {
            // A listener that needs something only Minecraft's own render has: said once, the rest goes on.
            if (!this.warned) {
                this.warned = true;
                RadianteClient.LOGGER.warn("A mod's level render listener failed while the world is traced", e);
            }
        }
    }
}
