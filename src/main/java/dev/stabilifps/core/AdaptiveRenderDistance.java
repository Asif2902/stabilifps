package dev.stabilifps.core;

import dev.stabilifps.StabiliFPS;
import dev.stabilifps.config.StabiliConfig;
import dev.stabilifps.util.StabiliLog;
import net.minecraft.client.Minecraft;

/**
 * Adaptive render-distance controller.
 *
 * <p>This is the single most effective drop-preventer in the mod. When chunk
 * geometry stops fitting the frame budget — the classic cause of the
 * 100 -> 8 -> 100 stutter — this system <b>proactively</b> shrinks the render
 * distance before the drop deepens, then grows it back once the renderer is
 * breathing again.</p>
 *
 * <p>Key properties that make it safe rather than annoying:</p>
 * <ul>
 *   <li><b>Hysteresis</b> — a transition needs {@code hysteresisSamples}
 *       consecutive confirming ticks, so a single hiccup never flaps the
 *       render distance.</li>
 *   <li><b>Floor</b> — never goes below {@code minRenderDistance}.</li>
 *   <li><b>User respect</b> — if the player manually changes render distance,
 *       that becomes the new ceiling we grow back toward.</li>
 *   <li><b>Slow ramp</b> — adjusts one {@code rdStep} at a time, so the change
 *       is imperceptible instead of jarring.</li>
 * </ul>
 */
public final class AdaptiveRenderDistance {
    /**
     * How long (ms) to freeze frame-time measurement AND skip re-evaluation
     * after a render-distance change. Changing RD triggers a Minecraft chunk
     * reload, which itself causes a frame-time spike; without a freeze that
     * spike would be read as "degradation" and force another change — the
     * self-amplifying "chunk flash" loop.
     */
    private static final long CHANGE_COOLDOWN_MS = 4000;

    private static int degradeStreak = 0;
    private static int stableStreak = 0;
    private static int lastApplied = -1;
    private static int maxTarget = -1;
    private static long lastChangeNanos = 0L;
    private static boolean firstSeedDone = false;

    private AdaptiveRenderDistance() {}

    public static void init() {
        degradeStreak = 0;
        stableStreak = 0;
        lastChangeNanos = 0L;
        firstSeedDone = false;
        try {
            int cur = currentRd();
            maxTarget = StabiliConfig.clamp(cur, cfg().minRenderDistance, cfg().maxRenderDistance);
            lastApplied = cur;
            firstSeedDone = true;
            StabiliLog.info("AdaptiveRenderDistance: starting RD=%d, ceiling=%d", cur, maxTarget);
        } catch (Throwable t) {
            StabiliLog.warn("AdaptiveRenderDistance: could not read current render distance: %s", t.getMessage());
            maxTarget = 12;
        }
    }

    public static void onToggled() {
        // When re-enabled, re-seed the ceiling from the live value so we don't
        // immediately yank the user's setting back down.
        try {
            int cur = currentRd();
            maxTarget = StabiliConfig.clamp(cur, cfg().minRenderDistance, cfg().maxRenderDistance);
            lastApplied = cur;
        } catch (Throwable ignored) {}
        degradeStreak = 0;
        stableStreak = 0;
        lastChangeNanos = 0L;
        StabiliLog.info("AdaptiveRenderDistance toggled -> %b", cfg().adaptiveRenderDistance);
    }

    public static void tick(Minecraft mc) {
        StabiliConfig c = cfg();
        if (!c.enabled || !c.adaptiveRenderDistance || mc.level == null || mc.player == null) return;
        if (!firstSeedDone) return;

        int current;
        try {
            current = currentRd();
        } catch (Throwable t) {
            return;
        }

        // Detect external (user) changes and adopt them as the new ceiling.
        // After our own change we also set lastApplied, so we won't mistake our
        // own write for a user change here.
        if (lastApplied >= 0 && current != lastApplied) {
            int adopted = StabiliConfig.clamp(current, c.minRenderDistance, c.maxRenderDistance);
            if (adopted > maxTarget) maxTarget = adopted;
            lastApplied = current;
            // The user just changed RD — that reloads chunks too. Freeze.
            FrameTimeTracker.ignoreFor(CHANGE_COOLDOWN_MS);
            lastChangeNanos = System.nanoTime();
            degradeStreak = 0;
            stableStreak = 0;
        }

        // Cooldown: never re-evaluate while a previous change's chunk reload is
        // still settling, and never change RD more often than the cooldown.
        long now = System.nanoTime();
        if (lastChangeNanos > 0 && (now - lastChangeNanos) / 1_000_000L < CHANGE_COOLDOWN_MS) return;

        boolean degraded = FrameTimeTracker.isDegraded();
        double avg = FrameTimeTracker.avgMs();

        if (degraded || avg > c.degradeThresholdMs) {
            degradeStreak++;
            stableStreak = 0;
        } else if (avg < c.recoverThresholdMs) {
            stableStreak++;
            degradeStreak = 0;
        } else {
            // Neutral band: decay both slowly so we don't flap on the edge.
            if (degradeStreak > 0) degradeStreak = Math.max(0, degradeStreak - 1);
            if (stableStreak > 0) stableStreak = Math.max(0, stableStreak - 1);
        }

        int desired = current;
        if (degradeStreak >= c.hysteresisSamples && current > c.minRenderDistance) {
            desired = Math.max(c.minRenderDistance, current - c.rdStep);
            StabiliLog.info("AdaptiveRD: degrade streak=%d avg=%.1fms -> RD %d -> %d",
                    degradeStreak, avg, current, desired);
            degradeStreak = 0; // apply, then re-accumulate before stepping again
        } else if (stableStreak >= c.hysteresisSamples && current < maxTarget) {
            desired = Math.min(maxTarget, current + c.rdStep);
            StabiliLog.info("AdaptiveRD: stable streak=%d avg=%.1fms -> RD %d -> %d",
                    stableStreak, avg, current, desired);
            stableStreak = 0;
        }

        if (desired != current) {
            try {
                mc.options.renderDistance().set(desired);
                lastApplied = desired;
                // Changing RD reloads chunks → freeze measurement for the reload
                // spike and start the cooldown so we don't re-trigger.
                FrameTimeTracker.ignoreFor(CHANGE_COOLDOWN_MS);
                lastChangeNanos = System.nanoTime();
            } catch (Throwable t) {
                StabiliFPS.LOGGER.warn("AdaptiveRD: failed to set render distance: {}", t.getMessage());
            }
        }
    }

    private static int currentRd() {
        return mc().options.renderDistance().get();
    }

    private static Minecraft mc() { return Minecraft.getInstance(); }
    private static StabiliConfig cfg() { return StabiliConfig.get(); }
}
