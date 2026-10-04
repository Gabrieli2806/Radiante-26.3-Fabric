package com.g2806.radiante.client.compat.litematica;

import com.g2806.radiante.api.RadianteGeometry;
import com.g2806.radiante.client.RadianteClient;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Litematica keeps the schematics placed in the world in a level of its own and draws that level with its own chunk
 * renderer, inside Minecraft's terrain passes - which do not run while the world is traced, so schematics did not
 * show. This reads the blocks of that level around the camera, builds their models and hands them to the ray tracer
 * as see-through ghosts.
 *
 * <p>As Litematica does, a schematic block is shown only where the world does not already hold that very block, and
 * only inside the layers chosen in Litematica. Its settings for showing schematics at all and for how see-through
 * the ghosts are apply.
 *
 * <p>Not covered: its overlays - the coloured boxes over missing and wrong blocks, selection and placement boxes -
 * and schematic fluids, entities and block entities. Those are drawn straight into Minecraft's render passes.
 *
 * <p>Everything of Litematica is looked up by name at run time, so it need not be installed and a changed version
 * switches this off instead of breaking the game.
 */
public final class LitematicaCompat implements RadianteGeometry.Provider {

    private static final String NAME = "radiante:litematica";
    /** How far from the camera ghosts are built, in sections each way. */
    private static final int REACH = 4;
    /** Sections looked at per frame, and of those how many may be built: a build walks 4096 blocks twice. */
    private static final int LOOKS_PER_FRAME = 96;
    private static final int BUILDS_PER_FRAME = 3;
    /** Above this the frame's ghosts stop growing; a schematic larger than that shows its nearest part. */
    private static final int MAX_QUADS = 120_000;

    private LitematicaCompat() {
    }

    /** Registers the bridge when Litematica is installed. */
    public static void init() {
        try {
            Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler", false,
                LitematicaCompat.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError notInstalled) {
            return;
        }
        // Ghosts are a guide, not part of the world: they cast no shadow.
        RadianteGeometry.register(NAME, new LitematicaCompat(), false);
        RadianteClient.LOGGER.info("Litematica found: its schematic blocks are traced by Radiante");
    }

    // ---- Litematica, by name ----

    private boolean resolved;
    private Method getSchematicWorld;
    private Method getRenderLayerRange;
    private Method isPositionWithinRange;
    private Object enableRendering;
    private Object enableSchematicRendering;
    private Object enableSchematicBlocks;
    private Object renderTranslucent;
    private Object ghostAlpha;
    private Method getBooleanValue;
    private Method getDoubleValue;

    private void resolve() throws ReflectiveOperationException {
        ClassLoader loader = LitematicaCompat.class.getClassLoader();
        this.getSchematicWorld = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler", true, loader)
            .getMethod("getSchematicWorld");
        this.getRenderLayerRange = Class.forName("fi.dy.masa.litematica.data.DataManager", true, loader)
            .getMethod("getRenderLayerRange");
        this.isPositionWithinRange = this.getRenderLayerRange.getReturnType()
            .getMethod("isPositionWithinRange", int.class, int.class, int.class);
        Class<?> visuals = Class.forName("fi.dy.masa.litematica.config.Configs$Visuals", true, loader);
        this.enableRendering = visuals.getField("ENABLE_RENDERING").get(null);
        this.enableSchematicRendering = visuals.getField("ENABLE_SCHEMATIC_RENDERING").get(null);
        this.enableSchematicBlocks = visuals.getField("ENABLE_SCHEMATIC_BLOCKS").get(null);
        this.renderTranslucent = visuals.getField("RENDER_BLOCKS_AS_TRANSLUCENT").get(null);
        this.ghostAlpha = visuals.getField("GHOST_BLOCK_ALPHA").get(null);
        this.getBooleanValue = this.enableRendering.getClass().getMethod("getBooleanValue");
        this.getDoubleValue = this.ghostAlpha.getClass().getMethod("getDoubleValue");
    }

    private boolean on(Object option) throws ReflectiveOperationException {
        return (Boolean) this.getBooleanValue.invoke(option);
    }

    // ---- ghosts ----

    /** The ghost quads of one section: per vertex x, y, z (from the section's corner), u, v and a colour. */
    private static final class Ghosts {
        final int originX;
        final int originY;
        final int originZ;
        float[] vertices = new float[0];
        int[] colors = new int[0];

        Ghosts(int sectionX, int sectionY, int sectionZ) {
            this.originX = sectionX << 4;
            this.originY = sectionY << 4;
            this.originZ = sectionZ << 4;
        }
    }

    private final Long2ObjectOpenHashMap<Ghosts> sections = new Long2ObjectOpenHashMap<>();
    /** Section offsets from the camera's, nearest first, walked a few a frame over and over. */
    private final List<int[]> order = makeOrder();
    private int cursor;
    private Level builtFor;
    private ModelBlockRenderer blockRenderer;
    private final FloatArrayList vertexScratch = new FloatArrayList();
    private final IntArrayList colorScratch = new IntArrayList();

    private static List<int[]> makeOrder() {
        List<int[]> order = new ArrayList<>();
        for (int x = -REACH; x <= REACH; x++) {
            for (int y = -REACH; y <= REACH; y++) {
                for (int z = -REACH; z <= REACH; z++) {
                    order.add(new int[] {x, y, z});
                }
            }
        }
        order.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2]));
        return order;
    }

    @Override
    public void provide(RadianteGeometry.Sink sink) {
        try {
            if (!this.resolved) {
                resolve();
                this.resolved = true;
            }
            provideChecked(sink);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError changed) {
            RadianteGeometry.unregister(NAME);
            this.sections.clear();
            RadianteClient.LOGGER.warn("Litematica is not the version Radiante knows; its schematics will not show "
                + "with ray tracing on", changed);
        }
    }

    private void provideChecked(RadianteGeometry.Sink sink) throws ReflectiveOperationException {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Object world = this.getSchematicWorld.invoke(null);
        if (level == null || !(world instanceof Level schematic) || !on(this.enableRendering)
            || !on(this.enableSchematicRendering) || !on(this.enableSchematicBlocks)) {
            this.sections.clear();
            return;
        }
        if (this.builtFor != schematic) {
            // Litematica makes a new level when schematics are reloaded.
            this.builtFor = schematic;
            this.sections.clear();
            this.cursor = 0;
        }
        Vec3 camera = sink.camera();
        int centerX = SectionPos.blockToSectionCoord(camera.x);
        int centerY = SectionPos.blockToSectionCoord(camera.y);
        int centerZ = SectionPos.blockToSectionCoord(camera.z);
        Object layers = this.getRenderLayerRange.invoke(null);

        int built = 0;
        for (int looked = 0; looked < LOOKS_PER_FRAME && built < BUILDS_PER_FRAME; looked++) {
            int[] offset = this.order.get(this.cursor);
            this.cursor = (this.cursor + 1) % this.order.size();
            int sx = centerX + offset[0];
            int sy = centerY + offset[1];
            int sz = centerZ + offset[2];
            long key = SectionPos.asLong(sx, sy, sz);
            if (isEmpty(schematic, sx, sy, sz)) {
                this.sections.remove(key);
                continue;
            }
            Ghosts ghosts = build(minecraft, level, schematic, layers, sx, sy, sz);
            built++;
            if (ghosts == null) {
                this.sections.remove(key);
            } else {
                this.sections.put(key, ghosts);
            }
        }
        // Sections the camera has left behind.
        this.sections.long2ObjectEntrySet().removeIf(entry -> {
            long key = entry.getLongKey();
            return Math.abs(SectionPos.x(key) - centerX) > REACH || Math.abs(SectionPos.y(key) - centerY) > REACH
                || Math.abs(SectionPos.z(key) - centerZ) > REACH;
        });
        if (this.sections.isEmpty()) {
            return;
        }

        boolean translucent = on(this.renderTranslucent);
        int alpha = translucent
            ? (int) Math.round(255.0 * Math.max(0.05, Math.min(1.0, (Double) this.getDoubleValue.invoke(this.ghostAlpha))))
            : 255;
        VertexConsumer out = sink.quads(TextureAtlas.LOCATION_BLOCKS,
            translucent ? RadianteGeometry.Alpha.DITHERED : RadianteGeometry.Alpha.CUTOUT);
        int quads = 0;
        for (Ghosts ghosts : this.sections.values()) {
            float ox = (float) (ghosts.originX - camera.x);
            float oy = (float) (ghosts.originY - camera.y);
            float oz = (float) (ghosts.originZ - camera.z);
            float[] vertices = ghosts.vertices;
            for (int i = 0, v = 0; v < ghosts.colors.length; i += 5, v++) {
                int color = ghosts.colors[v];
                out.addVertex(ox + vertices[i], oy + vertices[i + 1], oz + vertices[i + 2])
                    .setColor(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, alpha)
                    .setUv(vertices[i + 3], vertices[i + 4]);
            }
            quads += ghosts.colors.length / 4;
            if (quads > MAX_QUADS) {
                break;
            }
        }
    }

    private static boolean isEmpty(Level schematic, int sectionX, int sectionY, int sectionZ) {
        if (sectionY < schematic.getMinSectionY() || sectionY > schematic.getMaxSectionY()) {
            return true;
        }
        LevelChunk chunk = schematic.getChunk(sectionX, sectionZ);
        if (chunk == null || chunk.isEmpty()) {
            return true;
        }
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
        return section == null || section.hasOnlyAir();
    }

    /** The ghosts of one section, or null when it has none to show. */
    private Ghosts build(Minecraft minecraft, ClientLevel level, Level schematic, Object layers, int sectionX,
        int sectionY, int sectionZ) throws ReflectiveOperationException {
        if (this.blockRenderer == null) {
            // Ambient occlusion off, faces between neighbours culled, as Radiante builds terrain.
            this.blockRenderer = new ModelBlockRenderer(false, true, minecraft.getBlockColors());
        }
        Ghosts ghosts = new Ghosts(sectionX, sectionY, sectionZ);
        FloatArrayList vertices = this.vertexScratch;
        IntArrayList colors = this.colorScratch;
        vertices.clear();
        colors.clear();
        BlockQuadOutput output = (x, y, z, quad, instance) -> {
            for (int vertex = 0; vertex < 4; vertex++) {
                Vector3fc local = quad.position(vertex);
                long packedUv = quad.packedUV(vertex);
                vertices.add(local.x() + x);
                vertices.add(local.y() + y);
                vertices.add(local.z() + z);
                vertices.add(UVPair.unpackU(packedUv));
                vertices.add(UVPair.unpackV(packedUv));
                colors.add(instance.getColor(vertex));
            }
        };
        BlockAndTintGetter view = new SchematicView(schematic, level);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set(ghosts.originX + x, ghosts.originY + y, ghosts.originZ + z);
                    BlockState state = schematic.getBlockState(pos);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    // Already built in the world: nothing left to show there.
                    if (level.getBlockState(pos) == state) {
                        continue;
                    }
                    if (!(Boolean) this.isPositionWithinRange.invoke(layers, pos.getX(), pos.getY(), pos.getZ())) {
                        continue;
                    }
                    BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(state);
                    this.blockRenderer.tesselateBlock(output, x, y, z, view, pos, state, model, state.getSeed(pos));
                }
            }
        }
        if (colors.isEmpty()) {
            return null;
        }
        ghosts.vertices = vertices.toFloatArray();
        ghosts.colors = colors.toIntArray();
        return ghosts;
    }

    /** The schematic's blocks with the real world's light and biome colours, for building block models. */
    private record SchematicView(Level schematic, ClientLevel level) implements BlockAndTintGetter {

        @Override
        public CardinalLighting cardinalLighting() {
            return this.level.cardinalLighting();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return this.level.getBlockTint(pos, resolver);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return this.level.getLightEngine();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return this.schematic.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.schematic.getFluidState(pos);
        }

        @Override
        public int getHeight() {
            return this.schematic.getHeight();
        }

        @Override
        public int getMinY() {
            return this.schematic.getMinY();
        }
    }
}
