package com.g2806.radiante.client.render;

import com.g2806.radiante.mixin.render.RenderTypeAccessors.RenderSetupAccessor;
import com.g2806.radiante.mixin.render.RenderTypeAccessors.RenderTypeAccessor;
import com.g2806.radiante.mixin.render.RenderTypeAccessors.TextureBindingAccessor;
import java.util.Map;
import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Per render type data the ray tracer needs: which mirrored texture it samples, whether it blends and whether
 * it reads the overlay (entities flashing red or white).
 */
public final class RenderTypeInfo {

    /**
     * Read on the render thread for every layer of every entity, every frame. A plain map behind a lock made that
     * a contended call for what is almost always a hit, so lookups go straight at a concurrent map and only a miss
     * pays anything. Building the same entry twice is harmless: the values are immutable and identical.
     */
    private static final Map<RenderType, RenderTypeInfo> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** A lightning bolt is the brightest thing in the world while it lasts. */
    private static final float LIGHTNING_EMISSION = 20.0f;
    /** A beacon beam is a solid column of light. */
    private static final float BEACON_BEAM_EMISSION = 10.0f;
    /**
     * Glowing eyes and the other emissive entity overlays. Kept low on purpose: the emission is multiplied by the
     * texture colour, so a high value pushes every channel past white and an enderman ends up with white eyes
     * instead of purple ones.
     */
    private static final float ENTITY_EMISSION = 1.0f;
    /** Flames wrapped around a burning entity. */
    public static final float FLAME_EMISSION = 6.0f;

    private final Identifier texture;
    private final boolean useOverlay;
    private final boolean blending;
    private final boolean solid;
    private final String name;

    private RenderTypeInfo(Identifier texture, boolean useOverlay, boolean blending, boolean solid, String name) {
        this.texture = texture;
        this.useOverlay = useOverlay;
        this.blending = blending;
        this.solid = solid;
        this.name = name == null ? "" : name;
    }

    public static RenderTypeInfo of(RenderType renderType) {
        RenderTypeInfo info = CACHE.get(renderType);
        if (info != null) {
            return info;
        }

        RenderSetup setup = ((RenderTypeAccessor) (Object) renderType).radiante$state();
        RenderSetupAccessor access = (RenderSetupAccessor) (Object) setup;
        Object binding = access.radiante$textures().get("Sampler0");
        Identifier texture = binding == null ? null : ((TextureBindingAccessor) binding).radiante$location();
        String name = ((RenderTypeAccessor) (Object) renderType).radiante$name();
        // Only fully opaque types are traced as solid. Cut out layers (villager hats, zombie heads, skin overlays)
        // keep their alpha test, otherwise their transparent texels render black on top of the face.
        boolean solid = !renderType.hasBlending() && name != null && name.contains("solid");
        info = new RenderTypeInfo(texture, access.radiante$useOverlay(), renderType.hasBlending(), solid, name);
        CACHE.put(renderType, info);
        return info;
    }

    /** See util/text_mode.glsl: these share the alpha mode field, so they must not collide with its values. */
    private int layerTextMode() {
        return switch (this.name) {
            case "text_background" -> 1;
            case "text_intensity" -> 2;
            case "text" -> 3;
            case "text_background_see_through" -> 4;
            case "text_intensity_see_through" -> 5;
            case "text_see_through" -> 6;
            case "text_intensity_polygon_offset" -> 7;
            case "text_polygon_offset" -> 8;
            default -> PBRVertexWriter.ALPHA_MODE_OPAQUE;
        };
    }

    /**
     * Minecraft builds a font page with one channel unless the font needs colour, and the shape of a letter is that
     * single channel. Read as colour it samples as red with a solid alpha, which is a filled rectangle rather than
     * a letter, so a page like that is always an intensity mode whatever the layer is called.
     */
    private static int forChannels(int mode, boolean singleChannelPage) {
        return (mode == 3 || mode == 6 || mode == 8) && singleChannelPage ? mode - 1 : mode;
    }

    private int textMode() {
        return forChannels(layerTextMode(), isSingleChannel());
    }

    /**
     * The same choice, for a glyph whose page is known. Font pages are built at runtime and are not registered
     * under the identifier the layer names, so asking the texture manager for the layer's texture answers about
     * some other sheet - or about nothing at all. Every glyph knows the page it was baked into, and that is the one
     * whose channel count decides how the shader has to read it.
     */
    public int alphaModeForPage(boolean singleChannelPage) {
        int mode = layerTextMode();
        if (mode == PBRVertexWriter.ALPHA_MODE_OPAQUE) {
            return alphaMode();
        }
        return forChannels(mode, singleChannelPage);
    }

    private boolean isSingleChannel() {
        if (this.texture == null) {
            return false;
        }
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(this.texture);
        return texture != null && texture.getTexture() != null
            && texture.getTexture().getFormat() == GpuFormat.R8_UNORM;
    }

    /** The renderer id of the texture this render type samples, or 0 when it has none. */
    public int textureId() {
        return this.texture == null ? 0 : TextureTracker.idOf(this.texture);
    }

    /**
     * Layers that are light themselves rather than lit surfaces. They carry no texture and are drawn additively in
     * vanilla, so without this they would trace as dark geometry.
     */
    public float emission() {
        if (this.name.contains("lightning")) {
            return LIGHTNING_EMISSION;
        }
        if (this.name.contains("beacon_beam")) {
            return BEACON_BEAM_EMISSION;
        }
        // "eyes" covers endermen, spiders and blazes; the emissive variant covers the overlays other mobs add.
        if (this.name.equals("eyes") || this.name.contains("emissive")) {
            return ENTITY_EMISSION;
        }
        return 0.0f;
    }

    /**
     * Layers whose vertices arrive without a normal. End portals submit bare corner positions, and glyphs carry
     * position, colour and texture coordinate but nothing to say which way they face. A surface with no normal
     * cannot be shaded, so the normal is derived from the quad itself instead.
     */
    public boolean needsComputedNormals() {
        return isEndPortal() || isEndGateway() || this.name.startsWith("text");
    }

    /** The texture this layer samples, or null when it has none. */
    public Identifier texture() {
        return this.texture;
    }

    public boolean useOverlay() {
        return this.useOverlay;
    }

    private boolean isEndPortal() {
        return this.name.equals("end_portal");
    }

    private boolean isEndGateway() {
        return this.name.equals("end_gateway");
    }

    /** Hit groups the packs define for text. Any other name here stalls the renderer, so the list is exact. */
    private static final java.util.Set<String> TEXT_HIT_GROUPS = java.util.Set.of(
        "text_see_through", "text_intensity_see_through", "text_polygon_offset", "text_intensity_polygon_offset");

    /** Hit group the shader packs shade this geometry with; a few have their own, everything else the default. */
    public String groupName() {
        // The shader packs name their hit groups after render layers, which makes it tempting to hand the layer
        // name over and let specialised groups do their job. Doing that stalls the renderer within seconds of
        // entering a world: most of those groups are not valid for entity geometry. Only the names the packs
        // really define for this geometry are passed through - the portals, and the four text groups, whose
        // any-hit shader is the only thing that cuts a glyph out of the cell it is drawn in.
        if (isEndPortal() || isEndGateway() || TEXT_HIT_GROUPS.contains(this.name)) {
            return this.name;
        }
        return "Entity";
    }

    public int alphaMode() {
        // Text layers put a text mode in this field instead of an alpha mode: the shaders read it to know whether a
        // glyph page carries its coverage in the red channel, in the alpha channel, or is a solid background quad.
        int textMode = textMode();
        if (textMode != PBRVertexWriter.ALPHA_MODE_OPAQUE) {
            return textMode;
        }
        if (this.solid || isEndPortal() || isEndGateway()) {
            return PBRVertexWriter.ALPHA_MODE_OPAQUE;
        }
        return this.blending ? PBRVertexWriter.ALPHA_MODE_TRANSPARENT : PBRVertexWriter.ALPHA_MODE_CUTOUT;
    }

    /**
     * Only fully opaque geometry may be traced as solid: the acceleration structure marks it opaque, and the
     * shaders never terminate when an opaque hit asks them to continue past a cut out texel.
     */
    public int geometryType() {
        if (isEndPortal()) {
            return NativeGeometry.GEOMETRY_TYPE_END_PORTAL;
        }
        if (isEndGateway()) {
            return NativeGeometry.GEOMETRY_TYPE_END_GATEWAY;
        }
        // A glyph cell is mostly empty, and only the text any-hit shader knows which texels are the letter. Solid
        // geometry is built with the Vulkan opaque flag, which tells the driver it may skip any-hit entirely - so
        // calling text solid is what turned every letter into a filled rectangle. It has to stay non-opaque.
        if (textMode() != PBRVertexWriter.ALPHA_MODE_OPAQUE) {
            return NativeGeometry.GEOMETRY_TYPE_WORLD_TRANSPARENT;
        }
        return this.solid ? NativeGeometry.GEOMETRY_TYPE_WORLD_SOLID : NativeGeometry.GEOMETRY_TYPE_WORLD_TRANSPARENT;
    }
}
