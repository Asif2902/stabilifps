package dev.stabilifps.core;

import dev.stabilifps.config.StabiliConfig;
import dev.stabilifps.util.StabiliLog;
import net.minecraft.client.Minecraft;

/**
 * Adaptive framerate-cap governor.
 *
 * <p>Naively uncapping the framerate feels fast on paper but causes the very
 * stutter we are fighting: the GPU renders a burst of frames, fills the driver
 * command queue, then stalls on a sync — producing the 100 -> 8 -> 100
 * pattern. A well-chosen cap keeps the queue shallow and frame times flat.</p>
 *
 * <p>The governor does a simple hill-climb on the <b>1% low</b> every
 * {@code capReevaluateIntervalSec} seconds:</p>
 * <ol>
 *   <li>If frame-time variance is high (stuttery), step the cap <i>down</i>
 *       — shallower queue, more headroom, fewer stalls.</li>
 *   <li>If variance is low <i>and</i> the 1% low is bumping against the cap,
 *       step the cap <i>up</i> — we have spare performance to spend.</li>
 * </ol>
 *
 * <p>This converges on the cap with the best perceived smoothness for the
 * current scene + hardware, and re-converges when the load changes.</p>
 */
public final class FpsGovernor {
    private static final int UNLIMITED = 260; // Minecraft encodes "Unlimited" as 260 on the slider
    private static final int STEP = 10;

    private static long lastEvalNanos = 0L;
    private static int lastApplied = -1;
    private static int lastEvaluatedLow1 = -1;

    private FpsGovernor() {}

    public static void init() {
        lastEvalNanos = System.nanoTime();
        try {
            lastApplied = currentCap();
            StabiliLog.info("FpsGovernor: starting cap=%d, range=[%d..%s]",
                    lastApplied, cfg().minCap, cfg().maxCap == 0 ? "∞" : cfg().maxCap);
        } catch (Throwable t) {
            StabiliLog.warn("FpsGovernor: could not read current cap: %s", t.getMessage());
        }
    }

    public static void tick(Minecraft mc) {
        StabiliConfig c = cfg();
        if (!c.enabled || !c.adaptiveCap) return;
        if (mc.level == null) return;

        long now = System.nanoTime();
        long elapsedSec = (now - lastEvalNanos) / 1_000_000_000L;
        if (elapsedSec < c.capReevaluateIntervalSec) return;
        lastEvalNanos = now;

        int current;
        try {
            current = currentCap();
        } catch (Throwable t) {
            return;
        }

        // Detect the user changing the cap manually and adopt it as a baseline.
        if (lastApplied >= 0 && current != lastApplied) {
            lastApplied = current;
        }

        double avg = FrameTimeTracker.avgMs();
        double low1Ms = avgMsToLow1();
        double variance = FrameTimeTracker.varianceMs();
        if (avg <= 0) return;

        int currentFps = current >= UNLIMITED ? Integer.MAX_VALUE : current;
        int low1Fps = low1Ms > 0 ? (int) Math.round(1000.0 / low1Ms) : 0;

        // Aggression scales how much variance we tolerate before stepping down.
        double varTolerance = 6.0 * (1.0 - c.governorAggression); // ms; lower aggression => tolerate more variance
        boolean stuttery = variance > varTolerance && low1Fps > 0 && low1Fps < (int)(1000.0 / avg * 0.6);

        int desired = current;
        int ceiling = c.maxCap == 0 ? UNLIMITED : Math.min(UNLIMITED, c.maxCap);
        int floor = Math.max(c.minCap, 10);

        if (stuttery && current > floor) {
            desired = Math.max(floor, current - STEP);
            StabiliLog.info("Governor: stuttery (var=%.1fms low1=%dfps) -> cap %d -> %d",
                    variance, low1Fps, current, desired);
        } else if (!stuttery && low1Fps > 0 && low1Fps >= currentFps * 0.92 && current < ceiling) {
            // Smooth and the 1% low is essentially at the cap => we have headroom.
            desired = Math.min(ceiling, current + STEP);
            StabiliLog.info("Governor: smooth (var=%.1fms low1=%dfps) -> cap %d -> %d",
                    variance, low1Fps, current, desired);
        }

        lastEvaluatedLow1 = low1Fps;

        if (desired != current) {
            try {
                mc.options.framerateLimit().set(desired);
                lastApplied = desired;
            } catch (Throwable t) {
                StabiliLog.warn("Governor: failed to set cap: %s", t.getMessage());
            }
        }
    }

    private static double avgMsToLow1() {
        double low1Ms = FrameTimeTracker.lowFpsOnePercent() > 0
                ? 1000.0 / FrameTimeTracker.lowFpsOnePercent()
                : 0;
        return low1Ms;
    }

    private static int currentCap() {
        return mc().options.framerateLimit().get();
    }

    private static Minecraft mc() { return Minecraft.getInstance(); }
    private static StabiliConfig cfg() { return StabiliConfig.get(); }
}
