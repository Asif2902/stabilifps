package dev.stabilifps.core;

import dev.stabilifps.StabiliFPS;
import dev.stabilifps.config.StabiliConfig;
import dev.stabilifps.util.ModCompat;
import dev.stabilifps.util.StabiliLog;
import net.minecraft.client.Minecraft;

/**
 * Player-floor render-distance governor.
 *
 * <p><b>Hard guarantee:</b> the player's render distance is a floor. This
 * system may only <i>raise</i> render distance (when performance is healthy and
 * the player has set a higher ceiling via {@code maxRenderDistance}), and only
 * ever by one chunk at a time with a long cooldown. It will <b>never</b> call
 * {@code renderDistance().set()} with a value below what the player chose.</p>
 *
 * <p>This is the direct replacement for the old {@code AdaptiveRenderDistance},
 * which could yank the player's render distance downward — and because changing
 * render distance reloads chunks, that yank caused the very "chunk flash"
 * stutter the mod existed to prevent (a self-amplifying feedback loop). The
 * governor makes that loop structurally impossible: it has no code path that
 * lowers render distance.</p>
 *
 * <p>On degradation it does the honest thing instead:</p>
 * <ul>
 *   <li>freezes its own growth (won't push RD up while the GPU is struggling);</li>
 *   <li>surfaces a HUD hint that the current RD may be too high, so the player
 *       can choose to lower it themselves — the decision stays theirs.</li>
 * </ul>
 *
 * <p>If the player manually lowers render distance, that lower value becomes
 * the new floor.</p>
 */
public final class RenderDistanceGovernor {
    /**
     * Cooldown between upward RD steps. Every step reloads chunks, so we keep
     * this long enough that the reload's own spike (frozen out of measurement
     * via {@link FrameTimeTracker#ignoreFor}) cannot pile into the next step.
     */
    private static final long RAISE_COOLDOWN_MS = 6000;

    private static int floor = -1;        // the player's chosen RD (never go below)
    private static int lastApplied = -1;  // last value we set (to detect external changes)
    private static int stableStreak = 0;
    private static long lastRaiseNanos = 0L;
    private static boolean seeded = false;
    private static boolean hintShownForCurrentFloor = false;

    private RenderDistanceGovernor() {}

    public static void init() {
        stableStreak = 0;
        lastRaiseNanos = 0L;
        seeded = false;
        hintShownForCurrentFloor = false;
        try {
            int cur = currentRd();
            floor = cur;
            lastApplied = cur;
            seeded = true;
            StabiliLog.info("RenderDistanceGovernor: floor (player RD)=%d, ceiling=%d",
                    cur, StabiliConfig.get().maxRenderDistance);
        } catch (Throwable t) {
            StabiliLog.warn("RenderDistanceGovernor: could not read render distance: %s", t.getMessage());
        }
    }

    /** Called when the feature is toggled or the mod is re-enabled. */
    public static void onToggled() {
        try {
            int cur = currentRd();
            floor = cur;       // re-seed floor from whatever the player has now
            lastApplied = cur;
        } catch (Throwable ignored) {}
        stableStreak = 0;
        lastRaiseNanos = 0L;
        hintShownForCurrentFloor = false;
        StabiliLog.info("RenderDistanceGovernor toggled -> %b (floor=%d)",
                StabiliConfig.get().rdGovernor, floor);
    }

    public static void tick(Minecraft mc) {
        StabiliConfig c = cfg();
        if (!c.enabled || !c.rdGovernor || mc.level == null || mc.player == null) return;
        if (!seeded) return;

        int current;
        try {
            current = currentRd();
        } catch (Throwable t) {
            return;
        }

        // The player may have changed RD manually. Whatever they set is the new
        // floor — even if they lowered it. We never override a manual change
        // except possibly to raise it (and only if they haven't touched it for
        // a stable streak).
        if (current != lastApplied) {
            floor = current;
            lastApplied = current;
            hintShownForCurrentFloor = false; // fresh floor → allow hint again
            stableStreak = 0;
        }

        double avg = FrameTimeTracker.avgMs();

        if (FrameTimeTracker.isDegraded() || avg > c.degradeThresholdMs) {
            // Struggling: freeze growth and (once per floor) hint to the player.
            stableStreak = 0;
            maybeShowTooHighHint(c, current, avg);
            return;
        }

        if (avg < c.recoverThresholdMs) {
            stableStreak++;
        } else {
            stableStreak = Math.max(0, stableStreak - 1);
        }

        // Only consider raising after a long, healthy streak, past cooldown,
        // and only up to the configured ceiling. We never lower.
        // In a Sodium-boosted environment we can be a touch more willing to
        // enjoy the extra headroom Sodium gave us (still raise-only).
        long now = System.nanoTime();
        boolean cooldownClear = lastRaiseNanos == 0L
                || (now - lastRaiseNanos) / 1_000_000L >= RAISE_COOLDOWN_MS;
        int effectiveHysteresis = c.hysteresisSamples;
        if (ModCompat.isBoostedEnvironment()) {
            effectiveHysteresis = Math.max(60, c.hysteresisSamples / 2);
        }
        if (stableStreak >= effectiveHysteresis
                && cooldownClear
                && current < c.maxRenderDistance) {
            int desired = Math.min(c.maxRenderDistance, current + c.rdStep);
            if (desired > current) { // strictly raising — the only branch that writes
                try {
                    mc.options.renderDistance().set(desired);
                    lastApplied = desired;
                    // The raise reloads chunks: freeze measurement so the reload
                    // spike is not read back as degradation (which would
                    // otherwise freeze us permanently).
                    FrameTimeTracker.ignoreFor(RAISE_COOLDOWN_MS);
                    lastRaiseNanos = now;
                    StabiliLog.info("RenderDistanceGovernor: healthy (avg=%.1fms streak=%d) -> raised RD %d -> %d",
                            avg, stableStreak, current, desired);
                } catch (Throwable t) {
                    StabiliFPS.LOGGER.warn("RenderDistanceGovernor: failed to raise RD: {}", t.getMessage());
                }
            }
        }
    }

    /** Surface a one-time hint that the current RD may be too high. */
    private static void maybeShowTooHighHint(StabiliConfig c, int current, double avg) {
        if (hintShownForCurrentFloor) return;
        // Only hint if the player set a high RD and we're consistently over budget.
        if (current <= 8) { hintShownForCurrentFloor = true; return; }
        hintShownForCurrentFloor = true;
        FrameTimeTracker.attributeHitch(FrameTimeTracker.HitchCause.ADAPTIVE);
        StabiliLog.info("RenderDistanceGovernor: frame time degraded (avg=%.1fms at RD=%d). "
                + "Not lowering automatically — consider lowering RD in Options if this persists.", avg, current);
    }

    /** The player's render distance, which this governor treats as the floor. */
    public static int floor() { return floor; }

    private static int currentRd() {
        return mc().options.renderDistance().get();
    }
    private static Minecraft mc() { return Minecraft.getInstance(); }
    private static StabiliConfig cfg() { return StabiliConfig.get(); }
}
