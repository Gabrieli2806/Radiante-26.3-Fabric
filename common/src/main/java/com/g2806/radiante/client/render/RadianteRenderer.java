package com.g2806.radiante.client.render;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.pipeline.Pipeline;
import com.g2806.radiante.client.proxy.vulkan.BufferProxy;
import com.g2806.radiante.client.proxy.vulkan.RendererProxy;
import com.g2806.radiante.client.proxy.vulkan.TextureProxy;
import com.g2806.radiante.client.proxy.world.PlayerProxy;
import com.g2806.radiante.mixin.backend.VulkanCommandEncoderAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;

/** Drives the native renderer: device setup, per-frame world rendering and the blit into Minecraft's target. */
public final class RadianteRenderer {

    public static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_FRAME_COMMAND_BUFFERS = 8;

    private static volatile boolean active;
    private static VkDevice vkDevice;
    private static final long[] commandBufferHandles = new long[MAX_FRAME_COMMAND_BUFFERS];
    private static final Matrix4f glintMatrix = new Matrix4f();

    private RadianteRenderer() {
    }

    public static boolean isActive() {
        return active;
    }

    /** True when the ray tracer both works here and the player has it switched on. */
    public static boolean isRayTracingEnabled() {
        return active && Options.rayTracingEnabled;
    }

    /** Why ray tracing is unavailable on this machine, or null when it is available. */
    public static String unsupportedReason() {
        return unsupportedReason;
    }

    private static String unsupportedReason;

    public static void lockQueue() {
        if (active) {
            RendererProxy.lockQueue();
        }
    }

    public static void unlockQueue() {
        if (active) {
            RendererProxy.unlockQueue();
        }
    }

    public static void onDeviceCreated(VulkanDevice device) {
        RadianteClient.ensureNativeLoaded();
        vkDevice = device.vkDevice();

        long instance = device.instance().vkInstance().address();
        long physicalDevice = vkDevice.getPhysicalDevice().address();
        long mainQueue = device.graphicsQueue().vkQueue().address();
        int mainQueueFamily = device.graphicsQueue().queueFamilyIndex();
        long secondaryQueue = device.computeQueue().vkQueue().address();
        int secondaryQueueFamily = device.computeQueue().queueFamilyIndex();

        // The native renderer compiles shaders recursively, so give the initialisation a large stack.
        boolean[] ok = new boolean[1];
        Thread initThread = new Thread(null, () -> {
            ok[0] = RendererProxy.initRenderer(instance, physicalDevice, vkDevice.address(), mainQueue,
                mainQueueFamily, secondaryQueue, secondaryQueueFamily, 854, 480);
            if (ok[0]) {
                Pipeline.collectNativeModules();
            }
        }, "Radiante Init", 512L * 1024L * 1024L);
        initThread.start();
        try {
            initThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        if (!ok[0]) {
            LOGGER.error("Radiante native renderer failed to initialise; ray tracing stays off");
            unsupportedReason = "options.radiante.unsupported.reason";
            return;
        }

        active = true;
        FrameGeneration.setGeneratedFrames(Options.frameGeneration ? Options.generatedFrames : 0);
        FrameGeneration.applyReflex();
        reserveFallbackTexture();
        Pipeline.loadPipeline();
        Pipeline.build();
        LOGGER.info("Radiante renderer initialised on Minecraft's Vulkan device");
    }

    /**
     * Texture id 0 is what geometry without a texture points at, so it gets a 1x1 white pixel.
     */
    private static void reserveFallbackTexture() {
        int id = TextureProxy.generateTextureId();
        TextureProxy.prepareImage(id, 1, 1, 1, VulkanConst.toVk(com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM));
        ByteBuffer white = MemoryUtil.memAlloc(4);
        white.put(0, (byte) 0xFF).put(1, (byte) 0xFF).put(2, (byte) 0xFF).put(3, (byte) 0xFF);
        TextureProxy.queueUpload(MemoryUtil.memAddress(white), 4, 1, id, 0, 0, 0, 0, 1, 1, 0);
        RendererProxy.flushTextureUploads();
        MemoryUtil.memFree(white);
    }

    public static void close() {
        if (active) {
            active = false;
            ChunkManager.shutdown();
            RendererProxy.close();
        }
    }

    /** Replaces {@code GameRenderer.renderLevel} while the renderer is active. */
    /** Frames of the world this renderer has drawn since the level was opened. See {@link #levelFrames()}. */
    private static final java.util.concurrent.atomic.AtomicInteger LEVEL_FRAMES =
        new java.util.concurrent.atomic.AtomicInteger();

    /** How many frames of the world have been traced since the level loaded; zero while none has. */
    public static int levelFrames() {
        return LEVEL_FRAMES.get();
    }

    /** Called when a level is opened or left, so the count means "of this level". */
    public static void resetLevelFrames() {
        LEVEL_FRAMES.set(0);
    }

    public static void renderLevel(GameRenderer gameRenderer, LevelRenderState levelRenderState) {
        Minecraft minecraft = Minecraft.getInstance();
        CameraRenderState cameraState = levelRenderState.cameraRenderState;
        RenderTarget mainTarget = gameRenderer.mainRenderTarget();
        GpuTexture colorTexture = mainTarget.getColorTexture();
        if (colorTexture == null) {
            return;
        }

        LEVEL_FRAMES.incrementAndGet();
        DevProfiler.begin();
        PlayerProxy.setCameraPos(cameraState.pos.x(), cameraState.pos.y(), cameraState.pos.z());
        RendererProxy.shouldRenderWorld(true);

        updateUniforms(minecraft, gameRenderer, levelRenderState);
        DevProfiler.mark(0);
        BufferProxy.updateMapping();
        DevProfiler.mark(1);

        if (minecraft.level != null && minecraft.levelExtractor != null) {
            SectionTrackerHolder.update(minecraft, cameraState.pos);
        }
        DevProfiler.mark(2);

        keepOcclusionGraphFed(minecraft, levelRenderState);
        DevProfiler.mark(3);

        EmissionTiles.registerIfNeeded(minecraft);
        ChunkManager.applyImportantUploads();
        EntityManager.render(minecraft, levelRenderState);
        DevProfiler.mark(4);

        // Vanilla's "loading terrain" screen waits for the section under the player to be compiled.
        Runnable compiledCallback = levelRenderState.playerCompiledSectionCallback;
        if (compiledCallback != null && ChunkManager.isSectionReady(cameraState.blockPos)) {
            compiledCallback.run();
        }

        long image = ((VulkanGpuTexture) colorTexture).vkImage();
        int format = VulkanConst.toVk(colorTexture.getFormat());
        int count = RendererProxy.renderFrame(image, mainTarget.width, mainTarget.height, format,
            commandBufferHandles);
        DevProfiler.mark(5);
        if (count <= 0) {
            DevProfiler.endFrame();
            return;
        }

        VulkanCommandEncoder encoder = (VulkanCommandEncoder) ((FrontendCommandEncoder) RenderSystem.getDevice()
            .createCommandEncoder()).backend();
        for (int i = 0; i < count; i++) {
            encoder.execute(new VkCommandBuffer(commandBufferHandles[i], vkDevice));
        }

        VulkanCommandEncoderAccessor accessor = (VulkanCommandEncoderAccessor) encoder;
        RendererProxy.markSubmitted(accessor.radiante$getSubmitSemaphore(), accessor.radiante$getCurrentSubmitIndex());
        DevProfiler.mark(6);
        DevProfiler.endFrame();
    }

    private static void updateUniforms(Minecraft minecraft, GameRenderer gameRenderer,
        LevelRenderState levelRenderState) {
        CameraRenderState cameraState = levelRenderState.cameraRenderState;
        FogData fog = cameraState.fogData;

        Matrix4f view = toRendererView(cameraState.viewRotationMatrix);
        // Primary rays are generated from the effected view, which is the matrix meant to carry camera effects.
        Matrix4f effectedView =
            toRendererView(cameraShake(minecraft, cameraState).mul(cameraState.viewRotationMatrix));
        Matrix4f projection = new Matrix4f(cameraState.projectionMatrix);

        int skyType = skyTypeOf(levelRenderState.skyRenderState.skybox);
        // Environmental fog only applies inside water, lava or powder snow; elsewhere it is the
        // render distance haze that matters.
        boolean environmental = cameraState.fogType == FogType.WATER || cameraState.fogType == FogType.LAVA
            || cameraState.fogType == FogType.POWDER_SNOW;
        float fogStart = environmental ? fog.environmentalStart : fog.renderDistanceStart;
        float fogEnd = environmental ? fog.environmentalEnd : fog.renderDistanceEnd;

        BufferProxy.updateWorldUniform(new BufferProxy.WorldUniform(view, effectedView, projection,
            glintTextureMatrix(minecraft), dayFraction(levelRenderState),
            TextureTracker.idOf(gameRenderer.overlayTexture().getTextureView().texture()),
            cameraState.isFirstPerson, fogStart, fogEnd, new Vector4f(fog.color), skyType,
            TextureTracker.idOf(AbstractEndPortalRenderer.END_SKY_LOCATION),
            TextureTracker.idOf(AbstractEndPortalRenderer.END_PORTAL_LOCATION),
            TextureTracker.idOf(gameRenderer.levelLightmap().texture()), handFovScale(cameraState, projection),
            levelRenderState.entityRenderStates.stream().anyMatch(net.minecraft.client.renderer.entity.state.EntityRenderState::appearsGlowing),
            Options.blockLightSampling && Options.collectChunkEmission,
            Options.heldItemLight ? HeldLight.position(minecraft, cameraState.pos,
                minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false)) : new Vector4f(0.0f),
            Options.heldItemLight ? HeldLight.color(minecraft) : new Vector4f(0.0f),
            Options.parallaxTransparentEdges, Options.dayBrightness / 100.0f, Options.nightBrightness / 100.0f,
            Options.emissionBrightness / 100.0f));

        SkyRenderState sky = levelRenderState.skyRenderState;
        Vector3f skyColor = sky.skyColor == null ? new Vector3f(0.5f, 0.6f, 1.0f) : new Vector3f(sky.skyColor);
        Vector4f horizonColor = sky.sunriseAndSunsetColor == null
            ? new Vector4f(0.0f)
            : new Vector4f(sky.sunriseAndSunsetColor);
        Matrix4f celestial = new Matrix4f()
            .rotateX(Options.vanillaSunPath ? 0.0f : (float) Math.toRadians(SUN_PATH_SOUTH_TILT_DEGREES))
            .rotateY((float) Math.toRadians(-90.0))
            .rotateX(sky.sunAngle);
        Vector3f sunDirection = celestial.transformDirection(new Vector3f(0.0f, 1.0f, 0.0f)).normalize();
        // Vanilla's sun and moon quads lie along the transform's local x and z, so their edges stay lined up with
        // the path; x is the axis the sky turns around.
        Vector3f celestialAxis = celestial.transformDirection(new Vector3f(1.0f, 0.0f, 0.0f)).normalize();


        TextureAtlas celestials = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.CELESTIALS);
        int celestialsId = TextureTracker.idOf(celestials.getTexture());

        BufferProxy.updateSkyUniform(new BufferProxy.SkyUniform(skyColor, horizonColor, sunDirection, skyType,
            Mth.sin(sky.sunAngle) >= 0.0f && horizonColor.w() > 0.0f, sky.shouldRenderDarkDisc,
            cameraState.entityRenderState.doesMobEffectBlockSky, submersionTypeOf(cameraState.fogType),
            sky.moonPhase.ordinal(), 1.0f - sky.rainBrightness, celestialsId, celestialsId,
            spriteRect(celestials, SUN_SPRITE),
            spriteRect(celestials, moonSprite(sky.moonPhase)),
            BiomeAmbiance.update(minecraft, cameraState.pos, skyType, 1.0f - sky.rainBrightness, fog.color),
            gameRenderer.gameRenderState().lightmapRenderState.nightVisionEffectIntensity,
            new Vector4f(celestialAxis, Options.vanillaCelestialOrientation ? 1.0f : 0.0f)));
    }

    /** How far the custom sun path leans south of vanilla's overhead arc. */
    private static final double SUN_PATH_SOUTH_TILT_DEGREES = 10.0;

    private static final Identifier SUN_SPRITE =Identifier.withDefaultNamespace("sun");

    /** The eight phases are separate sprites named after the phase, not tiles of a fixed grid. */
    private static Identifier moonSprite(MoonPhase phase) {
        return Identifier.withDefaultNamespace("moon/" + phase.getSerializedName());
    }

    /**
     * Where a sprite sits in its atlas, as (u0, v0, u1, v1). A missing sprite collapses to a zero rectangle, which
     * samples one texel rather than stretching some unrelated neighbour across the whole sky.
     */
    private static Vector4f spriteRect(TextureAtlas atlas, Identifier sprite) {
        TextureAtlasSprite found = atlas.getSprite(sprite);
        if (found == null) {
            return new Vector4f(0.0f);
        }
        return new Vector4f(found.getU0(), found.getV0(), found.getU1(), found.getV1());
    }

    /**
     * The shaders reconstruct view rays looking down the opposite Z axis to the render pearl camera, so the traced
     * view faced backwards. Turning the view half a revolution around its up axis faces it forward again.
     */
    /**
     * How much wider or narrower the hand's field of view is than the world's. Minecraft draws the first person
     * hand in a pass of its own with a fixed 70 degrees ({@code Camera.calculateHudFov}), so changing the FOV
     * setting opens up the world without touching the hand; a path tracer that shoots one ray per pixel has to
     * widen the hand's ray instead. {@code projection.m11()} is {@code 1 / tan(fov / 2)}, so this is the ratio of
     * the two half angle tangents and comes out at 1 when the setting is left at 70.
     */
    private static float handFovScale(CameraRenderState cameraState, Matrix4f projection) {
        float worldTanTerm = projection.m11();
        if (!Float.isFinite(worldTanTerm) || worldTanTerm <= 0.0f || cameraState.hudFov <= 0.0f) {
            return 1.0f;
        }
        return (float) Math.tan(Math.toRadians(cameraState.hudFov) * 0.5) * worldTanTerm;
    }

    private static Matrix4f toRendererView(Matrix4f viewRotation) {
        return new Matrix4f().scaling(-1.0f, 1.0f, -1.0f).mul(viewRotation);
    }

    /**
     * The tilt when the player takes a hit and the sway while walking, exactly as vanilla computes them. Vanilla
     * multiplies this onto the camera transform; folding it into the projection instead skews the frustum and the
     * shake comes out far stronger than it should.
     */
    private static Matrix4f cameraShake(Minecraft minecraft, CameraRenderState cameraState) {
        CameraEntityRenderState entity = cameraState.entityRenderState;
        OptionsRenderState options = minecraft.gameRenderer.gameRenderState().optionsRenderState;
        Matrix4f shake = new Matrix4f();

        if (entity.isLiving) {
            if (entity.isDeadOrDying) {
                float duration = Math.min(entity.deathTime, 20.0f);
                shake.rotate((float) Math.toRadians(40.0f - 8000.0f / (duration + 200.0f)), 0.0f, 0.0f, 1.0f);
            }

            if (entity.hurtTime >= 0.0f && entity.hurtDuration > 0) {
                float hurt = entity.hurtTime / entity.hurtDuration;
                hurt = Mth.sin(hurt * hurt * hurt * hurt * (float) Math.PI);
                float direction = entity.hurtDir;
                float tilt = (float) (-hurt * 14.0 * options.damageTiltStrength);
                shake.rotate((float) Math.toRadians(-direction), 0.0f, 1.0f, 0.0f);
                shake.rotate((float) Math.toRadians(tilt), 0.0f, 0.0f, 1.0f);
                shake.rotate((float) Math.toRadians(direction), 0.0f, 1.0f, 0.0f);
            }
        }

        if (options.bobView && entity.isPlayer) {
            float walk = entity.backwardsInterpolatedWalkDistance;
            float bob = entity.bob;
            shake.translate(Mth.sin(walk * (float) Math.PI) * bob * 0.5f,
                -Math.abs(Mth.cos(walk * (float) Math.PI) * bob), 0.0f);
            shake.rotate((float) Math.toRadians(Mth.sin(walk * (float) Math.PI) * bob * 3.0f), 0.0f, 0.0f, 1.0f);
            shake.rotate((float) Math.toRadians(Math.abs(Mth.cos(walk * (float) Math.PI - 0.2f) * bob) * 5.0f),
                1.0f, 0.0f, 0.0f);
        }
        return shake;
    }

    /** The shaders use the same order as vanilla: none, overworld, end. */
    private static int skyTypeOf(DimensionType.Skybox skybox) {
        return skybox.ordinal();
    }

    /** The shaders use the legacy camera submersion order: lava, water, powder snow, none. */
    private static int submersionTypeOf(FogType fogType) {
        return switch (fogType) {
            case LAVA -> 0;
            case WATER -> 1;
            case POWDER_SNOW -> 2;
            default -> 3;
        };
    }

    /**
     * What the shaders mean by game time, and it is not a tick count: vanilla's GameTime uniform is how far the
     * Minecraft day has gone, from 0 to 1, wrapping every 24000 ticks. Handing over raw ticks instead runs every
     * animation that reads it - the end portal layers, the clouds, the water - some twenty four thousand times too
     * fast, and the number grows until single precision can no longer hold the fraction, which is what made the
     * portal jump about instead of drifting.
     */
    private static float dayFraction(LevelRenderState levelRenderState) {
        return ((float) (levelRenderState.gameTime % 24000L) + levelRenderState.worldPartialTicks) / 24000.0f;
    }

    /** Mirrors vanilla's armor glint texture animation, which the shaders sample the glint layer with. */
    private static Matrix4f glintTextureMatrix(Minecraft minecraft) {
        long millis = (long) (net.minecraft.util.Util.getMillis() * minecraft.options.glintSpeed().get() * 8.0);
        float u = (millis % 110000L) / 110000.0f;
        float v = (millis % 30000L) / 30000.0f;
        return glintMatrix.translation(-u, v, 0.0f).rotateZ((float) (Math.PI / 18)).scale(0.16f);
    }

    /**
     * Minecraft publishes which chunks were loaded and which sections turned empty as a per-frame difference, and
     * clears it again on the next extraction. Only {@code LevelRenderer.renderLevel} reads it, and that is the
     * method this renderer stands in for, so every one of those differences would be lost while ray tracing is on.
     * The occlusion graph would then believe none of those chunks ever arrived, and the moment the player switches
     * ray tracing off it would find nothing to draw - an empty world that only a rejoin repaired.
     */
    private static void keepOcclusionGraphFed(Minecraft minecraft, LevelRenderState levelRenderState) {
        LevelRenderer levelRenderer = minecraft.levelRenderer;
        if (levelRenderer == null) {
            return;
        }

        ChunkLoadingRenderState chunkLoading = levelRenderState.chunkLoadingRenderState;
        SectionOcclusionGraph graph = levelRenderer.sectionOcclusionGraph();
        if (chunkLoading.addedLoadedChunks != null && chunkLoading.removedLoadedChunks != null) {
            graph.updateLoadedChunks(chunkLoading.addedLoadedChunks, chunkLoading.removedLoadedChunks);
        }
        if (chunkLoading.addedEmptySections != null && chunkLoading.removedEmptySections != null) {
            graph.updateEmptySections(chunkLoading.addedEmptySections, chunkLoading.removedEmptySections);
        }
    }

    /** Pulls the section tracker out of the extractor and hands it to the chunk manager. */
    private static final class SectionTrackerHolder {

        static void update(Minecraft minecraft, Vec3 cameraPos) {
            net.minecraft.client.SectionUpdateTracker tracker =
                ((com.g2806.radiante.client.render.LevelExtractorAccess) minecraft.levelExtractor)
                    .radiante$sectionUpdateTracker();
            if (tracker != null) {
                ChunkManager.update(minecraft.level, tracker, SectionPos.of(cameraPos));
            }
        }
    }

    public static int blocksAtlasId() {
        return TextureTracker.idOf(TextureAtlas.LOCATION_BLOCKS);
    }

    static {
        // Keep a reference so the constant folding of VK12 does not drop the LWJGL class from the module graph.
        assert VK12.VK_SUCCESS == 0;
    }
}
