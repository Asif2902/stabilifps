package dev.stabilifps.mixin;

import dev.stabilifps.core.FrameTimeTracker;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-frame sampling hook.
 *
 * <p>26.1 reshaped {@code GameRenderer.render} to {@code render(DeltaTracker, boolean)}.
 * It is still called exactly once per rendered frame, so it remains the most
 * reliable place to sample frame boundaries — including when the HUD is hidden
 * (F1), where the HUD {@code HudElement} fallback does not fire.</p>
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
        FrameTimeTracker.onFrame(System.nanoTime());
    }
}
