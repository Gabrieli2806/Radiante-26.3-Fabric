package com.g2806.radiante.client.profile;

/**
 * How well a compatibility parameter is known. Every value in {@link BedrockRtxProfile} carries one, so a number that
 * that matches Bedrock is never mistaken for one that was guessed.
 */
public enum ParameterStatus {
    /** Taken from Bedrock's public pack format or a public description of it. */
    VERIFIED,
    /** Matches Bedrock RTX's observed behaviour (an equation, a constant, a rule); written independently. */
    MATCHES_BEDROCK,
    /** A rule Bedrock evidently applies but that looks like a tuning trick, not a model; reproduce only once measured. */
    HEURISTIC,
    /** Fitted to captures of vanilla Bedrock RTX against Radiante, with the capture set recorded. */
    MEASURED,
    /** Follows from verified structure or from observable behaviour, but nothing confirms the value itself. */
    INFERRED,
    /** A plausible stand-in that produces the right kind of effect; conservative until measured. */
    APPROXIMATE,
    /** Bedrock's value or algorithm is not known; the parameter exists so it can be set when it is. */
    UNKNOWN
}
