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
            command("fill -8 199 6 8 205 6 minecraft:stone");
            command("setblock -6 200 5 minecraft:glowstone");
            command("setblock 6 200 5 minecraft:glowstone");
            command("tp @s 0 199 0 0 0");
        });
        STEPS.add(() -> {
            // A wall sign hangs off the wall behind it; a standing sign would pop off with air underneath.
            command("setblock -2 201 5 minecraft:oak_wall_sign[facing=north]"
                + "{front_text:{messages:['{\"text\":\"RADIANTE\"}','{\"text\":\"SIGN TEXT\"}',"
                + "'{\"text\":\"LINE THREE\"}','{\"text\":\"LINE FOUR\"}']}}");
            // A painting is the control: it uses the same render layer as an item frame and already works.
            command("summon minecraft:painting 2 201 5.4 "
                + "{facing:2,variant:\"minecraft:alban\"}");
        });
        STEPS.add(() -> {
            command("summon minecraft:item_frame 0 201 5.4 "
                + "{Facing:2b,Item:{id:\"minecraft:diamond\",count:1}}");
            command("summon minecraft:glow_item_frame 1 201 5.4 {Facing:2b}");
        });
        STEPS.add(() -> command("tp @s 0 200.6 2.5 0 0"));
        STEPS.add(() -> shot("A-wide-sign-frame-painting"));
        STEPS.add(() -> command("tp @s -2 200.9 4.2 0 0"));
        STEPS.add(() -> shot("B-sign-closeup"));
        STEPS.add(() -> command("tp @s 0 200.9 4.2 0 0"));
        STEPS.add(() -> shot("C-item-frame-closeup"));
        STEPS.add(() -> command("tp @s 1 200.9 4.2 0 0"));
        STEPS.add(() -> shot("D-glow-frame-closeup"));
        STEPS.add(() -> command("tp @s 2 200.9 4.2 0 0"));
        STEPS.add(() -> shot("E-painting-closeup"));
    }
}
