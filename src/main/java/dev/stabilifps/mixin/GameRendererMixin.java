package dev.stabilifps.mixin;

import dev.stabilifps.core.ChunkLoadPacer;
import dev.stabilifps.core.FrameTimeTracker;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-frame sampling + chunk-pacer begin hook.
 *
 * <p>26.x reshaped {@code GameRenderer.render} to {@code render(DeltaTracker, boolean)}.
 * It is still called exactly once per rendered frame, so it remains the most
 * reliable place to (a) sample frame boundaries — including when the HUD is
 * hidden (F1), where the HUD {@code HudElement} fallback does not fire — and
 * (b) start the {@link ChunkLoadPacer}'s per-frame budget.</p>
 *
 * <p>The descriptor pins the exact overload. If a future 26.x drop reshapes the
 * signature, {@code require = 0} makes the injection skip silently instead of
 * crashing, and frame timing falls back to the HUD element callback.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At("HEAD"),
            require = 0
    )
    private void stabilifps$onFrameStart(DeltaTracker tracker, boolean renderLevel, CallbackInfo ci) {
        // Reset the chunk-pacer's per-frame budget first so this frame's uploads
        // are paced against the budget, then record the frame boundary.
        ChunkLoadPacer.beginFrame();
        FrameTimeTracker.onFrame(System.nanoTime());
    }
}

