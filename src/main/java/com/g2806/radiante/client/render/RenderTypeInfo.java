package com.g2806.radiante.client.render;

import com.g2806.radiante.mixin.render.RenderTypeAccessors.RenderSetupAccessor;
import com.g2806.radiante.mixin.render.RenderTypeAccessors.RenderTypeAccessor;
import com.g2806.radiante.mixin.render.RenderTypeAccessors.TextureBindingAccessor;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Per render type data the ray tracer needs: which mirrored texture it samples, whether it blends and whether
 * it reads the overlay (entities flashing red or white).
 */
public final class RenderTypeInfo {

    private static final Map<RenderType, RenderTypeInfo> CACHE = new IdentityHashMap<>();

    private final Identifier texture;
    private final boolean useOverlay;
    private final boolean blending;
    private final boolean solid;

    private RenderTypeInfo(Identifier texture, boolean useOverlay, boolean blending, boolean solid) {
        this.texture = texture;
        this.useOverlay = useOverlay;
        this.blending = blending;
        this.solid = solid;
    }

    public static synchronized RenderTypeInfo of(RenderType renderType) {
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
        info = new RenderTypeInfo(texture, access.radiante$useOverlay(), renderType.hasBlending(), solid);
        CACHE.put(renderType, info);
        return info;
    }

    /** The renderer id of the texture this render type samples, or 0 when it has none. */
    public int textureId() {
        return this.texture == null ? 0 : TextureTracker.idOf(this.texture);
    }

    public boolean useOverlay() {
        return this.useOverlay;
    }

    public int alphaMode() {
        if (this.solid) {
            return PBRVertexWriter.ALPHA_MODE_OPAQUE;
        }
        return this.blending ? PBRVertexWriter.ALPHA_MODE_TRANSPARENT : PBRVertexWriter.ALPHA_MODE_CUTOUT;
    }

    /**
     * Only fully opaque geometry may be traced as solid: the acceleration structure marks it opaque, and the
     * shaders never terminate when an opaque hit asks them to continue past a cut out texel.
     */
    public int geometryType() {
        return this.solid ? NativeGeometry.GEOMETRY_TYPE_WORLD_SOLID : NativeGeometry.GEOMETRY_TYPE_WORLD_TRANSPARENT;
    }
}
