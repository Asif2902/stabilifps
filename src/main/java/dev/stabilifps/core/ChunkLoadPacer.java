package dev.stabilifps.core;

import dev.stabilifps.config.StabiliConfig;
import dev.stabilifps.util.StabiliLog;

/**
 * Chunk-mesh burst pacer.
 *
 * <p>The single most common documented cause of Minecraft Java stutter is a
 * <b>chunk-meshing burst</b>: when the player flies fast, teleports, or crosses
 * into fresh terrain, the client queues a flood of chunk geometry rebuilds and
 * uploads them all in a frame or two. That dominates the frame budget and
 * produces the classic edge-of-render-distance hitch (Mojang bug MC-166005).</p>
 *
 * <p>Existing perf mods (Sodium et al.) speed the rebuild up; nobody <i>paces</i>
 * it. StabiliFPS does. The pacer keeps a running estimate of how much chunk
 * upload work it has allowed this frame; once the per-frame budget is spent, it
 * tells the chunk pipeline to defer the rest to subsequent frames. The budget
 * is adaptive:</p>
 * <ul>
 *   <li>Healthy frame times → generous budget (loads chunks promptly);</li>
 *   <li>Degraded frame times → tight budget (favours smoothness over load
 *       speed — the world finishes loading a second later but doesn't hitch);</li>
 *   <li>During an explicit world load (player just joined) → full throttle:
 *       getting the world on screen is worth a few hitches.</li>
 * </ul>
 *
 * <p>The pacer is queried by {@code ChunkUploadMixin} (a Mixin into the client
 * chunk-render upload path) every time a chunk mesh is about to be uploaded.
 * The mixin uses {@code require = 0}, so if a future Minecraft refactor moves
 * the upload call site, the pacer simply stops being consulted — the game
 * behaves as vanilla — rather than crashing.</p>
 *
 * <p>Whenever the pacer actually defers work, it attributes the resulting
 * hitch (if one shows up) to {@link FrameTimeTracker.HitchCause#CHUNK} so the
 * HUD can say <i>why</i>.</p>
 */
public final class ChunkLoadPacer {
    private ChunkLoadPacer() {}

    /** Frame-budget modes driven by the live frame-time signal. */
    private enum Mode { THROTTLE, NORMAL, BOOST }

    private static Mode mode = Mode.NORMAL;
    private static long budgetMicrosThisFrame = 0;
    private static long spentMicrosThisFrame = 0;
    private static long lastFrameNanos = 0L;
    private static long deferredThisFrame = 0;
    private static long totalDeferred = 0;
    private static volatile boolean worldLoadBoostUntil = false;

    /** Reset the per-frame budget at the start of each frame. Called from the render mixin. */
    public static void beginFrame() {
        if (!StabiliConfig.get().enabled || !StabiliConfig.get().chunkPacer) {
            mode = Mode.NORMAL;
            budgetMicrosThisFrame = Long.MAX_VALUE; // unlimited = vanilla behaviour
            spentMicrosThisFrame = 0;
            deferredThisFrame = 0;
            lastFrameNanos = System.nanoTime();
            return;
        }

        double avg = FrameTimeTracker.avgMs();
        double var = FrameTimeTracker.varianceMs();
        long frameBudgetMicros = StabiliConfig.get().chunkPacerFrameBudgetMicros;

        // Pick the mode from the live signal. During an active world load we
        // always BOOST — showing the world is worth a few hitches.
        if (worldLoadBoostUntil) {
            mode = Mode.BOOST;
        } else if (FrameTimeTracker.isDegraded() || avg > 28.0 || var > 6.0) {
            mode = Mode.THROTTLE;
        } else if (avg < 14.0 && var < 2.0) {
            mode = Mode.BOOST;
        } else {
            mode = Mode.NORMAL;
        }

        // Scale the configured budget by the mode.
        double scale = switch (mode) {
            case THROTTLE -> 0.35; // favour smoothness: slow the load down
            case NORMAL   -> 1.0;
            case BOOST    -> 2.5;  // plenty of headroom: load fast
        };
        budgetMicrosThisFrame = (long) (frameBudgetMicros * scale);
        spentMicrosThisFrame = 0;
        deferredThisFrame = 0;
        lastFrameNanos = System.nanoTime();
    }

    /**
     * Called by the chunk-upload mixin immediately before a mesh upload of the
     * given estimated cost. Returns {@code true} if the upload should proceed,
     * {@code false} if it should be deferred to a later frame.
     *
     * @param estimateMicros a cheap estimate of this upload's cost (the mixin
     *                       may pass a constant per-mesh value; precision is not
     *                       critical — the budget is a soft target)
     */
    public static boolean allowUpload(long estimateMicros) {
        StabiliConfig c = StabiliConfig.get();
        if (!c.enabled || !c.chunkPacer) return true; // feature off = vanilla
        if (budgetMicrosThisFrame == Long.MAX_VALUE) return true;
        if (spentMicrosThisFrame + estimateMicros <= budgetMicrosThisFrame) {
            spentMicrosThisFrame += estimateMicros;
            return true;
        }
        // Budget exhausted this frame: defer.
        deferredThisFrame++;
        totalDeferred++;
        FrameTimeTracker.attributeHitch(FrameTimeTracker.HitchCause.CHUNK);
        return false;
    }

    /** Signal that the player just joined a world — load fast for a bit. */
    public static void notifyWorldLoad() {
        worldLoadBoostUntil = true;
        StabiliLog.info("ChunkLoadPacer: world-load boost on (deferred so far: %d)", totalDeferred);
    }

    /** Signal that the initial world load is done — back to adaptive pacing. */
    public static void notifyWorldLoadDone() {
        if (worldLoadBoostUntil) {
            worldLoadBoostUntil = false;
            StabiliLog.info("ChunkLoadPacer: world-load boost off, resuming adaptive pacing");
        }
    }

    public static long totalDeferred() { return totalDeferred; }
    public static long deferredThisFrame() { return deferredThisFrame; }
    public static String modeLabel() {
        return switch (mode) {
            case THROTTLE -> "throttle";
            case NORMAL   -> "normal";
            case BOOST    -> "boost";
        };
    }

    /** Frame budget actually consumed this frame, for the HUD. */
    public static long spentMicrosThisFrame() { return spentMicrosThisFrame; }
    public static long budgetMicrosThisFrame() {
        return budgetMicrosThisFrame == Long.MAX_VALUE ? 0 : budgetMicrosThisFrame;
    }
}
