package com.g2806.radiante.client.bedrock;

/**
 * How the material channels of a texture were authored, i.e. what the numbers in them mean.
 *
 * <p>Radiante renders from LabPBR maps (the {@code _s} and {@code _n} atlases). A Bedrock RTX pack authors its
 * materials differently (metalness, emissive, roughness and subsurface in the channels of one MER(S) image), and
 * converting that to LabPBR is lossy: emission, for one, was stored as {@code min(254, 4 x value)}. The converter
 * therefore also keeps the original MER(S) data, unchanged, next to the LabPBR maps (see
 * {@link BedrockPackConverter#RAW_MER_DIR}), tagged {@link #BEDROCK_MER}, so a Bedrock-specific renderer profile can
 * apply Bedrock's own transforms to the original values instead of to a pre-cooked approximation.
 *
 * <p>Nothing renders from the raw data yet; this only prevents information loss.
 */
public enum MaterialEncoding {
    /** LabPBR 1.3 specular/normal maps, the format every shader pack and Radiante's renderer use. */
    LAB_PBR,
    /** Bedrock texture-set MER(S): R metalness, G emissive, B roughness, A subsurface (when the set has it). */
    BEDROCK_MER
}
