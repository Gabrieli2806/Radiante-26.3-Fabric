package com.g2806.radiante.mixin.compat;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Leaving a world with Distant Horizons generating (as it does while ray tracing asks it for far terrain) froze
 * the game for about five seconds, long enough for Windows to mark the window "Not responding": Distant Horizons
 * shuts its worker pools down on the render thread and waits up to five seconds for each to finish its running
 * tasks before interrupting them. Measured, they never finish in that time, so the wait only delayed the
 * interrupt; it is cut to a quarter of a second when leaving a world. Skipped when Distant Horizons is not installed.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker", remap = false)
public class DistantHorizonsShutdownMixin {

    private static final long WAIT_MILLIS = 250L;

    @Redirect(method = "shutdownNow", require = 0, at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/core/util/threading/PriorityTaskPicker$Executor;awaitTermination(JLjava/util/concurrent/TimeUnit;)Z"))
    private boolean radiante$shortShutdownWait(@Coerce AbstractExecutorService executor, long timeout, TimeUnit unit)
        throws InterruptedException {
        if (!net.minecraft.client.Minecraft.getInstance().isRunning()) {
            // Closing the game: the process ends right after, and tasks still running into Distant Horizons'
            // closed databases crashed it natively (seen in testing). The full wait stays for that.
            return executor.awaitTermination(timeout, unit);
        }
        return executor.awaitTermination(Math.min(unit.toMillis(timeout), WAIT_MILLIS), TimeUnit.MILLISECONDS);
    }
}
