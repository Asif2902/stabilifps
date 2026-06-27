package dev.stabilifps.util;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Compatibility helper for running as a "sub-pack" / companion to Sodium and other perf mods.
 *
 * <p>Goal: never fight other mods. If Sodium (or similar) has boosted performance
 * (higher effective render distance, faster chunking, better FPS), we keep and
 * build on top of that boosted state. We only add stability on top.
 * All our active interventions are either pure-good (pacing, deferral, measurement)
 * or strictly additive/raise-only/opt-in.</p>
 *
 * <p>This class detects popular companions at runtime and adjusts behavior/logs
 * accordingly so the mod stays "comfortable" with any combination.</p>
 */
public final class ModCompat {

    private static final String SODIUM = "sodium";
    private static final String LITHIUM = "lithium";
    private static final String FERRITECORE = "ferritecore";
    private static final String ENTITY_CULLING = "entityculling";
    private static final String MODERN_FIX = "modernfix";
    private static final String IMMEDIATELY_FAST = "immediatelyfast";
    private static final String IRIS = "iris";

    private static boolean sodium;
    private static boolean lithium;
    private static boolean ferrite;
    private static boolean entityCullingMod;
    private static boolean modernfix;
    private static boolean immediatelyfast;
    private static boolean iris;
    private static boolean detected = false;

    private ModCompat() {}

    public static void detect() {
        if (detected) return;
        FabricLoader loader = FabricLoader.getInstance();
        sodium = loader.isModLoaded(SODIUM);
        lithium = loader.isModLoaded(LITHIUM);
        ferrite = loader.isModLoaded(FERRITECORE);
        entityCullingMod = loader.isModLoaded(ENTITY_CULLING);
        modernfix = loader.isModLoaded(MODERN_FIX);
        immediatelyfast = loader.isModLoaded(IMMEDIATELY_FAST);
        iris = loader.isModLoaded(IRIS);
        detected = true;

        StringBuilder sb = new StringBuilder("StabiliFPS companions: ");
        if (sodium) sb.append("Sodium ");
        if (lithium) sb.append("Lithium ");
        if (ferrite) sb.append("FerriteCore ");
        if (entityCullingMod) sb.append("EntityCulling ");
        if (modernfix) sb.append("ModernFix ");
        if (immediatelyfast) sb.append("ImmediatelyFast ");
        if (iris) sb.append("Iris ");
        if (sb.length() > "StabiliFPS companions: ".length()) {
            StabiliLog.info(sb.toString().trim());
        } else {
            StabiliLog.info("StabiliFPS running standalone (no major perf companions detected)");
        }

        if (sodium) {
            StabiliLog.info("Sodium detected — StabiliFPS will keep Sodium's performance gains and focus purely on frame-time stability.");
        }
        if (entityCullingMod) {
            StabiliLog.info("EntityCulling mod detected — our entity culler is disabled by default for maximum compatibility. You can enable it if desired.");
        }
    }

    public static boolean isSodiumLoaded() { detect(); return sodium; }
    public static boolean isLithiumLoaded() { detect(); return lithium; }
    public static boolean isFerriteLoaded() { detect(); return ferrite; }
    public static boolean isEntityCullingModLoaded() { detect(); return entityCullingMod; }
    public static boolean isModernFixLoaded() { detect(); return modernfix; }
    public static boolean isImmediatelyFastLoaded() { detect(); return immediatelyfast; }
    public static boolean isIrisLoaded() { detect(); return iris; }

    /** Returns a short string for HUD or logs, e.g. "+Sodium" */
    public static String companionSummary() {
        detect();
        StringBuilder s = new StringBuilder();
        if (sodium) s.append("+Sodium");
        if (lithium) s.append(s.length() > 0 ? " " : "").append("+Lithium");
        if (ferrite) s.append(s.length() > 0 ? " " : "").append("+Ferrite");
        return s.length() > 0 ? s.toString() : "";
    }

    /**
     * Suggests a more conservative chunk budget when we are running with heavy
     * chunk optimizers (Sodium already made meshing faster, so we need less
     * aggressive pacing to avoid over-throttling the gains).
     */
    public static double getChunkBudgetScaleForCompanions() {
        detect();
        if (sodium) return 1.35; // Sodium is fast, give a bit more budget headroom
        if (immediatelyfast || modernfix) return 1.15;
        return 1.0;
    }

    /**
     * Whether we should be extra careful with entity culling.
     * If a dedicated entity culling mod is present, we prefer to stay out of its way.
     */
    public static boolean shouldPreferExternalEntityCulling() {
        return isEntityCullingModLoaded();
    }

    /**
     * True if we are running in a "boosted" environment (Sodium + friends).
     * In this case we can be slightly more willing to let work through
     * because the base performance is already higher.
     */
    public static boolean isBoostedEnvironment() {
        detect();
        return sodium || lithium || ferrite;
    }
}
