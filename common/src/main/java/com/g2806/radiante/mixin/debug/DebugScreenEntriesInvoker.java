package com.g2806.radiante.mixin.debug;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Registering a debug entry is private outside Fabric (which widens it), so every loader goes through this. */
@Mixin(DebugScreenEntries.class)
public interface DebugScreenEntriesInvoker {

    @Invoker("register")
    static Identifier radiante$register(Identifier identifier, DebugScreenEntry entry) {
        throw new AssertionError();
    }
}
