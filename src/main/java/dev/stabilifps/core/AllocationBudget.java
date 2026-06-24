package dev.stabilifps.core;

import dev.stabilifps.config.StabiliConfig;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * GC-aware frame-work gate.
 *
 * <p>The single biggest hidden cause of "100 -> 8 -> 100" stutter in Minecraft
 * Java is a stop-the-world GC pause. The game allocates an enormous number of
 * short-lived objects every frame, and when the young generation fills, a
 * collection fires and the main thread freezes for tens of milliseconds.</p>
 *
 * <p>This class gives non-critical work (HUD recompute, config autosave,
 * sparkline draws, advice printing) a single cheap question to ask before it
 * allocates: <b>"is now a bad time?"</b>. When the young generation is near a
 * collection boundary, {@link #shouldDeferNonCritical()} returns {@code true}
 * so that work slides by one frame instead of being the straw that triggers a
 * GC pause. Critical work (frame sampling, render decisions) never asks — it
 * always runs.</p>
 *
 * <p>Sampling the JMX {@link MemoryMXBean} is cheap (a few hundred nanoseconds)
 * and only happens when a subsystem consults this gate, so it adds no cost to
 * frames where nobody asks.</p>
 */
public final class AllocationBudget {
    /**
     * If young-gen used space is above this fraction of its committed size, we
     * consider a collection imminent and ask non-critical work to defer. Tuned
     * conservatively: a GC that fires ~one frame earlier than it otherwise
     * would is much cheaper than one that fires mid-burst.
     */
    private static final double DEFER_THRESHOLD = 0.80;

    private static volatile MemoryMXBean memBean;
    private static volatile boolean disabled = false;

    private AllocationBudget() {}

    public static void init() {
        try {
            memBean = ManagementFactory.getMemoryMXBean();
        } catch (Throwable t) {
            disabled = true;
        }
    }

    /**
     * @return {@code true} if non-critical, allocating work should defer to a
     *         later frame because the young generation is near a GC boundary.
     *         Always {@code false} when the feature is off or unavailable, so
     *         callers can gate on this cheaply every frame.
     */
    public static boolean shouldDeferNonCritical() {
        if (disabled) return false;
        if (!StabiliConfig.get().gcAwareDeferral) return false;
        MemoryMXBean bean = memBean;
        if (bean == null) return false;
        try {
            // Heap usages: index 0 = heap, 1 = non-heap. We care about the heap
            // (where allocation pressure lives), specifically pool breakdown —
            // but the aggregate heap used/committed ratio is the cheapest signal.
            MemoryUsage u = bean.getHeapMemoryUsage();
            long used = u.getUsed();
            long committed = u.getCommitted();
            if (committed <= 0) return false;
            return ((double) used / committed) > DEFER_THRESHOLD;
        } catch (Throwable t) {
            disabled = true;
            return false;
        }
    }
}
