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

    /** Called every client tick by RadianteClient; does nothing unless a script was given. */
    static void tick(Minecraft minecraft) {
        if (!active) {
            return;
        }
        clientTicks++;
        while (nextPre < PRE_STEPS.size() && PRE_STEPS.get(nextPre).tick() <= clientTicks) {
            run(minecraft, PRE_STEPS.get(nextPre++).action());
        }

        if (minecraft.player == null || minecraft.level == null) {
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
        } else if (action.startsWith("packs=")) {
            String value = action.substring(6);
            minecraft.options.resourcePacks.clear();
            if (!value.equals("none")) {
                for (String pack : value.split(",")) {
                    minecraft.options.resourcePacks.add(pack);
                }
            }
            minecraft.reloadResourcePacks();
            RadianteClient.LOGGER.info("[dev] packs {}", value);
        } else if (action.equals("reload")) {
            minecraft.reloadResourcePacks();
            RadianteClient.LOGGER.info("[dev] reload");
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
