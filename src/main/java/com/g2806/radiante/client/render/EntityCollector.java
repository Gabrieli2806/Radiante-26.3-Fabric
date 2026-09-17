package com.g2806.radiante.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.Minecraft;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.client.model.geom.builders.UVPair;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Turns Minecraft's deferred entity submissions into ray tracing geometry. Minecraft collects them into
 * {@code SubmitNodeStorage} and replays them into vertex buffers later; the renderer instead needs the
 * geometry per entity, so every submission is expanded here as it arrives.
 */
public final class EntityCollector implements SubmitNodeCollector {

    private static final Direction[] DIRECTIONS = Direction.values();


    /** How far world text is lifted off the surface it is written on, in blocks. */
    private static final float TEXT_SURFACE_OFFSET = 0.02f;

    private final Map<RenderType, PBRVertexWriter> writers = new LinkedHashMap<>();
    private final List<QuadParticleRenderState> particleGroups = new ArrayList<>();
    private final List<PBRVertexWriter> pool = new ArrayList<>();
    private final QuadInstance quadInstance = new QuadInstance();
    private int used;

    /** Drops the geometry of the previous entity, keeping the buffers for reuse. */
    public void reset() {
        this.writers.clear();
        this.used = 0;
    }

    public Map<RenderType, PBRVertexWriter> layers() {
        return this.writers;
    }

    public boolean isEmpty() {
        for (PBRVertexWriter writer : this.writers.values()) {
            if (writer.vertexCount() > 0) {
                return false;
            }
        }
        return true;
    }

    public void close() {
        for (PBRVertexWriter writer : this.pool) {
            writer.close();
        }
        this.pool.clear();
        this.writers.clear();
        this.used = 0;
    }

    private PBRVertexWriter writer(RenderType renderType) {
        PBRVertexWriter writer = this.writers.get(renderType);
        if (writer != null) {
            return writer;
        }

        if (this.used == this.pool.size()) {
            this.pool.add(new PBRVertexWriter(1024));
        }
        writer = this.pool.get(this.used++);
        writer.reset();

        RenderTypeInfo info = RenderTypeInfo.of(renderType);
        writer.textureId(info.textureId())
            .glintTextureId(0)
            .alphaMode(info.alphaMode())
            .coordinate(NativeGeometry.COORDINATE_CAMERA)
            .albedoEmission(info.emission())
            .overlayEnabled(info.useOverlay())
            .computeQuadNormals(info.needsComputedNormals());
        this.writers.put(renderType, writer);
        return writer;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
        int lightCoords, int overlayCoords, int tintedColor, @Nullable UvMapping uvMapping, int outlineColor) {
        if (outlineColor != 0) {
            return;
        }

        VertexConsumer buffer = this.writer(renderType);
        if (uvMapping != null) {
            buffer = uvMapping.wrap(buffer);
        }

        model.setupAnim(state);
        model.renderToBuffer(poseStack, buffer, lightCoords, overlayCoords, tintedColor);
    }

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack,
        RenderType renderType, int lightCoords, int overlayCoords, int tintedColor,
        ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        // Block breaking overlays are drawn by the terrain pass instead.
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
        int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {

        if (outlineColor != 0) {
            return;
        }

        PBRVertexWriter buffer = this.writer(renderType);
        int before = buffer.vertexCount();
        this.quadInstance.setLightCoords(lightCoords);
        this.quadInstance.setOverlayCoords(overlayCoords);
        for (BlockStateModelPart part : parts) {
            for (Direction direction : DIRECTIONS) {
                putQuads(part.getQuads(direction), poseStack, tintLayers, buffer, this.quadInstance);
            }
            putQuads(part.getQuads(null), poseStack, tintLayers, buffer, this.quadInstance);
        }

    }

    /**
     * Paintings reach the renderer through submitCustomGeometry and come out right; item frames go through the
     * baked quad helper and come out invisible. The two differ only in who writes the vertices, so block model
     * quads are expanded here the same way the custom geometry path writes them, by hand.
     */
    /**
     * Fabric replaces the storage behind a block model with a mesh of its own and submits it through this overload,
     * cancelling the vanilla one. Its default implementation forwards to the vanilla call with the mesh dropped,
     * which leaves nothing at all for models Fabric keeps entirely in the mesh - an item frame's frame is one of
     * them, and that is why it traced as empty air while the item inside it showed.
     */
    @Override
    public void submitBlockModel(PoseStack poseStack, Function<ChunkSectionLayer, RenderType> renderTypeFunction,
        boolean translucent, List<BlockStateModelPart> parts, @Nullable Mesh mesh, int[] tintLayers, int lightCoords,
        int overlayCoords, int outlineColor) {
        RenderType renderType = renderTypeFunction.apply(
            translucent ? ChunkSectionLayer.TRANSLUCENT : ChunkSectionLayer.CUTOUT);
        this.submitBlockModel(poseStack, renderType, parts, tintLayers, lightCoords, overlayCoords, outlineColor);

        if (mesh == null || mesh.size() == 0 || outlineColor != 0) {
            return;
        }

        PBRVertexWriter buffer = this.writer(renderType);
        PoseStack.Pose pose = poseStack.last();
        mesh.forEach(quad -> putMeshQuad(quad, pose, tintLayers, lightCoords, overlayCoords, buffer));
    }

    /** One quad out of a Fabric mesh, written with the same values the vanilla quad path writes. */
    private static void putMeshQuad(QuadView quad, PoseStack.Pose pose, int[] tintLayers, int lightCoords,
        int overlayCoords, PBRVertexWriter buffer) {
        int tintIndex = quad.tintIndex();
        int tint = tintIndex >= 0 && tintIndex < tintLayers.length ? ARGB.opaque(tintLayers[tintIndex]) : -1;
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();

        for (int vertex = 0; vertex < 4; vertex++) {
            pose.pose().transformPosition(quad.copyPos(vertex, position), position);
            if (quad.hasNormal(vertex)) {
                normal.set(quad.normalX(vertex), quad.normalY(vertex), quad.normalZ(vertex));
            } else {
                normal.set(quad.faceNormal());
            }
            pose.transformNormal(normal, normal);

            int light = quad.lightmap(vertex);
            buffer.addVertex(position.x(), position.y(), position.z())
                .setColor(ARGB.multiply(quad.color(vertex), tint))
                .setUv(quad.u(vertex), quad.v(vertex))
                .setOverlay(overlayCoords)
                .setLight(light == 0 ? lightCoords : light)
                .setNormal(normal.x(), normal.y(), normal.z());
        }
    }

    private static void putQuads(List<BakedQuad> quads, PoseStack poseStack, int[] tintLayers,
        VertexConsumer buffer, QuadInstance quadInstance) {
        PoseStack.Pose pose = poseStack.last();
        Vector3f position = new Vector3f();
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            int color = tintIndex >= 0 && tintIndex < tintLayers.length
                ? ARGB.opaque(tintLayers[tintIndex])
                : -1;
            Vector3fc unitNormal = quad.direction().getUnitVec3f();
            Vector3f normal = pose.transformNormal(unitNormal, new Vector3f());
            int lightEmission = quad.materialInfo().lightEmission();

            for (int vertex = 0; vertex < 4; vertex++) {
                long packedUv = quad.packedUV(vertex);
                pose.pose().transformPosition(quad.position(vertex), position);
                buffer.addVertex(position.x(), position.y(), position.z())
                    .setColor(color)
                    .setUv(UVPair.unpackU(packedUv), UVPair.unpackV(packedUv))
                    .setOverlay(quadInstance.overlayCoords())
                    .setLight(quadInstance.getLightCoordsWithEmission(vertex, lightEmission))
                    .setNormal(normal.x(), normal.y(), normal.z());
            }
        }
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
        int overlayCoords, int outlineColor, int[] tintLayers, ItemQuads quads,
        ItemStackRenderState.FoilType foilType) {
        if (outlineColor != 0) {
            return;
        }

        this.quadInstance.setLightCoords(lightCoords);
        this.quadInstance.setOverlayCoords(overlayCoords);
        for (BakedQuad quad : quads.all()) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            int tintIndex = material.tintIndex();
            this.quadInstance.setColor(tintIndex >= 0 && tintIndex < tintLayers.length
                ? ARGB.opaque(tintLayers[tintIndex])
                : -1);
            this.writer(material.itemRenderType()).putBakedQuad(poseStack.last(), quad, this.quadInstance);
        }
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
        SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        customGeometryRenderer.render(poseStack.last(), this.writer(renderType));
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState,
        int outlineColor) {
        // Pistons move terrain geometry; the terrain pass already rebuilds those sections.
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress,
        boolean isBlockTranslucent) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
        float width, boolean afterTerrain) {
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        // Path tracing casts real shadows.
    }

    @Override
    public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name,
        boolean seeThrough, int lightCoords, CameraRenderState camera) {
    }

    /**
     * Text drawn in the world: the lines on a sign, mostly. Minecraft lays the glyphs out for us and hands back
     * renderables that know how to emit their own quads, so each one is asked to write straight into the layer of
     * its own render type.
     */
    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
        Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor, int outlineColor) {
        Font font = Minecraft.getInstance().font;
        if (font == null) {
            return;
        }

        // Minecraft keeps sign text inside the board it is written on and relies on a depth bias to draw it in
        // front anyway. A ray tracer has no such bias: the board's own surface is hit first and the letters are
        // never seen. Pushing the glyphs a hair out along the way they face puts them where they appear to be.
        // The pose already carries the scale that turns glyph units into blocks, so the step has to be divided by
        // it to come out as a fixed distance in the world rather than a hundredth of one.
        poseStack.pushPose();
        float glyphScale = poseStack.last().pose().transformDirection(new Vector3f(0.0f, 0.0f, 1.0f)).length();
        poseStack.translate(0.0f, 0.0f, TEXT_SURFACE_OFFSET / Math.max(glyphScale, 1.0E-6f));
        Matrix4fc pose = poseStack.last().pose();
        Font.PreparedText prepared = font.prepareText(string, x, y, color, dropShadow, false, backgroundColor);
        prepared.visit(new Font.GlyphVisitor() {
            @Override
            public void acceptRenderable(TextRenderable renderable) {
                RenderType renderType = renderable.renderType(displayMode);
                if (renderType == null) {
                    return;
                }
                PBRVertexWriter writer = EntityCollector.this.writer(renderType);
                // Font pages are built at runtime and are not registered under the identifier the render layer
                // names, so resolving the texture by that name hands back something other than the glyph sheet.
                // The renderable knows which page its glyph actually lives on.
                if (renderable.textureView() != null && renderable.textureView().texture() != null) {
                    int fontTextureId = TextureTracker.idOf(renderable.textureView().texture());
                    if (fontTextureId != 0) {
                        writer.textureId(fontTextureId);
                    }
                }
                renderable.render(pose, writer, lightCoords, false);
            }
        });
        poseStack.popPose();
    }

    @Override
    public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color,
        Font.DisplayMode displayMode, int lightCoords) {
    }

    /**
     * The flames wrapped around a burning entity: a stack of shrinking billboards alternating between the two fire
     * sprites, the same shape vanilla draws, but emissive so the fire lights up what it is burning.
     */
    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlasSprite first = minecraft.getAtlasManager().get(ModelBakery.FIRE_0);
        TextureAtlasSprite second = minecraft.getAtlasManager().get(ModelBakery.FIRE_1);
        if (first == null || second == null) {
            return;
        }

        RenderType renderType = RenderTypes.entityCutoutCull(TextureAtlas.LOCATION_BLOCKS);
        PBRVertexWriter writer = this.writer(renderType);
        PoseStack.Pose pose = poseStack.last();

        float scale = renderState.boundingBoxWidth * 1.4f;
        pose.scale(scale, scale, scale);
        float halfWidth = 0.5f;
        float remaining = renderState.boundingBoxHeight / scale;
        float verticalOffset = 0.0f;
        pose.rotate(rotation);
        pose.translate(0.0f, 0.0f, 0.3f - (int) remaining * 0.02f);
        float depth = 0.0f;
        int slice = 0;
        int lightCoords = LightCoordsUtil.withBlock(renderState.lightCoords, 15);

        writer.albedoEmission(RenderTypeInfo.FLAME_EMISSION);
        try {
            while (remaining > 0.0f) {
                TextureAtlasSprite sprite = slice % 2 == 0 ? first : second;
                float u0 = sprite.getU0();
                float v0 = sprite.getV0();
                float u1 = sprite.getU1();
                float v1 = sprite.getV1();
                if (slice / 2 % 2 == 0) {
                    float swap = u1;
                    u1 = u0;
                    u0 = swap;
                }

                flameVertex(pose, writer, -halfWidth, -verticalOffset, depth, u1, v1, lightCoords);
                flameVertex(pose, writer, halfWidth, -verticalOffset, depth, u0, v1, lightCoords);
                flameVertex(pose, writer, halfWidth, 1.4f - verticalOffset, depth, u0, v0, lightCoords);
                flameVertex(pose, writer, -halfWidth, 1.4f - verticalOffset, depth, u1, v0, lightCoords);

                remaining -= 0.45f;
                verticalOffset -= 0.45f;
                halfWidth *= 0.9f;
                depth -= 0.03f;
                slice++;
            }
        } finally {
            writer.albedoEmission(RenderTypeInfo.of(renderType).emission());
        }
    }

    private static void flameVertex(PoseStack.Pose pose, PBRVertexWriter writer, float x, float y, float z, float u,
        float v, int lightCoords) {
        writer.addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setUv1(0, 10).setLight(lightCoords)
            .setNormal(pose, 0.0f, 1.0f, 0.0f);
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
        this.particleGroups.add(particles);
    }

    /** Particle groups submitted since the last call, which are built separately per texture atlas layer. */
    public List<QuadParticleRenderState> drainParticleGroups() {
        List<QuadParticleRenderState> groups = new ArrayList<>(this.particleGroups);
        this.particleGroups.clear();
        return groups;
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera,
        boolean onTop) {
    }
}
