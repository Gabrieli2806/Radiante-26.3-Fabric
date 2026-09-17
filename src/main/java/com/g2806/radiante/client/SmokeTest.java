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
            command("time set midnight");
        });
        STEPS.add(() -> {
            command("fill -10 198 -10 10 198 10 minecraft:stone");
            command("fill -10 199 -10 10 206 10 minecraft:air");
            command("fill -8 199 6 8 205 6 minecraft:stone");
            command("tp @s 0 199 0 0 0");
        });
        STEPS.add(() -> {
            // A wall sign is attached to the wall behind it, so it survives; a standing sign would pop off.
            command("setblock 0 201 5 minecraft:oak_wall_sign[facing=north]"
                + "{front_text:{has_glowing_text:1b,color:\"white\","
                + "messages:['{\"text\":\"RADIANTE\"}','{\"text\":\"SIGN TEXT\"}',"
                + "'{\"text\":\"LINE THREE\"}','{\"text\":\"LINE FOUR\"}']}}");
            command("setblock -4 200 5 minecraft:torch");
            command("setblock 4 200 5 minecraft:glowstone");
            command("setblock 2 200 5 minecraft:end_portal");
            command("setblock -2 200 5 minecraft:nether_portal[axis=x]");
        });
        STEPS.add(() -> command("tp @s 0 200 2 0 0"));
        STEPS.add(() -> shot("01-wall-night"));
        STEPS.add(() -> command("tp @s 0 200.6 3 0 0"));
        STEPS.add(() -> shot("02-sign-closeup"));
        STEPS.add(() -> command("time set noon"));
        STEPS.add(() -> shot("02b-sign-daylight"));
        STEPS.add(() -> command("tp @s 0 200.75 4.2 0 0"));
        STEPS.add(() -> shot("02c-sign-very-close"));
        STEPS.add(() -> command("time set midnight"));
        STEPS.add(() -> command("tp @s 2 200.6 3 0 0"));
        STEPS.add(() -> shot("03-end-portal"));
        STEPS.add(() -> {
            command("tp @s 0 199 0 0 0");
            // Rotation 180 turns them to face the camera; spawned facing away, their eyes are simply not in shot.
            command("summon minecraft:enderman 0 199 4 {NoAI:1b,PersistenceRequired:1b,Silent:1b,Rotation:[180f,0f]}");
            command("summon minecraft:spider 3 199 4 {NoAI:1b,PersistenceRequired:1b,Silent:1b,Rotation:[180f,0f]}");
        });
        STEPS.add(() -> command("tp @s 0 200.8 1.5 0 -5"));
        STEPS.add(() -> shot("04-enderman-eyes"));
        STEPS.add(() -> command("tp @s 3 199.6 2.2 0 0"));
        STEPS.add(() -> shot("04b-spider-eyes"));
        STEPS.add(() -> {
            command("kill @e[type=enderman]");
            command("kill @e[type=spider]");
            command("summon minecraft:zombie 0 199 4 {NoAI:1b,PersistenceRequired:1b,Fire:600s,Rotation:[180f,0f]}");
            command("tp @s 0 199 0 0 0");
        });
        STEPS.add(() -> shot("05-burning-zombie"));
        STEPS.add(() -> {
            command("kill @e[type=zombie]");
            command("item replace entity @s weapon.mainhand with minecraft:torch");
            command("tp @s 0 199 -4 0 0");
        });
        STEPS.add(() -> shot("06-held-torch"));
        STEPS.add(() -> {
            command("time set noon");
            command("tp @s 0 199 0 0 -50");
        });
        STEPS.add(() -> shot("07-sky-a"));
        STEPS.add(() -> shot("08-sky-b"));
        STEPS.add(() -> shot("09-sky-c"));
    }
}
