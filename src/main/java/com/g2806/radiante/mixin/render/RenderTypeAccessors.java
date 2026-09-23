package com.g2806.radiante.mixin.render;

import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The renderer needs the texture a render type samples and whether it uses the overlay, neither of which
 * Minecraft exposes.
 */
public final class RenderTypeAccessors {

    private RenderTypeAccessors() {
    }

    @Mixin(RenderType.class)
    public interface RenderTypeAccessor {

        @Accessor("state")
        RenderSetup radiante$state();

        @Accessor("name")
        String radiante$name();
    }

    @Mixin(RenderSetup.class)
    public interface RenderSetupAccessor {

        @Accessor("textures")
        Map<String, ?> radiante$textures();

        @Accessor("useOverlay")
        boolean radiante$useOverlay();

        @Accessor("textureTransform")
        net.minecraft.client.renderer.rendertype.TextureTransform radiante$textureTransform();
    }

    @Mixin(targets = "net.minecraft.client.renderer.rendertype.RenderSetup$TextureBinding")
    public interface TextureBindingAccessor {

        @Accessor("location")
        Identifier radiante$location();
    }
}
