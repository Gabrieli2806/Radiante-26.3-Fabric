package com.g2806.radiante.mixin.compat;

import com.g2806.radiante.client.render.EntityCollector;
import com.g2806.radiante.client.render.EntityManager;
import com.g2806.radiante.client.render.RadianteRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vivecraft draws the VR hands and what they hold itself, into Minecraft's level render, over the traced world: a
 * held item looked flat and showed in no reflection. While a frame is traced, the arms and held items go to
 * Radiante instead (EntityManager.beginVrHands) and are traced like the rest of the world. Only those: the menu
 * pointer and other VR overlays use Vivecraft's own collector features and stay Vivecraft's.
 *
 * <p>Optional: applied only when Vivecraft is installed, and skipped without error if a Vivecraft version renames
 * the methods - the hands then simply stay as Vivecraft draws them.
 */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.render.helpers.VRArmHelper", remap = false)
public class VivecraftHandsMixin {

    @Unique
    private static EntityCollector radiante$hands;

    @Inject(method = "renderVRHands", at = @At("HEAD"), require = 0, remap = false)
    private static void radiante$beginHands(CallbackInfoReturnable<Integer> cir) {
        radiante$hands = EntityManager.beginVrHands();
    }

    @ModifyArg(method = {"renderVRHand_Main", "renderVRHand_Offhand"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"),
        index = 9, require = 0, remap = false)
    private static SubmitNodeCollector radiante$traceArm(SubmitNodeCollector collector) {
        return radiante$hands != null ? radiante$hands : collector;
    }

    @Inject(method = "renderVRHands", at = @At("RETURN"), require = 0, remap = false)
    private static void radiante$endHands(CallbackInfoReturnable<Integer> cir) {
        if (radiante$hands != null) {
            radiante$hands = null;
            var camera = RadianteRenderer.lastCameraPos();
            EntityManager.endVrHands(camera.x, camera.y, camera.z);
        }
    }
}
