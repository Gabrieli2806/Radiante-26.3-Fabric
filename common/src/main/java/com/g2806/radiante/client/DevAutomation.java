package com.g2806.radiante.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Scripted test runs for development, driven entirely by the {@code RADIANTE_DEV_SCRIPT} environment variable and
 * inert without it. The script is a {@code ;}-separated list of {@code tick:action} steps counted from the moment
 * the player is in a world, where the action is {@code shot=name} (screenshot to {@code screenshots/name.png}),
 * {@code cmd=/command} (run a chat command), {@code break=x,y,z,stage} (show block cracks), {@code packs=a,b} or {@code packs=none} (set the
 * resource packs and reload), {@code reload} (reload resource packs), {@code fps} (log
 * the frame rate) or {@code quit}.
 */
public final class DevAutomation {

    private record Step(int tick, String action) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    /** Steps counted from the client's first tick, for everything that has to happen before a world is open. */
    private static final List<Step> PRE_STEPS = new ArrayList<>();
    private static int clientTicks = -1;
    private static int nextPre;
    private static int worldTicks = -1;
    private static int next;
    private static boolean active;
    /** A test world is being opened: its experimental-settings backup prompt is answered here, not by a person. */
    private static boolean openingTestWorld;
    private static net.minecraft.client.gui.screens.Screen answeredBackup;
    private static final String TEST_WORLD_PREFIX = "Radiante";

    private DevAutomation() {
    }

    public static void register() {
        String preScript = System.getenv("RADIANTE_DEV_PRESCRIPT");
        if (preScript != null && !preScript.isBlank()) {
            for (String part : preScript.split(";")) {
                int colon = part.indexOf(':');
                if (colon > 0) {
                    PRE_STEPS.add(new Step(Integer.parseInt(part.substring(0, colon).trim()),
                        part.substring(colon + 1)));
                }
            }
            PRE_STEPS.sort((a, b) -> Integer.compare(a.tick(), b.tick()));
        }

        String script = System.getenv("RADIANTE_DEV_SCRIPT");
        if (script == null || script.isBlank()) {
            active = !PRE_STEPS.isEmpty();
            return;
        }
        for (String part : script.split(";")) {
            int colon = part.indexOf(':');
            if (colon > 0) {
                STEPS.add(new Step(Integer.parseInt(part.substring(0, colon).trim()), part.substring(colon + 1)));
            }
        }
        STEPS.sort((a, b) -> Integer.compare(a.tick(), b.tick()));
        active = true;
        RadianteClient.LOGGER.info("[dev] script with {} steps", STEPS.size());
    }

    /** Opens a test world, creating it as a flat creative world first if needed; only names with the test prefix. */
    private static void openOrCreateTestWorld(Minecraft minecraft, String name) {
        if (!name.startsWith(TEST_WORLD_PREFIX)) {
            RadianteClient.LOGGER.warn("[dev] refusing test world {}: name must start with {}", name, TEST_WORLD_PREFIX);
            return;
        }
        openingTestWorld = true;
        if (minecraft.getLevelSource().levelExists(name)) {
            minecraft.createWorldOpenFlows().openWorld(name,
                () -> RadianteClient.LOGGER.info("[dev] opening test world {} failed", name));
            RadianteClient.LOGGER.info("[dev] open test world {}", name);
            return;
        }
        net.minecraft.world.level.LevelSettings settings = new net.minecraft.world.level.LevelSettings(name,
            net.minecraft.world.level.GameType.CREATIVE, net.minecraft.world.level.LevelSettings.DifficultySettings.DEFAULT,
            true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(name, settings,
            new net.minecraft.world.level.levelgen.WorldOptions(name.hashCode(), false, false),
            // "Terrain" in the name asks for real terrain (hills, trees, water) instead of a flat test world.
            name.contains("Terrain") ? net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions
                : net.minecraft.world.level.levelgen.presets.WorldPresets::createTestWorldDimensions,
            new net.minecraft.client.gui.screens.TitleScreen());
        RadianteClient.LOGGER.info("[dev] create test world {}", name);
    }

    private static void skipBackup(net.minecraft.client.gui.screens.BackupConfirmScreen screen) {
        try {
            java.lang.reflect.Field field =
                net.minecraft.client.gui.screens.BackupConfirmScreen.class.getDeclaredField("onProceed");
            field.setAccessible(true);
            ((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener) field.get(screen)).proceed(false, false);
            RadianteClient.LOGGER.info("[dev] test world backup prompt skipped");
        } catch (ReflectiveOperationException e) {
            RadianteClient.LOGGER.warn("[dev] could not answer the backup prompt", e);
            openingTestWorld = false;
        }
    }

    private static boolean isTestWorld(Minecraft minecraft) {
        var server = minecraft.getSingleplayerServer();
        return server != null && server.getWorldData().getLevelName().startsWith(TEST_WORLD_PREFIX);
    }

    /** Called every client tick by RadianteClient; does nothing unless a script was given. */
    static void tick(Minecraft minecraft) {
        if (!active) {
            return;
        }
        clientTicks++;
        while (nextPre < PRE_STEPS.size() && PRE_STEPS.get(nextPre).tick() <= clientTicks) {
            run(minecraft, PRE_STEPS.get(nextPre++).action());
        }

        if (openingTestWorld
            && minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.BackupConfirmScreen backup) {
            // Test worlds are flat worlds with experimental settings, so opening one asks for a backup first. The
            // answer is carried out a few ticks later and the prompt stays up meanwhile; answering it twice opened the
            // world twice, and the second server found the world's lock already taken.
            if (backup != answeredBackup) {
                answeredBackup = backup;
                skipBackup(backup);
            }
        }
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        openingTestWorld = false;
        if (worldTicks < 0 && !isTestWorld(minecraft)) {
            // Scripts teleport, fill and summon; run in a player's own world they would damage it.
            RadianteClient.LOGGER.warn("[dev] world is not a test world (name must start with {}); script stopped",
                TEST_WORLD_PREFIX);
            active = false;
            return;
        }
        worldTicks++;
        while (next < STEPS.size() && STEPS.get(next).tick() <= worldTicks) {
            run(minecraft, STEPS.get(next++).action());
        }
    }

    private static void run(Minecraft minecraft, String action) {
        if (action.startsWith("shot=")) {
            String name = action.substring(5) + ".png";
            Screenshot.grab(minecraft.gameDirectory, name, minecraft.gameRenderer.mainRenderTarget(), 1,
                message -> RadianteClient.LOGGER.info("[dev] screenshot {}", name));
        } else if (action.startsWith("cmd=/")) {
            minecraft.player.connection.sendCommand(action.substring(5));
            RadianteClient.LOGGER.info("[dev] command {}", action.substring(4));
        } else if (action.startsWith("break=")) {
            String[] v = action.substring(6).split(",");
            minecraft.level.destroyBlockProgress(-4242, new net.minecraft.core.BlockPos(Integer.parseInt(v[0]),
                Integer.parseInt(v[1]), Integer.parseInt(v[2])), Integer.parseInt(v[3]));
        } else if (action.startsWith("fps=")) {
            // Frames drawn in the last second, for comparing settings from a fixed camera.
            RadianteClient.LOGGER.info("[dev] fps {} {}", action.substring(4), minecraft.getFps());
        } else if (action.startsWith("rainmv=")) {
            com.g2806.radiante.client.render.EntityManager.rainMotion = Boolean.parseBoolean(action.substring(7));
        } else if (action.startsWith("hold=") || action.startsWith("release=")) {
            // hold=forward / release=forward (also sprint, jump): walk in a test world to see bobbing and motion.
            boolean down = action.startsWith("hold=");
            String key = action.substring(action.indexOf('=') + 1);
            net.minecraft.client.KeyMapping mapping = switch (key) {
                case "sprint" -> minecraft.options.keySprint;
                case "jump" -> minecraft.options.keyJump;
                case "back" -> minecraft.options.keyDown;
                default -> minecraft.options.keyUp;
            };
            mapping.setDown(down);
            RadianteClient.LOGGER.info("[dev] {} {}", down ? "hold" : "release", key);
        } else if (action.startsWith("rt=")) {
            // As the toggle key does it (RadianteClient), without touching the saved options.
            com.g2806.radiante.client.option.Options.rayTracingEnabled = Boolean.parseBoolean(action.substring(3));
            minecraft.levelExtractor.allChanged();
            RadianteClient.LOGGER.info("[dev] ray tracing {}",
                com.g2806.radiante.client.option.Options.rayTracingEnabled);
        } else if (action.startsWith("opt=")) {
            // opt=field=value: any Radiante option, for comparing looks in one run. Not saved.
            String[] parts = action.substring(4).split("=", 2);
            try {
                java.lang.reflect.Field field =
                    com.g2806.radiante.client.option.Options.class.getField(parts[0]);
                Class<?> type = field.getType();
                Object value = type == boolean.class ? Boolean.parseBoolean(parts[1])
                    : type == int.class ? Integer.parseInt(parts[1])
                    : type == float.class ? Float.parseFloat(parts[1]) : parts[1];
                field.set(null, value);
                RadianteClient.LOGGER.info("[dev] option {} = {}", parts[0], value);
            } catch (ReflectiveOperationException | RuntimeException e) {
                RadianteClient.LOGGER.warn("[dev] option {} not set", action, e);
            }
        } else if (action.startsWith("pixel=")) {
            com.g2806.radiante.client.option.Options.pixelLighting = Boolean.parseBoolean(action.substring(6));
            RadianteClient.LOGGER.info("[dev] pixel lighting {}",
                com.g2806.radiante.client.option.Options.pixelLighting);
        } else if (action.startsWith("packs=")) {
            String value = action.substring(6);
            // The options list is only read at startup; the repository's selection is what a reload applies.
            var repository = minecraft.getResourcePackRepository();
            List<String> selected = new ArrayList<>();
            if (!value.equals("none")) {
                for (String pack : value.split(",")) {
                    selected.add(pack);
                }
            }
            repository.setSelected(selected);
            minecraft.options.updateResourcePacks(repository);
            RadianteClient.LOGGER.info("[dev] packs {}", value);
        } else if (action.equals("reload")) {
            minecraft.reloadResourcePacks();
            RadianteClient.LOGGER.info("[dev] reload");
        } else if (action.startsWith("rejoin=")) {
            // Leave to the title screen and open the test world again; world ticks pause meanwhile.
            minecraft.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
            RadianteClient.LOGGER.info("[dev] left the world");
            openOrCreateTestWorld(minecraft, action.substring(7));
        } else if (action.startsWith("testworld=")) {
            openOrCreateTestWorld(minecraft, action.substring(10));
        } else if (action.startsWith("join=")) {
            String levelId = action.substring(5);
            minecraft.createWorldOpenFlows().openWorld(levelId, () -> RadianteClient.LOGGER.info("[dev] join failed"));
            RadianteClient.LOGGER.info("[dev] join {}", levelId);
        } else if (action.startsWith("fov=")) {
            minecraft.options.fov().set(Integer.parseInt(action.substring(4)));
            RadianteClient.LOGGER.info("[dev] fov {}", action.substring(4));
        } else if (action.startsWith("slot=")) {
            int slot = Integer.parseInt(action.substring(5));
            minecraft.player.getInventory().setSelectedSlot(slot);
            RadianteClient.LOGGER.info("[dev] slot {}", slot);
        } else if (action.startsWith("debug=")) {
            String value = action.substring(6);
            net.minecraft.resources.Identifier entry = switch (value) {
                case "hitboxes" -> net.minecraft.client.gui.components.debug.DebugScreenEntries.ENTITY_HITBOXES;
                case "chunkborders" -> net.minecraft.client.gui.components.debug.DebugScreenEntries.CHUNK_BORDERS;
                default -> null;
            };
            if (entry != null) {
                minecraft.debugEntries.toggleStatus(entry);
            }
            RadianteClient.LOGGER.info("[dev] debug {}", value);
        } else if (action.startsWith("camera=")) {
            String value = action.substring(7);
            minecraft.options.setCameraType(value.equals("third") ?
                    net.minecraft.client.CameraType.THIRD_PERSON_BACK :
                    value.equals("front") ? net.minecraft.client.CameraType.THIRD_PERSON_FRONT :
                                            net.minecraft.client.CameraType.FIRST_PERSON);
            RadianteClient.LOGGER.info("[dev] camera {}", value);
        } else if (action.equals("options")) {
            minecraft.gui.setScreen(new com.g2806.radiante.client.gui.RadianteOptionsScreen(null, minecraft.options));
            RadianteClient.LOGGER.info("[dev] options screen");
        } else if (action.equals("fps")) {
            RadianteClient.LOGGER.info("[dev] fps {} at tick {}", minecraft.getFps(), worldTicks);
        } else if (action.equals("quit")) {
            minecraft.stop();
        }
    }
}
