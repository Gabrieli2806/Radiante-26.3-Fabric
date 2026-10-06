package com.g2806.radiante.client.profile;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.pipeline.Pipeline;

import java.util.Locale;

/**
 * A named set of renderer settings.
 *
 * <ul>
 * <li>{@link #DEFAULT}: Radiante's own look, as it was before profiles existed.
 * <li>{@link #BEDROCK_RTX}: the Bedrock RTX compatible mode. Its values live in {@link BedrockRtxProfile} and say how
 * well each one is known.
 * <li>{@link #RADIANTE_ENHANCED}: reserved for techniques beyond Bedrock (ReSTIR variations, ray reconstruction
 * tuning). Today it is the same as {@link #DEFAULT}; it exists so the choice does not have to be added later.
 * </ul>
 *
 * Applying a profile writes the attributes it manages (see {@link BedrockRtxProfile#PARAMETERS}) and remembers the
 * choice in {@link Options#rendererProfile}; attributes it does not manage are left as the player set them.
 */
public enum RendererProfile {
    DEFAULT,
    BEDROCK_RTX,
    RADIANTE_ENHANCED;

    /** The profile saved in the options, {@link #DEFAULT} when it is missing or unknown. */
    public static RendererProfile current() {
        return of(Options.rendererProfile);
    }

    public static RendererProfile of(String name) {
        if (name != null) {
            for (RendererProfile profile : values()) {
                if (profile.name().equals(name.trim().toUpperCase(Locale.ROOT))) {
                    return profile;
                }
            }
        }
        return DEFAULT;
    }

    /**
     * Writes this profile's attributes into the pipeline and saves the choice. Returns true when the pipeline has to be
     * rebuilt for it to show ({@code Pipeline.build()}, or the applying screen).
     */
    public boolean apply() {
        boolean rebuild = false;
        for (CompatParameter parameter : BedrockRtxProfile.PARAMETERS) {
            String value = this == BEDROCK_RTX ? parameter.bedrockValue() : parameter.defaultValue();
            boolean shaderPack = parameter.module() == null;
            boolean changed = shaderPack ? Pipeline.setShaderPackValue(parameter.attribute(), value)
                : Pipeline.setModuleValue(parameter.module(), parameter.attribute(), value);
            String present = shaderPack ? Pipeline.getShaderPackValue(parameter.attribute())
                : Pipeline.getModuleValue(parameter.module(), parameter.attribute());
            if (!changed && present == null) {
                RadianteClient.LOGGER.warn("Profile {}: the pipeline has no attribute {} in {}", this,
                    parameter.attribute(), parameter.module());
            }
            rebuild |= changed;
        }
        Options.rendererProfile = name();
        Options.overwriteConfig();
        if (rebuild) {
            Pipeline.savePipeline();
        }
        RadianteClient.LOGGER.info("Renderer profile {} applied (rebuild needed: {})", this, rebuild);
        return rebuild;
    }
}
