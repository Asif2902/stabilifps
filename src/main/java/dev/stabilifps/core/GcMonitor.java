package dev.stabilifps.core;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import dev.stabilifps.config.StabiliConfig;
import dev.stabilifps.util.StabiliLog;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * Garbage-collection pause monitor.
 *
 * <p>The single biggest hidden cause of "100 -> 8 -> 100" stutters in Minecraft
 * Java is a stop-the-world GC pause. The game allocates an enormous number of
 * short-lived objects every frame; when the young generation fills and a
 * collection fires, the main thread freezes for tens of milliseconds.</p>
 *
 * <p>This monitor hooks the platform JMX {@code GarbageCollectorMXBean}
 * notification stream and records the <i>actual pause duration</i> of every
 * collection. The HUD overlays the recent pauses so users can see, in real
 * time, whether a hitch was a GC event or a render spike — and the mod logs a
 * one-time block of recommended JVM flags ({@code -XX:+UseG1GC}, tuned pause
 * goals) so the root cause can be fixed at the launcher level.</p>
 */
public final class GcMonitor {
    private static final int PAUSE_WINDOW = 60;
    private static final long[] recentPausesMs = new long[PAUSE_WINDOW];
    private static int pauseHead = 0;
    private static int pauseSize = 0;

    // Volatile: written from the JMX notification thread, read from the main
    // render/tick thread. Volatile guarantees visibility without full sync.
    private static volatile long lastPauseMs = 0;
    private static volatile long lastPauseTime = 0;
    private static volatile long maxPauseMs = 0;
    private static volatile long totalPauseMs = 0;
    private static volatile long totalPauses = 0;

    private static final NotificationListener LISTENER = (notification, handback) -> {
        if (!"com.sun.management.gc.notification".equals(notification.getType())) return;
        CompositeData cd = (CompositeData) notification.getUserData();
        GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(cd);
        GcInfo gcInfo = info.getGcInfo();
        if (gcInfo == null) return;
        long duration = gcInfo.getDuration(); // ms
        recordPause(duration, info.getGcAction());
    };

    private static volatile boolean started = false;
    private static volatile boolean advicePrinted = false;

    private GcMonitor() {}

    public static void start() {
        if (started) return;
        if (!StabiliConfig.get().gcMonitor) return;
        try {
            List<java.lang.management.GarbageCollectorMXBean> beans =
                    ManagementFactory.getGarbageCollectorMXBeans();
            for (java.lang.management.GarbageCollectorMXBean bean : beans) {
                if (bean instanceof NotificationEmitter emitter) {
                    emitter.addNotificationListener(LISTENER, null, null);
                }
            }
            started = true;
            StabiliLog.info("GcMonitor: attached to %d GC beans", beans.size());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                StabiliLog.info("GcMonitor shutdown: totalPauses=%d totalPauseMs=%d maxPauseMs=%d",
                        totalPauses, totalPauseMs, maxPauseMs);
            }));
        } catch (Throwable t) {
            StabiliLog.warn("GcMonitor: failed to attach: %s", t.getMessage());
        }
    }

    private static void recordPause(long durationMs, String action) {
        lastPauseMs = durationMs;
        lastPauseTime = System.currentTimeMillis();
        totalPauseMs += durationMs;
        totalPauses++;
        if (durationMs > maxPauseMs) maxPauseMs = durationMs;

        recentPausesMs[pauseHead] = durationMs;
        pauseHead = (pauseHead + 1) % PAUSE_WINDOW;
        if (pauseSize < PAUSE_WINDOW) pauseSize++;

        if (durationMs >= StabiliConfig.get().gcPauseWarnMs) {
            // Attribute the hitch to GC so the HUD can say WHY the frame dropped.
            FrameTimeTracker.attributeHitch(FrameTimeTracker.HitchCause.GC);
            StabiliLog.warn("GcMonitor: long GC pause %.0fms (%s) — consider adding Aikar's flags",
                    (double) durationMs, action);
        }
    }

    /** Called 20 Hz from the client tick. */
    public static void tick() {
        if (!started) return;
        StabiliConfig c = StabiliConfig.get();
        if (c.printGcAdvice && !advicePrinted) {
            advicePrinted = true;
            printAdvice();
        }
    }

    private static void printAdvice() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long heapMax = mem.getHeapMemoryUsage().getMax() / (1024L * 1024L);
        StabiliLog.info("=== StabiliFPS GC tuning advice (heap max = %dMB) ===", heapMax);
        StabiliLog.info("Add these JVM args to your launcher profile for stable FPS:");
        StabiliLog.info("  -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200");
        StabiliLog.info("  -XX:InitiatingHeapOccupancyPercent=20 -XX:G1ReservePercent=20");
        StabiliLog.info("  -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC");
        StabiliLog.info("  -Xms%dM -Xmx%dM   (match min/max so the heap never resizes)", heapMax, heapMax);
        StabiliLog.info("=========================================================");
    }

    public static long lastPauseMs() { return lastPauseMs; }
    public static long lastPauseAgeMs() { return lastPauseTime == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastPauseTime; }
    public static long maxPauseMs() { return maxPauseMs; }
    public static long totalPauses() { return totalPauses; }
    public static long totalPauseMs() { return totalPauseMs; }

    /** Pauses in the last window, oldest-first. */
    public static List<Long> recentPauses() {
        List<Long> out = new ArrayList<>(pauseSize);
        int start = pauseSize < PAUSE_WINDOW ? 0 : pauseHead;
        for (int i = 0; i < pauseSize; i++) {
            out.add(recentPausesMs[(start + i) % PAUSE_WINDOW]);
        }
        return out;
    }

    public static int recentPauseCount() { return pauseSize; }

    /** True if a notable GC pause happened within the last second. */
    public static boolean recentHitch() {
        return lastPauseAgeMs() < 1000 && lastPauseMs >= StabiliConfig.get().gcPauseWarnMs;
    }
}
