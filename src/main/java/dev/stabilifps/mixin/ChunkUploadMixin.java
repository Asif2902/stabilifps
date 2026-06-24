package dev.stabilifps.mixin;

import dev.stabilifps.core.ChunkLoadPacer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunk-mesh upload hook for the {@link ChunkLoadPacer}.
 *
 * <p>The pacer keeps a per-frame upload budget (microseconds). This mixin
 * charges each chunk section mesh upload against that budget and, when the
 * budget is spent, attributes the resulting hitch to
 * {@link dev.stabilifps.core.FrameTimeTracker.HitchCause#CHUNK} so the HUD can
 * tell the player <i>why</i> the frame dropped.</p>
 *
 * <p><b>Safety over aggression.</b> We do <b>not</b> cancel uploads mid-pipeline:
 * tearing a mesh upload out from under the GL can leak buffer state. Instead
 * the pacer's adaptive budget is enforced at the <i>render rebuild scheduling</i>
 * layer (where work can safely be re-queued), and this mixin's job is
 * measurement + attribution. The {@code require = 0} setting means if a future
 * 26.x drop reshapes the method, this injection no-ops silently and the game
 * behaves as vanilla — no crash path.</p>
 */
@Mixin(SectionRenderDispatcher.class)
public abstract class ChunkUploadMixin {

    private static final long UPLOAD_COST_ESTIMATE_MICROS = 120;

    @Inject(
            method = "uploadMeshBuffer",
            at = @At("HEAD"),
            require = 0
    )
    private void stabilifps$measureUpload(CallbackInfo ci) {
        // Charge the upload against the per-frame budget. When the budget is
        // exhausted, attribute subsequent hitches to chunk upload activity.
        ChunkLoadPacer.allowUpload(UPLOAD_COST_ESTIMATE_MICROS);
    }
}
