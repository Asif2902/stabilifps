package dev.stabilifps.core;

import dev.stabilifps.config.StabiliConfig;
import dev.stabilifps.util.StabiliLog;

/**
 * Ring-buffer frame-time measurement.
 *
 * <p>This is the single source of truth for "how is the game actually running
 * right now". Every other subsystem reads from here. It keeps the last N frame
 * intervals (in nanoseconds) and exposes derived statistics:</p>
 *
 * <ul>
 *   <li>{@link #avgFps()} — plain average FPS over the window;</li>
 *   <li>{@link #lowFpsOnePercent()} — the <b>1% low</b> (worst 1% of frames,
 *       averaged) — the number that actually reflects stutter;</li>
 *   <li>{@link #lowFpsPointOnePercent()} — 0.1% low for the worst-case view;</li>
 *   <li>{@link #varianceMs()} — frame-time variance, the core stability signal;</li>
 *   <li>{@link #hitchCount()} — frames whose time exceeded 2&times; the recent
 *       average (a concrete "drop" counter);</li>
 *   <li>{@link #stabilityScore()} — 0..100 composite for the HUD.</li>
 * </ul>
 *
 * <p>Sampling is driven per-frame from the HUD render callback (which fires
 * every frame), and additionally from the {@code GameRenderer.render} mixin
 * when present, so the numbers are accurate even when the HUD is hidden.</p>
 */
public final class FrameTimeTracker {
    private static final long MS = 1_000_000L;

    private static long[] frames;
    // Reusable scratch buffer for the per-frame sort, so ensureComputed() never
    // allocates in the hot path. Only touched under ensureComputed().
    private static long[] scratch;
    private static int head = 0;
    private static int size = 0;
    private static long lastSampleNanos = 0L;

    // Cached derived values, recomputed at most once per frame (when the ring
    // head advances). Getters call ensureComputed(); if the head hasn't moved
    // since the last compute, the cache is returned as-is — so calling avgMs(),
    // varianceMs() and lowFpsOnePercent() in the same frame is a single
    // recompute, not three.
    private static double cachedAvgMs = 0;
    private static double cachedVarMs = 0;
    private static double cachedLow1Ms = 0;
    private static double cachedLow01Ms = 0;
    private static int cachedHitch = 0;
    private static long cacheFrameStamp = -1;

    // Hitch attribution: when a single frame is markedly worse than the recent
    // average, the subsystem that caused it (chunk upload, GC pause, entity
    // pass, …) can call attributeHitch() so the HUD can show "why it stuttered"
    // instead of a bare number. The most recent non-null cause wins for a short
    // window so the user actually has time to read it.
    public enum HitchCause { CHUNK, GC, ENTITY, ADAPTIVE, UNKNOWN }
    private static volatile HitchCause lastHitchCause = null;
    private static volatile long lastHitchCauseNanos = 0L;
    private static final long HITCH_CAUSE_TTL_NS = 2_000_000_000L; // show for ~2s

    // Rolling hitch detector state (independent of full recompute).
    private static final int HITCH_WINDOW = 30;
    private static final long[] recentForHitch = new long[HITCH_WINDOW];
    private static int hitchHead = 0;
    private static int hitchSize = 0;
    private static int totalHitches = 0;

    // Measurement-freeze window. When an active intervention (render-distance
    // change, framerate-cap change, chunk reload) is happening, its own spike
    // would otherwise be measured as "degradation" and trigger a self-amplifying
    // feedback loop. ignoreUntilNanos lets a subsystem tell us "don't trust the
    // next N ms of samples" so the loop cannot feed on itself.
    private static long ignoreUntilNanos = 0L;

    private FrameTimeTracker() {}

    /**
     * Freeze measurement for the next {@code millis} milliseconds. Use this
     * immediately after any action that itself causes a frame-time spike
     * (render-distance change, chunk reload, cap change) so the spike is not
     * counted as real workload and fed back into the adaptive logic.
     */
    public static void ignoreFor(long millis) {
        long until = System.nanoTime() + millis * 1_000_000L;
        if (until > ignoreUntilNanos) ignoreUntilNanos = until;
    }

    /**
     * Attribute the most recent / current hitch to a cause so the HUD can show
     * <i>why</i> a frame dropped (chunk, gc, entity, …) instead of just that it
     * did. Subsystems call this when they know they just did heavy work.
     */
    public static void attributeHitch(HitchCause cause) {
        lastHitchCause = cause;
        lastHitchCauseNanos = System.nanoTime();
    }

    /** True if a hitch cause was attributed recently and is still worth showing. */
    public static boolean hasRecentHitchCause() {
        return lastHitchCause != null
                && (System.nanoTime() - lastHitchCauseNanos) < HITCH_CAUSE_TTL_NS;
    }

    /** The most recent hitch cause, or null if none is fresh enough to display. */
    public static HitchCause recentHitchCause() {
        if (!hasRecentHitchCause()) return null;
        return lastHitchCause;
    }

    public static void init() {
        int n = Math.max(60, StabiliConfig.get().frameSampleCount);
        frames = new long[n];
        scratch = new long[n];
        head = 0;
        size = 0;
        lastSampleNanos = 0L;
        cacheFrameStamp = -1; // force a recompute after reset
        lastHitchCause = null;
        lastHitchCauseNanos = 0L;
        StabiliLog.info("FrameTimeTracker: window=%d frames", n);
    }

    /** Called once per rendered frame. {@code nowNanos} = System.nanoTime(). */
    public static void onFrame(long nowNanos) {
        if (frames == null) init();
        if (lastSampleNanos == 0L) {
            lastSampleNanos = nowNanos;
            return;
        }
        long delta = nowNanos - lastSampleNanos;
        lastSampleNanos = nowNanos;
        // Reject (a) duplicate same-frame samples from the HUD callback + mixin
        // firing within one frame (<1ms apart), and (b) absurd values (pause,
        // focus loss, first frame) so they don't poison the statistics.
        if (delta < 1_000_000L || delta > 1_000_000_000L) return;

        // During an active intervention (see ignoreFor), drop samples entirely
        // so the intervention's own spike cannot be read back as degradation.
        if (nowNanos < ignoreUntilNanos) {
            // Keep the hitch window seeded but do not pollute the main window.
            recentForHitch[hitchHead] = delta;
            hitchHead = (hitchHead + 1) % HITCH_WINDOW;
            if (hitchSize < HITCH_WINDOW) hitchSize++;
            return;
        }

        frames[head] = delta;
        head = (head + 1) % frames.length;
        if (size < frames.length) size++;

        // Hitch bookkeeping (rolling).
        recentForHitch[hitchHead] = delta;
        hitchHead = (hitchHead + 1) % HITCH_WINDOW;
        if (hitchSize < HITCH_WINDOW) hitchSize++;
        double recentAvg = rollingAvgMs();
        if (hitchSize >= 10 && delta / (double) MS > recentAvg * 2.0 && recentAvg > 0) {
            totalHitches++;
        }
    }

    private static double rollingAvgMs() {
        long sum = 0;
        for (int i = 0; i < hitchSize; i++) sum += recentForHitch[i];
        return hitchSize == 0 ? 0 : (sum / (double) hitchSize) / (double) MS;
    }

    /** Force a recompute on next read; call when the window slides significantly. */
    private static void ensureComputed() {
        if (size == 0) {
            cachedAvgMs = cachedVarMs = cachedLow1Ms = cachedLow01Ms = 0;
            cachedHitch = 0;
            return;
        }
        if (cacheFrameStamp == head) return; // already current
        cacheFrameStamp = head;

        // Copy + sort the live window into the reusable scratch buffer
        // (small N, cheap, and zero-allocation). We sort the copy not the ring
        // so the time-ordered window (used by the sparkline) stays intact.
        if (scratch == null || scratch.length < size) scratch = new long[size];
        for (int i = 0; i < size; i++) scratch[i] = frames[i];
        java.util.Arrays.sort(scratch, 0, size);
        long[] copy = scratch;

        long sum = 0;
        for (long l : copy) sum += l;
        double avgNs = sum / (double) size;
        cachedAvgMs = avgNs / (double) MS;

        double var = 0;
        for (long l : copy) {
            double d = l - avgNs;
            var += d * d;
        }
        cachedVarMs = Math.sqrt(var / size) / (double) MS;

        // 1% low = average of the slowest 1% of frames (worst frame times).
        int onePct = Math.max(1, size / 100);
        int oneStart = 0; // slowest frames are at the front after sort ascending? No — ascending = fastest first, slowest at end.
        long lowSum1 = 0;
        for (int i = size - onePct; i < size; i++) lowSum1 += copy[i];
        cachedLow1Ms = (lowSum1 / (double) onePct) / (double) MS;

        int oneTenthPct = Math.max(1, size / 1000);
        long lowSum01 = 0;
        for (int i = size - oneTenthPct; i < size; i++) lowSum01 += copy[i];
        cachedLow01Ms = (lowSum01 / (double) oneTenthPct) / (double) MS;

        // Hitch count over the full window: frames slower than 2x average.
        cachedHitch = 0;
        double thresh = avgNs * 2.0;
        for (long l : copy) if (l > thresh) cachedHitch++;
    }

    public static double avgMs() { ensureComputed(); return cachedAvgMs; }
    public static double avgFps() { double a = avgMs(); return a <= 0 ? 0 : 1000.0 / a; }
    public static double varianceMs() { ensureComputed(); return cachedVarMs; }
    public static double lowFpsOnePercent() { ensureComputed(); double a = cachedLow1Ms; return a <= 0 ? 0 : 1000.0 / a; }
    public static double lowFpsPointOnePercent() { ensureComputed(); double a = cachedLow01Ms; return a <= 0 ? 0 : 1000.0 / a; }
    public static int hitchCount() { ensureComputed(); return cachedHitch; }
    public static int totalHitches() { return totalHitches; }

    /**
     * Composite 0..100 stability score — the headline "how smooth is it" number.
     *
     * <p>Weighted from the four signals that actually map to perceived
     * smoothness, in priority order:</p>
     * <ul>
     *   <li><b>1% low vs average ratio</b> (40%) — the core stutter signal.
     *       Perfectly flat = 1.0; severe stutter &lt; 0.3.</li>
     *   <li><b>Absolute variance</b> (25%) — penalises jitter even when the
     *       1% low is okay. &gt;6ms variance is harshly penalised.</li>
     *   <li><b>0.1% low ratio</b> (20%) — worst-case frames; catches the rare
     *       but jarring spike that the 1% low averages away.</li>
     *   <li><b>Frame-time headroom</b> (15%) — rewards having average frame
     *       time well under a 60fps budget, so high-FPS smooth play scores
     *       higher than barely-60fps play.</li>
     * </ul>
     * <p>GC pause frequency is surfaced separately on the HUD rather than
     * folded in, because a clean frame-time graph with one GC blip is still
     * "smooth" in a way the score should not fully punish.</p>
     */
    public static double stabilityScore() {
        double avg = avgMs();
        double low1 = cachedLow1Ms;
        double low01 = cachedLow01Ms;
        if (avg <= 0) return 100;

        // 1% low ratio (0..1). low1 >= avg means flat; low1 >> avg means bad.
        double ratio1 = low1 > 0 ? Math.min(1.0, avg / low1) : 0;
        double from1Low = ratio1 * 100.0;

        // 0.1% low ratio — worst-case frames.
        double ratio01 = low01 > 0 ? Math.min(1.0, avg / low01) : 0;
        double from01Low = ratio01 * 100.0;

        // Variance: 0ms = 100, 6ms+ = 0.
        double var = cachedVarMs;
        double fromVar = Math.max(0, 100.0 - var * 16.0);

        // Headroom: avg at 16.7ms (60fps) = ~50; at 8ms = 100; at 33ms = 0.
        double fromHeadroom = Math.max(0, Math.min(100, (33.0 - avg) / (33.0 - 8.0) * 100.0));

        return Math.max(0, Math.min(100,
                0.40 * from1Low + 0.25 * fromVar + 0.20 * from01Low + 0.15 * fromHeadroom));
    }

    /** True when the recent window is in a degraded (stuttering) state. */
    public static boolean isDegraded() {
        double avg = avgMs();
        double low1 = cachedLow1Ms;
        if (avg <= 0 || low1 <= 0) return false;
        // Degraded when the 1% low is worse than ~half the average rate,
        // i.e. low1 frame time > ~2x average frame time.
        return low1 > avg * 1.8;
    }

    public static int windowSize() { return size; }

    /** Returns up to {@code max} recent frame times in ms, oldest-first (for the HUD sparkline). */
    public static float[] recentFrameTimesMs(int max) {
        if (frames == null || size == 0) return new float[0];
        int count = Math.min(Math.max(1, max), size);
        float[] out = new float[count];
        int start = (head - count + frames.length) % frames.length;
        for (int i = 0; i < count; i++) {
            out[i] = frames[(start + i) % frames.length] / 1_000_000f;
        }
        return out;
    }
}
