package dev.stabilifps.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.stabilifps.StabiliFPS;
import dev.stabilifps.util.StabiliLog;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted configuration for StabiliFPS.
 *
 * <p>All tunables live here as plain public fields so the config screen and the
 * subsystems can read/write them directly. The file is JSON and lives at
 * {@code config/stabilifps.json}. Unknown keys are ignored on load so the mod
 * stays forward-compatible.</p>
 *
 * <h2>Zero-config safe defaults</h2>
 * <p>Out of the box, on a fresh install, the mod is <b>pure-good</b>: it
 * measures honestly and paces chunk loads, but never changes anything the
 * player can see or feel against their will. Every gameplay-affecting
 * intervention is either off by default or strictly additive (the render
 * distance governor only ever <i>raises</i> RD, never lowers it — see
 * {@code rdGovernor}). A player can install StabiliFPS and immediately get
 * smoother frames with zero config. {@link #resetToRecommended()} restores
 * these exact values.</p>
 */
public final class StabiliConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static StabiliConfig INSTANCE = new StabiliConfig();

    private static Path configDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config");
    }

    private static Path configFile() {
        return configDir().resolve("stabilifps.json");
    }

    // ── General ─────────────────────────────────────────────────────────────
    /** Master kill-switch for every active intervention. */
    public boolean enabled = true;
    /** Show the on-screen stability HUD overlay. ON by default (compact mode). */
    public boolean showHud = true;
    /** Compact HUD (one line) vs full HUD (graph + breakdown). Compact by default. */
    public boolean hudCompact = true;

    // ── Frame-time tracking ─────────────────────────────────────────────────
    /** How many recent frame samples to keep (ring buffer size). */
    public int frameSampleCount = 240;

    // ── Render-distance governor (player floor; ONLY raises) ────────────────
    /**
     * Raise render distance towards {@code maxRenderDistance} when performance
     * is healthy. <b>Never lowers</b> it — the player's value is a hard floor.
     * ON by default because it can only do good.
     */
    public boolean rdGovernor = true;
    /** Ceiling the governor will never exceed. It never goes below the player's value. */
    public int maxRenderDistance = 32;
    /** Frame time (ms) above which the governor freezes growth (does NOT lower RD). */
    public double degradeThresholdMs = 40.0;
    /** Frame time (ms) below which the governor accumulates a healthy streak. */
    public double recoverThresholdMs = 22.0;
    /** Healthy ticks required before raising (long, to avoid needless chunk reloads). */
    public int hysteresisSamples = 240;
    /** Render-distance step per raise (chunks). Small, since each step reloads chunks. */
    public int rdStep = 1;

    // ── Chunk-load pacer (paces chunk-mesh bursts across frames) ────────────
    /**
     * Spread chunk-mesh upload bursts across frames so loading fresh terrain
     * doesn't dominate the frame budget. ON by default; can only smooth, never
     * reduces what the player sees. Pure-good.
     */
    public boolean chunkPacer = true;
    /** Per-frame budget for chunk uploads (microseconds) in NORMAL mode. */
    public long chunkPacerFrameBudgetMicros = 1500;

    // ── Adaptive framerate cap (FpsGovernor) — OFF by default ───────────────
    /**
     * Adjust the framerate cap to maximise 1% low. OFF by default because it
     * changes a setting the player chose; opt in only if desired.
     *
     * WARNING: Can feel "random" as it steps the cap up/down. The governor now
     * tries hard to respect manual changes from video settings, but results vary.
     * Use with caution. Many players prefer to leave this OFF and set a fixed cap.
     */
    public boolean adaptiveCap = false;
    /** Hard floor for the cap (never cap below this). */
    public int minCap = 60;
    /** Hard ceiling for the cap (never cap above this; 0 = unlimited). */
    public int maxCap = 0;
    /** How aggressively to react to variance (0.0..1.0). */
    public double governorAggression = 0.5;
    /** Seconds between cap re-evaluations. Higher = less random switching. */
    public int capReevaluateIntervalSec = 15;

    // ── Entity culling — OFF by default ─────────────────────────────────────
    /**
     * Skip rendering far/tiny entities. OFF by default because it pops visible
     * entities, which can be confusing; opt in only if desired.
     */
    public boolean entityCull = false;
    /** Hard distance beyond which entities are never rendered. */
    public int entityCullDistance = 96;
    /** Cull small entities (item frames, items, xp orbs) earlier than this distance. */
    public int smallEntityCullDistance = 48;

    // ── GC monitoring + allocation ──────────────────────────────────────────
    /** Track GC pauses and surface them on the HUD / log. */
    public boolean gcMonitor = true;
    /** Log a warning when a single GC pause exceeds this many ms. */
    public double gcPauseWarnMs = 40.0;
    /** Log recommended JVM flags to the console on startup. */
    public boolean printGcAdvice = true;
    /**
     * Defer non-critical, allocating work by one frame when the young
     * generation is near a GC boundary. Pure-good; can only reduce pauses.
     */
    public boolean gcAwareDeferral = true;

    private StabiliConfig() {}

    public static StabiliConfig get() { return INSTANCE; }

    public static void load() {
        try {
            Files.createDirectories(configDir());
            if (Files.exists(configFile())) {
                String json = Files.readString(configFile());
                StabiliConfig loaded = GSON.fromJson(json, StabiliConfig.class);
                if (loaded != null) {
                    INSTANCE = loaded;
                    StabiliLog.info("Loaded config from %s", configFile());
                }
            } else {
                StabiliLog.info("No config found, using defaults and writing %s", configFile());
                save();
            }
        } catch (IOException e) {
            StabiliLog.error("Failed to load config: %s", e.getMessage());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(configDir());
            Files.writeString(configFile(), GSON.toJson(INSTANCE));
        } catch (IOException e) {
            StabiliLog.error("Failed to save config: %s", e.getMessage());
        }
    }

    /**
     * Restore the zero-config safe defaults (the values documented above). The
     * config screen exposes this as "Reset to recommended".
     */
    public static void resetToRecommended() {
        INSTANCE = new StabiliConfig();
        save();
    }

    /** Clamp helper used by config screen sliders. */
    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
