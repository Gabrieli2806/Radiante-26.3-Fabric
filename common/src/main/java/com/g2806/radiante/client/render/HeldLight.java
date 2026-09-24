package com.g2806.radiante.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The light of whatever the player holds: a torch, lantern, glowstone, a lava bucket. Vanilla has no such light;
 * the item only glows itself. Here it becomes a small point light a little in front of the player, bright in
 * proportion to the block's own light level, which the shaders sample like a block light.
 */
public final class HeldLight {

    /** Radiance of a light level 15 item. */
    private static final float STRENGTH = 6.0f;
    /** How far a level 15 light reaches, in blocks; vanilla light fades out over the same distance. */
    private static final float REACH = 15.0f;

    private static final Vector4f NONE = new Vector4f(0.0f);

    private HeldLight() {
    }

    /** Camera-relative position (xyz) and reach (w); zero when nothing lit is held. */
    public static Vector4f position(Minecraft minecraft, Vec3 camera, float partialTicks) {
        LocalPlayer player = minecraft.player;
        if (player == null || level(player) <= 0) {
            return NONE;
        }
        // Chest height, a little ahead: roughly where a held torch is, and outside the player's own model.
        Vec3 look = player.getViewVector(partialTicks);
        Vec3 eye = player.getEyePosition(partialTicks);
        Vec3 at = eye.add(look.x * 0.45, look.y * 0.45 - 0.35, look.z * 0.45);
        return new Vector4f((float) (at.x - camera.x), (float) (at.y - camera.y), (float) (at.z - camera.z),
            REACH * level(player) / 15.0f);
    }

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

    private static int level(LocalPlayer player) {
        ItemStack stack = litStack(player);
        return stack == null ? 0 : levelOf(stack);
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
