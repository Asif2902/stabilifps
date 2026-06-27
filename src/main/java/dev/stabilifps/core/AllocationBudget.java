package dev.stabilifps.core;

import dev.stabilifps.config.StabiliConfig;
import dev.stabilifps.util.ModCompat;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;

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
    private static volatile MemoryPoolMXBean youngGenPool;
    private static volatile boolean disabled = false;

    private AllocationBudget() {}

    public static void init() {
        try {
            memBean = ManagementFactory.getMemoryMXBean();
            // Try to find the young generation / Eden pool for more accurate signal.
            // This works better with G1, Parallel, etc. and is what actually triggers most stutters.
            List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
            for (MemoryPoolMXBean pool : pools) {
                if (pool.getType() == MemoryType.HEAP) {
                    String name = pool.getName().toLowerCase();
                    if (name.contains("eden") || name.contains("young") || name.contains("ps eden") || name.contains("par eden")) {
                        youngGenPool = pool;
                        break;
                    }
                }
            }
            if (youngGenPool == null) {
                // Fallback to first survivor or any heap pool if no eden found.
                for (MemoryPoolMXBean pool : pools) {
                    if (pool.getType() == MemoryType.HEAP && pool.getName().toLowerCase().contains("survivor")) {
                        youngGenPool = pool;
                        break;
                    }
                }
            }
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

        try {
            // Prefer the actual young gen pool when available — much more precise
            // than whole-heap. This is especially important in "boosted" Sodium
            // environments that already allocate a lot but keep frames fast.
            MemoryPoolMXBean young = youngGenPool;
            if (young != null) {
                MemoryUsage u = young.getUsage();
                long used = u.getUsed();
                long committed = u.getCommitted() > 0 ? u.getCommitted() : u.getMax();
                if (committed > 0) {
                    double ratio = (double) used / committed;
                    // Slightly more aggressive threshold for young gen because
                    // it fills and empties very fast.
                    return ratio > (DEFER_THRESHOLD - 0.05);
                }
            }

            // Fallback to overall heap.
            MemoryMXBean bean = memBean;
            if (bean == null) return false;
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
