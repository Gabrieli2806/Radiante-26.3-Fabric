package com.g2806.radiante.client.profile;

/**
 * One setting a renderer profile manages: the pipeline attribute it sets, the value the Bedrock RTX profile gives it,
 * the value Radiante's own look gives it, and how well the Bedrock value is known.
 *
 * @param group the part of the renderer it belongs to ("exposure", "toneMapping", "bloom", ...)
 * @param module the pipeline module that owns the attribute, as {@code Pipeline.*_MODULE_NAME}; null for an
 * attribute of the ray tracing shader pack
 * @param attribute the attribute's name in that module
 * @param bedrockValue the value under {@link RendererProfile#BEDROCK_RTX}
 * @param defaultValue the value under {@link RendererProfile#DEFAULT}; what the module's yaml declares
 * @param status how well {@code bedrockValue} is known
 * @param source where it comes from, or why it is a stand-in
 */
public record CompatParameter(String group, String module, String attribute, String bedrockValue,
    String defaultValue, ParameterStatus status, String source) {
}
