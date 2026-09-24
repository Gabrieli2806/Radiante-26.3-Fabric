package com.g2806.radiante.mixin.debug;

import com.g2806.radiante.client.gui.RadianteDebugEntries;
import java.util.Map;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's built-in profiles only list its own entries, so Radiante's would start hidden. They start in the
 * overlay instead; a player who hides them from F3 + F6 gets a custom profile, which keeps that choice.
 */
@Mixin(DebugScreenEntryList.class)
public class DebugScreenEntryListMixin {

    @Shadow
    @Final
    private Map<Identifier, DebugScreenEntryStatus> allStatuses;

    @Inject(method = "resetToProfile", at = @At("TAIL"))
    private void radiante$showRadianteEntries(DebugScreenProfile profile, CallbackInfo ci) {
        for (Identifier id : RadianteDebugEntries.ALL) {
            this.allStatuses.putIfAbsent(id, DebugScreenEntryStatus.IN_OVERLAY);
        }
    }
}
