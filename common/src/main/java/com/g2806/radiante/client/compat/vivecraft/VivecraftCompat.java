package com.g2806.radiante.client.compat.vivecraft;

import com.g2806.radiante.client.RadianteClient;
import java.lang.reflect.Method;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Vivecraft draws the world several times a frame: once per eye, and between those, now and then, the views of its
 * handheld camera, telescopes and desktop mirror, at sizes of their own. The tracer keeps one set of images, so
 * every change of size rebuilt the whole pipeline, twice a second while the camera was out, until the GPU hung.
 * Those extra views are now traced at the eyes' size and scaled into their targets.
 *
 * <p>In VR the hands are not where vanilla puts them: Vivecraft draws them at the controllers. The tracer's own
 * first-person hands (and the light of what they hold) sat in front of the face instead; they are left to Vivecraft
 * and the held light follows the controllers.
 *
 * <p>Only Vivecraft's public API is used, looked up by name, so the mod is not needed to build or run Radiante.
 */
public final class VivecraftCompat {

    private static boolean installed;
    private static Object clientApi;
    private static Object renderingApi;
    private static Method isVrActive;
    private static Method currentRenderPass;
    private static Method handRenderPos;

    private VivecraftCompat() {
    }

    /** Notes whether Vivecraft is installed; its API is looked up on first use, once it has started. */
    public static void init() {
        try {
            Class.forName("org.vivecraft.api.client.VRClientAPI", false, VivecraftCompat.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError notInstalled) {
            return;
        }
        installed = true;
        RadianteClient.LOGGER.info("Vivecraft found: its extra views are traced at the eyes' resolution");
    }

    private static boolean resolve() {
        if (clientApi != null) {
            return true;
        }
        try {
            ClassLoader loader = VivecraftCompat.class.getClassLoader();
            Class<?> client = Class.forName("org.vivecraft.api.client.VRClientAPI", false, loader);
            Class<?> rendering = Class.forName("org.vivecraft.api.client.VRRenderingAPI", false, loader);
            isVrActive = client.getMethod("isVRActive");
            currentRenderPass = rendering.getMethod("getCurrentRenderPass");
            handRenderPos = rendering.getMethod("getHandRenderPos", InteractionHand.class);
            renderingApi = rendering.getMethod("instance").invoke(null);
            clientApi = client.getMethod("instance").invoke(null);
            return clientApi != null && renderingApi != null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            disable(e);
            return false;
        }
    }

    /** True while the game is being played in VR. */
    public static boolean isVrActive() {
        if (!installed || !resolve()) {
            return false;
        }
        try {
            return (Boolean) isVrActive.invoke(clientApi);
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable(e);
            return false;
        }
    }

    /**
     * True when the view being drawn is one of the player's eyes (or the centre view between them), the views the
     * tracer's resolution and history belong to. False for the camera, telescopes, mirror and third person.
     */
    public static boolean isEyePass() {
        String pass = currentPassName();
        return pass == null || pass.equals("LEFT") || pass.equals("RIGHT") || pass.equals("CENTER")
            || pass.equals("VANILLA");
    }

    /** Where Vivecraft draws the given hand this frame, in the world; null outside VR. */
    public static Vec3 handPosition(InteractionHand hand) {
        if (!isVrActive()) {
            return null;
        }
        try {
            return (Vec3) handRenderPos.invoke(renderingApi, hand);
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable(e);
            return null;
        }
    }

    private static String currentPassName() {
        if (!isVrActive()) {
            return null;
        }
        try {
            Object pass = currentRenderPass.invoke(renderingApi);
            return pass == null ? null : ((Enum<?>) pass).name();
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable(e);
            return null;
        }
    }

    private static void disable(Throwable e) {
        installed = false;
        RadianteClient.LOGGER.warn("Vivecraft bridge switched off: {}", e.toString());
    }
}
