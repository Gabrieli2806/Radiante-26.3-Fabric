package com.g2806.radiante.client.render;

/**
 * Per-frame CPU timings of the renderer's own steps, for development. With the {@code RADIANTE_DEV_PROFILE}
 * environment variable set the average of each step is logged every five seconds, and every slow frame whole. With
 * debug logging on instead, only the five second windows that ran slow are logged, with how much was traced: what
 * a player on a server where the frame rate drops can send back.
 */
final class DevProfiler {

    private static final boolean DEV = System.getenv("RADIANTE_DEV_PROFILE") != null;
    static boolean ENABLED = DEV;
    /** Windows slower than this are logged when only debug logging asks for the profile. */
    private static final int SLOW_FPS = 45;
    private static long entities;
    private static long layers;
    private static long vertices;
    private static long reused;
    /** The entities step, taken apart. */
    private static final String[] PARTS = {"mobs", "find block entities", "block entities", "other", "upload",
        "build"};
    private static final long[] PART_TOTALS = new long[PARTS.length];
    private static long partLast;

    static void partsBegin() {
        if (ENABLED) {
            partLast = System.nanoTime();
        }
    }

    static void part(int part) {
        if (ENABLED) {
            long now = System.nanoTime();
            PART_TOTALS[part] += now - partLast;
            partLast = now;
        }
    }

    /** A block entity whose kept geometry was handed back instead of being collected again. */
    static void reused() {
        if (ENABLED) {
            reused++;
        }
    }

    /** What was collected anew this window, by kind: how many, and their vertices. */
    private static final java.util.Map<String, long[]> KINDS = new java.util.HashMap<>();
    private static String kind;

    /** Names what the next {@link #count} is, or null for something kept from an earlier frame. */
    static void kind(Object state) {
        kind = !ENABLED || state == null ? null : state.getClass().getSimpleName();
    }

    /** One traced entity, block entity or other instance, with what it is made of. */
    static void count(int entityLayers, int entityVertices) {
        if (ENABLED) {
            entities++;
            layers += entityLayers;
            vertices += entityVertices;
            if (kind != null) {
                long[] totals = KINDS.computeIfAbsent(kind, key -> new long[2]);
                totals[0]++;
                totals[1] += entityVertices;
            }
        }
    }
    private static final String[] NAMES = {"uniforms", "mapping", "chunks", "occlusion", "entities", "native",
        "submit"};
    private static final long[] TOTALS = new long[NAMES.length];
    private static long last;
    private static long windowStart;
    private static int frames;
    /** This frame's steps, and when the previous frame ended: a frame far slower than the rest is logged whole. */
    private static final long[] FRAME = new long[NAMES.length];
    private static long lastFrameEnd;
    private static final long SPIKE_NANOS = 30_000_000L;

    private DevProfiler() {
    }

    /**
     * Where the render thread is, looked at every few milliseconds from a thread of its own: the steps above only
     * cover Radiante's part of a frame, and this says what the rest goes to - Minecraft's own work, or waiting for
     * the graphics card.
     */
    private static final java.util.Map<String, int[]> SAMPLES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger SAMPLE_COUNT =
        new java.util.concurrent.atomic.AtomicInteger();
    private static Thread sampler;

    private static void startSampler() {
        Thread renderThread = Thread.currentThread();
        sampler = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(4L);
                } catch (InterruptedException e) {
                    return;
                }
                if (!ENABLED) {
                    continue;
                }
                StackTraceElement[] stack = renderThread.getStackTrace();
                if (stack.length == 0) {
                    continue;
                }
                // The innermost frame, and the innermost one of the game or the mod: "what" and "on whose behalf".
                String top = shortName(stack[0]);
                String owner = "";
                for (StackTraceElement frame : stack) {
                    String name = frame.getClassName();
                    if (name.startsWith("net.minecraft.") || name.startsWith("com.g2806.")
                        || name.startsWith("com.mojang.")) {
                        owner = shortName(frame);
                        break;
                    }
                }
                String key = top.equals(owner) || owner.isEmpty() ? top : top + " < " + owner;
                SAMPLES.computeIfAbsent(key, k -> new int[1])[0]++;
                SAMPLE_COUNT.incrementAndGet();
            }
        }, "Radiante profile sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static String shortName(StackTraceElement frame) {
        String name = frame.getClassName();
        return name.substring(name.lastIndexOf('.') + 1) + "." + frame.getMethodName();
    }

    static void begin() {
        ENABLED = DEV || com.g2806.radiante.client.option.Options.debugLogging;
        if (ENABLED && sampler == null) {
            startSampler();
        }
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
        FRAME[step] = now - last;
        last = now;
    }

    static void endFrame() {
        if (!ENABLED) {
            return;
        }
        frames++;
        long now = System.nanoTime();
        if (DEV && lastFrameEnd != 0 && now - lastFrameEnd > SPIKE_NANOS) {
            StringBuilder spike = new StringBuilder("[profile] spike ")
                .append(String.format("%.1f", (now - lastFrameEnd) / 1.0e6)).append("ms:");
            for (int i = 0; i < NAMES.length; i++) {
                spike.append(' ').append(NAMES[i]).append('=').append(String.format("%.2f", FRAME[i] / 1.0e6));
            }
            RadianteRenderer.LOGGER.info(spike.toString());
        }
        lastFrameEnd = now;
        java.util.Arrays.fill(FRAME, 0L);
        if (now - windowStart < 5_000_000_000L) {
            return;
        }
        double seconds = (now - windowStart) / 1.0e9;
        StringBuilder line = new StringBuilder("[profile] fps ").append(Math.round(frames / seconds));
        for (int i = 0; i < NAMES.length; i++) {
            line.append(' ').append(NAMES[i]).append('=')
                .append(String.format("%.2f", TOTALS[i] / 1.0e6 / frames)).append("ms");
            TOTALS[i] = 0;
        }
        line.append(" | per frame: instances=").append(entities / frames).append(" layers=").append(layers / frames)
            .append(" vertices=").append(vertices / frames).append(" kept block entities=")
            .append(reused / frames);
        line.append(" | entities:");
        for (int i = 0; i < PARTS.length; i++) {
            line.append(' ').append(PARTS[i]).append('=')
                .append(String.format("%.2f", PART_TOTALS[i] / 1.0e6 / frames)).append("ms");
            PART_TOTALS[i] = 0;
        }
        line.append(" | collected per frame:");
        final int windowFrames = frames;
        KINDS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(10)
            .forEach(entry -> line.append(' ').append(entry.getKey()).append('=')
                .append(entry.getValue()[0] / windowFrames).append('/').append(entry.getValue()[1] / windowFrames)
                .append('v'));
        KINDS.clear();
        int samples = Math.max(1, SAMPLE_COUNT.getAndSet(0));
        line.append(" | render thread:");
        SAMPLES.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0])).limit(14)
            .forEach(entry -> line.append(' ').append(entry.getKey()).append('=')
                .append(entry.getValue()[0] * 100 / samples).append('%'));
        SAMPLES.clear();
        if (DEV || frames / seconds < SLOW_FPS) {
            RadianteRenderer.LOGGER.info(line.toString());
        }
        entities = 0;
        reused = 0;
        layers = 0;
        vertices = 0;
        frames = 0;
        windowStart = now;
    }
}
