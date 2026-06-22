package dev.stabilifps.mixin;

import dev.stabilifps.StabiliFPS;
import dev.stabilifps.core.DistanceEntityCuller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters the per-frame entity render list through {@link DistanceEntityCuller}.
 *
 * <p>This is the only mixin that touches vanilla rendering state directly, so
 * it is kept in its own mixin config ({@code stabilifps.cull.mixins.json}) and
 * can be disabled independently if a future Minecraft refactor changes the
 * shape of {@code entitiesForRendering()}.</p>
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "entitiesForRendering", at = @At("RETURN"), cancellable = true)
    private void stabilifps$cullFarEntities(CallbackInfoReturnable<Iterable<Entity>> cir) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return;
            Iterable<Entity> original = cir.getReturnValue();
            if (original == null) return;

            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            List<Entity> kept = null;
            int removed = 0;
            for (Entity e : original) {
                double dsq = e.distanceToSqr(px, py, pz);
                if (DistanceEntityCuller.shouldCull(e, dsq)) {
                    if (kept == null) kept = new ArrayList<>();
                    removed++;
                } else {
                    if (kept != null) kept.add(e);
                }
            }
            if (kept != null) {
                cir.setReturnValue(kept);
            }
        } catch (Throwable t) {
            // Never let culling break the render loop.
            StabiliFPS.LOGGER.debug("StabiliFPS cull skipped: {}", t.toString());
        }
    }
}
