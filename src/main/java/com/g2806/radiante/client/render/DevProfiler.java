package com.g2806.radiante.client.render;

/**
 * Per-frame CPU timings of the renderer's own steps, for development. Inert unless the {@code RADIANTE_DEV_PROFILE}
 * environment variable is set; then the average of each step is logged every five seconds.
 */
final class DevProfiler {

    static final boolean ENABLED = System.getenv("RADIANTE_DEV_PROFILE") != null;
    private static final String[] NAMES = {"uniforms", "mapping", "chunks", "occlusion", "entities", "native",
        "submit"};
    private static final long[] TOTALS = new long[NAMES.length];
    private static long last;
    private static long windowStart;
    private static int frames;

    private DevProfiler() {
    }

    static void begin() {
        if (ENABLED) {
            last = System.nanoTime();
            if (windowStart == 0) {
                windowStart = last;
            }
        }
    }

    static void mark(int step) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        TOTALS[step] += now - last;
        last = now;
    }

    static void endFrame() {
        if (!ENABLED) {
            return;
        }
        frames++;
        long now = System.nanoTime();
        if (now - windowStart < 5_000_000_000L) {
            return;
        }
        StringBuilder line = new StringBuilder("[profile] fps ").append(frames / 5);
        for (int i = 0; i < NAMES.length; i++) {
            line.append(' ').append(NAMES[i]).append('=')
                .append(String.format("%.2f", TOTALS[i] / 1.0e6 / frames)).append("ms");
            TOTALS[i] = 0;
        }
        RadianteRenderer.LOGGER.info(line.toString());
        frames = 0;
        windowStart = now;
    }
}
