package com.g2806.radiante.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Builds a scene with the things that are hard to judge from a log (a written sign, an end portal, glowing eyes, a
 * held torch, the sky) and photographs it, so a render can be checked without a person having to walk there.
 * Only runs when started with -Dradiante.smokeTest=true, and only ever in the world it was pointed at.
 */
public final class SmokeTest {

    private static final String PROPERTY = "radiante.smokeTest";
    /** Ticks between steps: enough for chunks, entities and the denoiser to settle before a photograph. */
    private static final int STEP_TICKS = 40;

    private static final List<Runnable> STEPS = new ArrayList<>();
    private static int tick;
    private static int step;
    private static boolean started;

    private SmokeTest() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static void register() {
        if (!enabled()) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(SmokeTest::onTick);
        RadianteClient.LOGGER.info("Smoke test armed");
    }

    private static void onTick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            return;
        }

        if (!started) {
            started = true;
            buildScript();
        }

        if (++tick < STEP_TICKS) {
            return;
        }
        tick = 0;

        if (step >= STEPS.size()) {
            RadianteClient.LOGGER.info("Smoke test finished");
            minecraft.stop();
            return;
        }
        STEPS.get(step++).run();
    }

    private static void command(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand(command);
        }
    }

    private static void shot(String name) {
        Minecraft minecraft = Minecraft.getInstance();
        RadianteClient.LOGGER.info("Smoke test shot: {}", name);
        Screenshot.grab(minecraft.gameDirectory, "smoke-" + name + ".png", minecraft.gameRenderer.mainRenderTarget(),
            1, message -> RadianteClient.LOGGER.info("Smoke test saved {}", name));
    }

    /**
     * The scene is built on a flat platform high above the world so nothing of the terrain interferes, and every
     * subject is placed at a known offset from where the camera is pointed.
     */
    private static void buildScript() {
        Minecraft minecraft = Minecraft.getInstance();
        // The interface would cover half of every photograph, and the chat would cover the rest.
        if (!minecraft.gui.hud.isHidden()) {
            minecraft.gui.hud.toggle();
        }

        STEPS.add(() -> {
            command("gamemode creative");
            command("gamerule doDaylightCycle false");
            command("gamerule doMobSpawning false");
            command("gamerule sendCommandFeedback false");
            command("weather clear");
            command("time set noon");
        });
        STEPS.add(() -> {
            command("fill -10 198 -10 10 198 10 minecraft:stone");
            command("fill -10 199 -10 10 206 10 minecraft:air");
            command("tp @s 0 199 5 180 0");
        });
        STEPS.add(() -> {
            // A full portal, the way one is found: the block itself ringed by its frames, with an end crystal
            // beside it because both are supposed to give off light of their own.
            command("setblock 0 199 0 minecraft:end_portal");
            command("setblock -1 199 0 minecraft:end_portal_frame[facing=east,eye=true]");
            command("setblock 1 199 0 minecraft:end_portal_frame[facing=west,eye=true]");
            command("setblock 0 199 1 minecraft:end_portal_frame[facing=north,eye=true]");
            command("setblock 0 199 -1 minecraft:end_portal_frame[facing=south,eye=true]");
            command("summon minecraft:end_crystal 3 200 0 {ShowBottom:1b}");
        });
        // A working portal sends whoever stands in it to the End, which ended one run on the title screen, and a
        // spectator sees an empty world here. So the camera stays on the floor beside the portal, never over it.
        STEPS.add(() -> command("tp @s 0 199 2 180 55"));
        STEPS.add(() -> shot("A-end-portal-close"));
        STEPS.add(() -> command("tp @s 0 199 4 180 25"));
        STEPS.add(() -> shot("B-end-portal-wide"));
        STEPS.add(() -> command("tp @s 3 199 4 180 10"));
        STEPS.add(() -> shot("C-end-crystal"));
    }
}
