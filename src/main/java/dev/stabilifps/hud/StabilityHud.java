package dev.stabilifps.hud;

import dev.stabilifps.config.StabiliConfig;
import dev.stabilifps.core.ChunkLoadPacer;
import dev.stabilifps.core.FrameTimeTracker;
import dev.stabilifps.core.GcMonitor;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * On-screen stability overlay, implemented as a 26.x {@link HudElement}.
 *
 * <p>Renders every frame and shows the numbers that actually matter for
 * stutter, plus a live frame-time sparkline so drops are visible as red spikes
 * rather than just felt:</p>
 *
 * <pre>
 *  StabiliFPS  [score 82]
 *  FPS 142  1% 98  0.1% 61
 *  frame 7.0ms  var 2.1ms
 *  hitches 3 (window) / 27 total
 *  GC last 6ms max 41ms x12
 *  RD 12down  cap 140
 *  (frame-time sparkline)
 * </pre>
 *
 * <p>26.x draws immediate-mode graphics via {@link GuiGraphicsExtractor} (the old
 * {@code GuiGraphics.fill/drawString} helpers moved here as {@code fill/text}).
 * This element is registered last so it paints over the vanilla HUD.</p>
 */
public final class StabilityHud implements HudElement {
    private static final int BG = argb(150, 0, 0, 0);
    private static final int WHITE = argb(255, 235, 235, 235);
    private static final int DIM = argb(255, 160, 160, 160);
    private static final int GREEN = argb(255, 80, 220, 120);
    private static final int YELLOW = argb(255, 240, 210, 80);
    private static final int RED = argb(255, 240, 90, 90);
    private static final int BLUE = argb(255, 110, 190, 255);

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker tracker) {
        StabiliConfig c = StabiliConfig.get();
        // Fallback frame-time sampling (covers the case where the GameRenderer
        // mixin did not apply). The tracker dedupes same-frame double calls.
        FrameTimeTracker.onFrame(System.nanoTime());
        if (!c.enabled || !c.showHud) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;

        int x = 4;
        int y = 4;
        if (c.hudCompact) drawCompact(g, mc, c, x, y);
        else drawFull(g, mc, c, x, y);
    }

    private void drawCompact(GuiGraphicsExtractor g, Minecraft mc, StabiliConfig c, int x, int y) {
        Font f = mc.font;
        String line = String.format("FPS %d  1%% %d  score %d  GC %dms",
                (int) FrameTimeTracker.avgFps(),
                (int) FrameTimeTracker.lowFpsOnePercent(),
                (int) FrameTimeTracker.stabilityScore(),
                GcMonitor.lastPauseMs());
        // Append the hitch-cause tag when we know why the last stutter happened
        // — the headline "it actually tells you" feature.
        FrameTimeTracker.HitchCause cause = FrameTimeTracker.recentHitchCause();
        if (cause != null) {
            line += "  " + causeLabel(cause);
        }
        int w = f.width(line) + 8;
        g.fill(x, y, x + w, y + f.lineHeight + 6, BG);
        g.text(f, line, x + 4, y + 3, colorForScore(FrameTimeTracker.stabilityScore()));
    }

    private void drawFull(GuiGraphicsExtractor g, Minecraft mc, StabiliConfig c, int x, int y) {
        Font f = mc.font;
        int lh = f.lineHeight + 2;
        int boxW = 168;

        int score = (int) FrameTimeTracker.stabilityScore();
        double avgFps = FrameTimeTracker.avgFps();
        double low1 = FrameTimeTracker.lowFpsOnePercent();
        double low01 = FrameTimeTracker.lowFpsPointOnePercent();
        double avgMs = FrameTimeTracker.avgMs();
        double varMs = FrameTimeTracker.varianceMs();
        int hitches = FrameTimeTracker.hitchCount();
        int totalH = FrameTimeTracker.totalHitches();

        int rows = 7;
        int boxH = rows * lh + 6;
        g.fill(x, y, x + boxW, y + boxH, BG);
        int tx = x + 5;
        int ty = y + 3;

        g.text(f, "StabiliFPS", tx, ty, BLUE); ty += lh;
        g.text(f, String.format("score  %d", score), tx, ty, colorForScore(score)); ty += lh;
        g.text(f, String.format("FPS %d   1%% %d   0.1%% %d",
                (int) avgFps, (int) low1, (int) low01), tx, ty, WHITE); ty += lh;
        g.text(f, String.format("frame %.1fms   var %.1fms", avgMs, varMs), tx, ty,
                varMs > 6 ? RED : varMs > 3 ? YELLOW : GREEN); ty += lh;
        g.text(f, String.format("hitches %d (win) / %d total", hitches, totalH), tx, ty,
                hitches > 3 ? YELLOW : DIM); ty += lh;
        String gcLine = GcMonitor.recentHitch()
                ? String.format("GC last %dms  MAX %dms  x%d  !!", GcMonitor.lastPauseMs(), GcMonitor.maxPauseMs(), GcMonitor.totalPauses())
                : String.format("GC last %dms  max %dms  x%d", GcMonitor.lastPauseMs(), GcMonitor.maxPauseMs(), GcMonitor.totalPauses());
        g.text(f, gcLine, tx, ty, GcMonitor.recentHitch() ? RED : DIM); ty += lh;

        // Active interventions line: governor (raise-only RD) + pacer + cap.
        int rd = safeRd(mc);
        int cap = safeCap(mc);
        String gov = c.rdGovernor ? "gov RD" + rd + "+" : "RD " + rd;
        String pace = c.chunkPacer ? "pacer " + ChunkLoadPacer.modeLabel() : "pacer off";
        String capState = c.adaptiveCap ? "cap " + (cap >= 260 ? "inf" : cap) : "cap " + (cap >= 260 ? "inf" : cap) + "(off)";
        g.text(f, gov + "  " + pace + "  " + capState, tx, ty, DIM); ty += lh;

        // Frame-time sparkline below the text box.
        int gy = y + boxH + 2;
        int gw = boxW;
        int gh = 28;
        g.fill(x, gy, x + gw, gy + gh, BG);
        drawSparkline(g, tx, gy + 3, gw - 10, gh - 6);
    }

    private void drawSparkline(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        float[] frames = FrameTimeTracker.recentFrameTimesMs(w);
        if (frames.length == 0) return;
        // Map 0..40ms to h..0 (taller bar = slower frame).
        float maxMs = 40f;
        int baseline = y + h;
        int cellW = Math.max(1, w / frames.length);
        for (int i = 0; i < frames.length; i++) {
            float ms = Math.min(maxMs, frames[i]);
            int barH = (int) ((ms / maxMs) * h);
            int col = ms < 16.7f ? GREEN : ms < 33.3f ? YELLOW : RED;
            int bx = x + (i * (w / frames.length));
            g.fill(bx, baseline - barH, bx + Math.max(1, cellW - 1), baseline, col);
        }
        // 16.7ms (60fps) reference line.
        int ref60 = baseline - (int) ((16.7f / maxMs) * h);
        g.horizontalLine(x, x + w, ref60, argb(120, 255, 255, 255));
    }

    private static int colorForScore(double score) {
        if (score >= 80) return GREEN;
        if (score >= 50) return YELLOW;
        return RED;
    }

    /** Short tag for a hitch cause, shown in red so the player notices it. */
    private static String causeLabel(FrameTimeTracker.HitchCause cause) {
        return switch (cause) {
            case CHUNK   -> "\u00A7c[chunk]";
            case GC      -> "\u00A7c[gc]";
            case ENTITY  -> "\u00A7c[entity]";
            case ADAPTIVE-> "\u00A7c[rd]";
            case UNKNOWN -> "\u00A7c[hitch]";
        };
    }

    private static int safeRd(Minecraft mc) {
        try { return mc.options.renderDistance().get(); } catch (Throwable t) { return -1; }
    }
    private static int safeCap(Minecraft mc) {
        try { return mc.options.framerateLimit().get(); } catch (Throwable t) { return -1; }
    }

    private static int argb(int a, int r, int gg, int b) {
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }
}
