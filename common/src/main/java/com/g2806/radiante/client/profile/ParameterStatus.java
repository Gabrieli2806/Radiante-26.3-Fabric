package com.g2806.radiante.client.profile;

/**
 * How well a compatibility parameter is known. Every value in {@link BedrockRtxProfile} carries one, so a number that
 * was read from Bedrock's own data is never mistaken for one that was guessed. See
 * docs/bedrock-rtx-compat/PARAMETER_STATUS.md.
 */
public enum ParameterStatus {
    /** Read from Bedrock's own files or a public specification of them (a pass layout, a uniform name). */
    VERIFIED,
    /**
     * The behaviour (an equation, a constant, a rule) was established by studying the compiled Bedrock shader
     * privately; the specification is in docs/bedrock-rtx-compat/research/ and the implementation was written from
     * it, not from the shader.
     */
    VERIFIED_FROM_DXIL,
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
