package com.g2806.radiante.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final float NAME_TAG_EMISSION = 1.0f;

    private final Map<RenderType, PBRVertexWriter> writers = new LinkedHashMap<>();

    /** Extra glow the current entity gives off whatever layer it is drawn with, such as a glow item frame. */
    private float entityEmission;
    private final List<QuadParticleRenderState> particleGroups = new ArrayList<>();
    private final List<PBRVertexWriter> pool = new ArrayList<>();
    private final QuadInstance quadInstance = new QuadInstance();
    private @Nullable ModelBlockRenderer movingBlockRenderer;
    /** Layers written by name tags; traced as overlay geometry that casts no shadow. */
    private final java.util.Set<RenderType> nameTagLayers = new java.util.HashSet<>();
    private boolean collectingNameTag;
    private int used;

    /** Drops the geometry of the previous entity, keeping the buffers for reuse. */
    public void reset() {
        this.writers.clear();
        this.nameTagLayers.clear();
        this.used = 0;
        this.entityEmission = 0.0f;
    }

    public Map<RenderType, PBRVertexWriter> layers() {
        return this.writers;
    }

    public boolean isNameTagLayer(RenderType renderType) {
        return this.nameTagLayers.contains(renderType);
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
            .albedoEmission(info.emission() + this.entityEmission)
            .overlayEnabled(info.useOverlay())
            .computeQuadNormals(info.needsComputedNormals());
        this.writers.put(renderType, writer);
        return writer;
    }

    /** Set before an entity is submitted; cleared with the collector. */
    public void entityEmission(float entityEmission) {
        this.entityEmission = entityEmission;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
        int lightCoords, int overlayCoords, int tintedColor, @Nullable UvMapping uvMapping, int outlineColor) {
        // An outline colour means the glowing effect. Vanilla still draws the model, and draws the silhouette on
        // top from a post effect this renderer does not run; dropping the submission made a glowing mob or player
        // disappear instead. The glow is applied as emission in EntityManager.
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
        // A block entity being mined: its model again, with the destroy-stage texture projected onto it.
        if (crumblingOverlay == null) {
            return;
        }
        PBRVertexWriter writer = this.crumblingWriter(crumblingOverlay.progress());
        model.setupAnim(state);
        model.renderToBuffer(poseStack,
            new com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator(writer, crumblingOverlay.cameraPose(), 1.0f),
            lightCoords, overlayCoords, -1);
    }

    /**
     * The destroy-stage layer. Vanilla multiplies it onto the block at twice its value, so its mid-grey texels leave
     * the block alone and only the dark ones darken it. A tracer has no destination colour to multiply; the decal
     * alpha mode keeps a crack texel in proportion to how much it would darken, which reads the same.
     */
    private PBRVertexWriter crumblingWriter(int progress) {
        RenderType renderType = ModelBakery.DESTROY_TYPES.get(Math.clamp(progress, 0, ModelBakery.DESTROY_TYPES.size() - 1));
        return this.writer(renderType).alphaMode(PBRVertexWriter.ALPHA_MODE_DECAL);
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
        int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {


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

        // Only models Fabric keeps entirely in its mesh need this: those are the ones vanilla hands over empty, an
        // item frame among them. Where vanilla parts exist they have already been written above, and adding the mesh
        // on top of them would trace the same model twice.
        if (mesh == null || mesh.size() == 0 || !parts.isEmpty()) {
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
        // Falling blocks and the blocks a piston is pushing are not in the terrain: the section holds a moving
        // piston block entity (or nothing) until they land, so they have to be traced here like any entity.
        Minecraft minecraft = Minecraft.getInstance();
        BlockState blockState = movingBlockRenderState.blockState;
        BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(blockState);
        boolean translucent = model.hasMaterialFlag(1);
        boolean forceSolid = ModelBlockRenderer.forceOpaque(minecraft.options.cutoutLeaves().get(), blockState);
        PoseStack.Pose pose = poseStack.last();

        if (this.movingBlockRenderer == null) {
            // Ambient occlusion off, as for terrain: the path tracer computes its own.
            this.movingBlockRenderer = new ModelBlockRenderer(false, false, minecraft.getBlockColors());
        }

        BlockQuadOutput output = (x, y, z, quad, instance) -> {
            ChunkSectionLayer layer = translucent ? ChunkSectionLayer.TRANSLUCENT
                : forceSolid ? ChunkSectionLayer.SOLID
                : quad.materialInfo().layer();
            RenderType renderType = switch (layer) {
                case SOLID -> RenderTypes.solidMovingBlock();
                case CUTOUT -> RenderTypes.cutoutMovingBlock();
                case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            };
            putMovingBlockQuad(pose, x, y, z, quad, instance, this.writer(renderType));
        };

        this.movingBlockRenderer.tesselateBlock(output, 0.0f, 0.0f, 0.0f, movingBlockRenderState,
            movingBlockRenderState.blockPos, blockState, model, blockState.getSeed(movingBlockRenderState.randomSeedPos));
    }

    /** One tesselated block quad, written by hand for the same reason as {@link #putQuads}. */
    private static void putMovingBlockQuad(PoseStack.Pose pose, float x, float y, float z, BakedQuad quad,
        QuadInstance instance, VertexConsumer buffer) {
        Vector3f normal = pose.transformNormal(quad.direction().getUnitVec3f(), new Vector3f());
        Vector3f position = new Vector3f();
        int lightEmission = quad.materialInfo().lightEmission();

        for (int vertex = 0; vertex < 4; vertex++) {
            long packedUv = quad.packedUV(vertex);
            Vector3fc local = quad.position(vertex);
            pose.pose().transformPosition(local.x() + x, local.y() + y, local.z() + z, position);
            buffer.addVertex(position.x(), position.y(), position.z())
                .setColor(instance.getColor(vertex))
                .setUv(UVPair.unpackU(packedUv), UVPair.unpackV(packedUv))
                .setOverlay(instance.overlayCoords())
                .setLight(instance.getLightCoordsWithEmission(vertex, lightEmission))
                .setNormal(normal.x(), normal.y(), normal.z());
        }
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress,
        boolean isBlockTranslucent) {
        PBRVertexWriter writer = this.crumblingWriter(progress);
        PoseStack.Pose pose = poseStack.last();
        org.joml.Matrix4f inverse = new org.joml.Matrix4f(pose.pose()).invert();
        Vector3f position = new Vector3f();
        Vector3f local = new Vector3f();
        Vector3f decal = new Vector3f();
        for (BlockStateModelPart part : parts) {
            for (Direction direction : DIRECTIONS) {
                putBreakingQuads(part.getQuads(direction), pose, inverse, writer, position, local, decal);
            }
            putBreakingQuads(part.getQuads(null), pose, inverse, writer, position, local, decal);
        }
    }

    /** How far the cracks sit in front of the block face, in blocks, so they are hit before the face itself. */
    private static final float CRUMBLING_OFFSET = 0.002f;

    /** Decal UVs exactly as vanilla's SheetedDecalTextureGenerator derives them from position and face. */
    private static void putBreakingQuads(List<BakedQuad> quads, PoseStack.Pose pose, org.joml.Matrix4f inverse,
        PBRVertexWriter writer, Vector3f position, Vector3f local, Vector3f decal) {
        for (BakedQuad quad : quads) {
            Direction face = quad.direction();
            Vector3fc unit = face.getUnitVec3f();
            Vector3f normal = pose.transformNormal(unit, new Vector3f());
            for (int vertex = 0; vertex < 4; vertex++) {
                Vector3fc corner = quad.position(vertex);
                pose.pose().transformPosition(corner.x() + unit.x() * CRUMBLING_OFFSET,
                    corner.y() + unit.y() * CRUMBLING_OFFSET, corner.z() + unit.z() * CRUMBLING_OFFSET, position);
                inverse.transformPosition(position, local);
                decal.set(local).rotateY((float) Math.PI).rotateX((float) (-Math.PI / 2)).rotate(face.getRotation());
                writer.addVertex(position.x(), position.y(), position.z())
                    .setColor(-1)
                    .setUv(-decal.x(), -decal.y())
                    .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                    .setLight(LightCoordsUtil.FULL_BRIGHT)
                    .setNormal(normal.x(), normal.y(), normal.z());
            }
        }
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
        if (nameTagAttachment == null) {
            return;
        }

        // Placed and turned to face the camera exactly as vanilla does it, then written as ordinary world text.
        // The polygon offset layer is the one the shader packs give the text any-hit shader, which is what cuts
        // each letter out of its cell. Vanilla's see-through copy has no meaning to a ray tracer and is skipped.
        poseStack.pushPose();
        poseStack.translate(nameTagAttachment.x, nameTagAttachment.y + 0.5, nameTagAttachment.z);
        poseStack.rotate(camera.orientation);
        poseStack.scale(0.025f, -0.025f, 0.025f);
        float x = -Minecraft.getInstance().font.width(name) / 2.0f;

        // The backing plate is vanilla's translucent black; the text any-hit shader hits it only that share of the
        // time, and name tag layers are kept out of shadow rays, so it darkens what is behind it without becoming a
        // board that throws a shadow. The letters glow so the name still reads at night and in caves.
        float backgroundAlpha = Minecraft.getInstance().gameRenderer.gameRenderState().optionsRenderState
            .getBackgroundOpacity(0.25f);
        int backgroundColor = ARGB.color(backgroundAlpha, 0xFF000000);
        float previousEmission = this.entityEmission;
        this.entityEmission = NAME_TAG_EMISSION;
        this.collectingNameTag = true;
        try {
            this.submitText(poseStack, x, offset, name.getVisualOrderText(), false, Font.DisplayMode.POLYGON_OFFSET,
                LightCoordsUtil.FULL_BRIGHT, -1, backgroundColor, 0);
        } finally {
            this.collectingNameTag = false;
            this.entityEmission = previousEmission;
            poseStack.popPose();
        }
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
                if (EntityCollector.this.collectingNameTag) {
                    EntityCollector.this.nameTagLayers.add(renderType);
                }
                // Font pages are built at runtime and are not registered under the identifier the render layer
                // names, so asking the texture manager for the layer's texture answers about some other sheet.
                // Everything about a glyph - which page to sample and how many channels that page has - has to
                // come from the renderable, which knows the page its glyph was baked into.
                if (renderable.textureView() == null || renderable.textureView().texture() == null) {
                    return;
                }
                GpuTexture page = renderable.textureView().texture();
                int fontTextureId = TextureTracker.idOf(page);
                if (fontTextureId == 0) {
                    return;
                }
                writer.textureId(fontTextureId);
                writer.alphaMode(RenderTypeInfo.of(renderType)
                    .alphaModeForPage(page.getFormat() == GpuFormat.R8_UNORM));
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
