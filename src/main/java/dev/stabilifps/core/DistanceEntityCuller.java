package dev.stabilifps.core;

import dev.stabilifps.config.StabiliConfig;
import dev.stabilifps.util.ModCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Distance-based entity culling.
 *
 * <p>Even within the render distance, dense entity scenes (mob farms, item
 * showers, crowded spawn chunks) cost real render time and cause the very
 * spikes StabiliFPS fights. This culler drops:</p>
 * <ul>
 *   <li><b>Small entities</b> (items, XP orbs, paintings, item frames — anything
 *       whose largest bounding-box dimension is under 0.5 blocks) beyond
 *       {@code smallEntityCullDistance};</li>
 *   <li><b>All other entities</b> beyond {@code entityCullDistance}.</li>
 * </ul>
 *
 * <p>It never culls the local player or anything riding/being ridden, and the
 * distances are tunable in the config screen.</p>
 */
public final class DistanceEntityCuller {
    private DistanceEntityCuller() {}

    public static boolean shouldCull(Entity entity, double distanceSq) {
        StabiliConfig c = StabiliConfig.get();
        if (!c.enabled || !c.entityCull) return false;

        // Be a good citizen: if a dedicated Entity Culling mod is present,
        // our simple distance culler can stay out of the way by default.
        // User can still force it on via config if they want both.
        if (ModCompat.shouldPreferExternalEntityCulling()) {
            // Only cull very aggressively far small entities even then,
            // to avoid fighting the other mod.
            if (distanceSq < (c.smallEntityCullDistance * 1.5) * (c.smallEntityCullDistance * 1.5)) return false;
        }

        // Never cull self or vehicles/passengers the player is using.
        if (entity.isVehicle() && entity.hasExactlyOnePlayerPassenger()) return false;

        double maxDim = largestDimension(entity.getBoundingBox());
        boolean small = maxDim < 0.5;
        double limit = small ? c.smallEntityCullDistance : c.entityCullDistance;
        if (limit <= 0) return false;
        return distanceSq > limit * limit;
    }

    private static double largestDimension(AABB bb) {
        double x = bb.getXsize();
        double y = bb.getYsize();
        double z = bb.getZsize();
        return Math.max(x, Math.max(y, z));
    }
}
