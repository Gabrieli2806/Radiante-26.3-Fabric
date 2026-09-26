package com.g2806.radiante.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The light of whatever the player holds: a torch, lantern, glowstone, a lava bucket. Vanilla has no such light;
 * the item only glows itself. Here it becomes a small point light where the item is held, bright in proportion
 * to the block's own light level, which the shaders sample like a block light.
 */
public final class HeldLight {

    /** Radiance of a light level 15 item. */
    private static final float STRENGTH = 6.0f;
    /** How far a level 15 light reaches, in blocks; vanilla light fades out over the same distance. */
    private static final float REACH = 15.0f;

    private static final Vector4f NONE = new Vector4f(0.0f);

    private HeldLight() {
    }

    /**
     * Camera-relative position (xyz) and reach (w); zero when nothing lit is held. The light sits where the item
     * is: in first person where vanilla draws the hand holding it, lower right or lower left of the view; seen
     * from outside, at that hand beside the player's body.
     */
    public static Vector4f position(Minecraft minecraft, Vec3 camera, float partialTicks) {
        LocalPlayer player = minecraft.player;
        ItemStack stack = player == null ? null : litStack(player);
        if (stack == null) {
            return NONE;
        }
        boolean mainHand = stack == player.getItemInHand(InteractionHand.MAIN_HAND);
        boolean rightArm = (player.getMainArm() == HumanoidArm.RIGHT) == mainHand;
        double side = rightArm ? 1.0 : -1.0;
        Vec3 eye = player.getEyePosition(partialTicks);
        Vec3 at;
        if (minecraft.options.getCameraType().isFirstPerson()) {
            // Vanilla places a held item about half a block to the side, half down and three quarters ahead of
            // the eye, turning with the view.
            Vec3 forward = player.getViewVector(partialTicks);
            // From the yaw alone, so looking straight up or down still has a side.
            Vec3 right = Vec3.directionFromRotation(0.0f, player.getViewYRot(partialTicks)).cross(UP).normalize();
            Vec3 up = right.cross(forward);
            at = eye.add(forward.scale(0.7)).add(right.scale(0.45 * side)).add(up.scale(-0.4));
        } else {
            // A hanging arm's hand, beside the body and a little ahead of it.
            float bodyYaw = net.minecraft.util.Mth.rotLerp(partialTicks, player.yBodyRotO, player.yBodyRot);
            Vec3 forward = Vec3.directionFromRotation(0.0f, bodyYaw);
            Vec3 right = forward.cross(UP).normalize();
            at = eye.add(0.0, -0.95 * player.getScale(), 0.0).add(right.scale(0.4 * side)).add(forward.scale(0.15));
        }
        return new Vector4f((float) (at.x - camera.x), (float) (at.y - camera.y), (float) (at.z - camera.z),
            REACH * levelOf(stack) / 15.0f);
    }

    private static final Vec3 UP = new Vec3(0.0, 1.0, 0.0);

    /** Radiance (rgb) and 1 in w while a lit item is held, all zero otherwise. */
    public static Vector4f color(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return NONE;
        }
        ItemStack stack = litStack(player);
        int level = stack == null ? 0 : levelOf(stack);
        if (level <= 0) {
            return NONE;
        }
        Vector3f tint = tintOf(stack);
        float strength = STRENGTH * level / 15.0f;
        return new Vector4f(tint.x * strength, tint.y * strength, tint.z * strength, 1.0f);
    }

    /** The brighter of the two hands, or null when neither holds a light. */
    private static ItemStack litStack(LocalPlayer player) {
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        int mainLevel = levelOf(main);
        int offLevel = levelOf(off);
        if (mainLevel <= 0 && offLevel <= 0) {
            return null;
        }
        return mainLevel >= offLevel ? main : off;
    }

    private static int levelOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.is(Items.LAVA_BUCKET)) {
            return 15;
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            return blockItem.getBlock().defaultBlockState().getLightEmission();
        }
        return 0;
    }

    private static Vector3f tintOf(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (id.contains("soul")) {
            return new Vector3f(0.45f, 0.85f, 1.0f);
        }
        if (id.contains("redstone")) {
            return new Vector3f(1.0f, 0.25f, 0.15f);
        }
        if (id.contains("sea_lantern") || id.contains("end_rod") || id.contains("froglight")
            || id.contains("beacon") || id.contains("conduit")) {
            return new Vector3f(0.9f, 0.95f, 1.0f);
        }
        if (id.contains("lava") || id.contains("magma")) {
            return new Vector3f(1.0f, 0.5f, 0.2f);
        }
        return new Vector3f(1.0f, 0.78f, 0.5f);
    }
}
