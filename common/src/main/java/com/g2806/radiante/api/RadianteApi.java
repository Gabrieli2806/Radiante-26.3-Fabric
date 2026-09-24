package com.g2806.radiante.api;

import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.render.RadianteRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * What other mods may rely on. Everything else in Radiante is internal and can change between versions.
 */
public final class RadianteApi {

    /** How far the sun's path leans south when the vanilla sun path option is off. */
    private static final double SUN_PATH_SOUTH_TILT_DEGREES = 10.0;

    private RadianteApi() {
    }

    /** Whether the world is being ray traced right now, rather than drawn by Minecraft. */
    public static boolean isRayTracingEnabled() {
        return RadianteRenderer.isRayTracingEnabled();
    }

    /**
     * The sky's rotation for a sun angle (radians, Minecraft's {@code SUN_ANGLE} attribute): the sun sits on its
     * local +y, the moon on -y, and x is the axis the sky turns around. It follows the player's sun path option,
     * so it is the sun the renderer draws and lights the world with.
     */
    public static Matrix4f celestialTransform(float sunAngleRadians) {
        return new Matrix4f()
            .rotateX(Options.vanillaSunPath ? 0.0f : (float) Math.toRadians(SUN_PATH_SOUTH_TILT_DEGREES))
            .rotateY((float) Math.toRadians(-90.0))
            .rotateX(sunAngleRadians);
    }

    /** Unit vector from the world towards the sun, in world space. */
    public static Vec3 sunDirection(float sunAngleRadians) {
        Vector3f direction = celestialTransform(sunAngleRadians).transformDirection(new Vector3f(0.0f, 1.0f, 0.0f))
            .normalize();
        return new Vec3(direction.x(), direction.y(), direction.z());
    }
}
