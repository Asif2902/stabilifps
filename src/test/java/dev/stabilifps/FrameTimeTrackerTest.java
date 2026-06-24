package dev.stabilifps;

import dev.stabilifps.core.FrameTimeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FrameTimeTracker}'s statistics engine.
 *
 * <p>These exercise the pure-maths core (ring buffer → avg / 1% low / variance /
 * stability score / hitch attribution) without a Minecraft client, by driving
 * {@link FrameTimeTracker#onFrame(long)} with synthetic nanosecond timestamps.
 * This is the regression coverage that would have caught the original
 * chunk-flash feedback loop: a synthetic "spike" sequence should be detected as
 * degraded and attributed, while a flat sequence should score near 100.</p>
 *
 * <p>{@code FrameTimeTracker.init()} reads {@code StabiliConfig.get()} for the
 * window size, but {@code new StabiliConfig()} is safe to construct in a test
 * (it does not touch {@code Minecraft} until load/save), so the tracker
 * initialises cleanly here.</p>
 */
class FrameTimeTrackerTest {

    private static final long NS_PER_MS = 1_000_000L;

    @BeforeEach
    void resetTracker() {
        // init() rebuilds the ring buffer and clears all cached/hitch state,
        // giving each test a clean slate.
        FrameTimeTracker.init();
    }

    @Test
    @DisplayName("a perfectly flat 16.6ms sequence scores high and is not degraded")
    void flatSequenceIsStable() {
        // 16.6ms ≈ 60fps, every frame identical.
        feed(240, 16_600_000L);
        double score = FrameTimeTracker.stabilityScore();
        assertTrue(score > 85, "flat 60fps should score high, got " + score);
        assertFalse(FrameTimeTracker.isDegraded());
    }

    @Test
    @DisplayName("a sequence with occasional huge spikes is flagged degraded")
    void spikySequenceIsDegraded() {
        // Mostly 16.6ms, but every 20th frame is a 150ms hitch (the classic
        // 100 -> 8 -> 100 stutter signature).
        for (int i = 0; i < 240; i++) {
            FrameTimeTracker.onFrame(i * 16_600_000L);
            if (i % 20 == 19) {
                // Inject a spike: jump the clock forward 150ms for one frame.
                FrameTimeTracker.onFrame(i * 16_600_000L + 150_000_000L);
            }
        }
        assertTrue(FrameTimeTracker.isDegraded(), "spiky sequence should be degraded");
        assertTrue(FrameTimeTracker.hitchCount() > 0, "spikes should register as hitches");
        double score = FrameTimeTracker.stabilityScore();
        assertTrue(score < 70, "spiky sequence should score low, got " + score);
    }

    @Test
    @DisplayName("the 1% low is worse (lower FPS) than the average FPS")
    void onePercentLowIsWorseThanAverage() {
        // Mix of fast (8ms) and occasional slow (40ms) frames.
        for (int i = 0; i < 240; i++) {
            long delta = (i % 15 == 0) ? 40_000_000L : 8_000_000L;
            FrameTimeTracker.onFrame(i * 8_000_000L + delta);
        }
        double avgFps = FrameTimeTracker.avgFps();
        double low1 = FrameTimeTracker.lowFpsOnePercent();
        assertTrue(low1 < avgFps, "1% low (" + low1 + ") should be below avg (" + avgFps + ")");
    }

    @Test
    @DisplayName("hitch attribution is recorded and visible within its TTL")
    void hitchAttributionIsVisible() {
        FrameTimeTracker.attributeHitch(FrameTimeTracker.HitchCause.GC);
        assertTrue(FrameTimeTracker.hasRecentHitchCause());
        assertEquals(FrameTimeTracker.HitchCause.GC, FrameTimeTracker.recentHitchCause());
    }

    @Test
    @DisplayName("hitch attribution is null when nothing was attributed")
    void noAttributionReturnsNull() {
        assertFalse(FrameTimeTracker.hasRecentHitchCause());
        assertEquals(null, FrameTimeTracker.recentHitchCause());
    }

    @Test
    @DisplayName("ignoreFor window drops samples so a spike is not counted")
    void ignoreWindowSuppressesSpike() {
        // Establish a flat baseline.
        feed(120, 16_600_000L);
        double scoreBefore = FrameTimeTracker.stabilityScore();

        // Freeze measurement, then inject a spike — it must be ignored.
        FrameTimeTracker.ignoreFor(500);
        FrameTimeTracker.onFrame(System.nanoTime() + 200_000_000L); // 200ms spike
        // Feed a few more "normal" frames (still inside the freeze window).
        feed(5, 16_600_000L);

        // The spike was suppressed, so the score should not have cratered.
        double scoreAfter = FrameTimeTracker.stabilityScore();
        assertTrue(scoreAfter > scoreBefore - 10,
                "ignored spike should not crater the score: before=" + scoreBefore + " after=" + scoreAfter);
    }

    /** Feed {@code count} identical {@code deltaNs} frames into the tracker. */
    private static void feed(int count, long deltaNs) {
        long t = 0;
        for (int i = 0; i < count; i++) {
            t += deltaNs;
            FrameTimeTracker.onFrame(t);
        }
    }
}
