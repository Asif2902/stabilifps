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
    /** Show the on-screen stability HUD overlay. Hidden by default; press F7 to show. */
    public boolean showHud = false;
    /** Compact HUD (one line) vs full HUD (graph + breakdown). */
    public boolean hudCompact = false;

    // ── Frame-time tracking ─────────────────────────────────────────────────
    /** How many recent frame samples to keep (ring buffer size). */
    public int frameSampleCount = 240;

    // ── Adaptive render distance ────────────────────────────────────────────
    /** Dynamically lower render distance when frame time degrades, restore when stable. */
    public boolean adaptiveRenderDistance = true;
    /** Floor the adaptive system will never go below. */
    public int minRenderDistance = 4;
    /** Ceiling the adaptive system will never exceed (also respects user's setting). */
    public int maxRenderDistance = 32;
    /** Frame time (ms) above which the system starts shrinking render distance. */
    public double degradeThresholdMs = 40.0;   // ~25 FPS sustained
    /** Frame time (ms) below which the system starts growing render distance back. */
    public double recoverThresholdMs = 22.0;   // ~45 FPS sustained
    /** How many consecutive samples must confirm a transition (hysteresis, prevents flapping). */
    public int hysteresisSamples = 240;
    /** Render-distance step per adjustment (chunks). Keep small: every step reloads chunks. */
    public int rdStep = 1;

    // ── Adaptive framerate cap (FpsGovernor) ────────────────────────────────
    /** Find the framerate cap that maximises the 1% low / minimises variance. */
    public boolean adaptiveCap = true;
    /** Hard floor for the cap (never cap below this). */
    public int minCap = 60;
    /** Hard ceiling for the cap (never cap above this; 0 = unlimited). */
    public int maxCap = 0;
    /** How aggressively to react to variance (0.0..1.0). */
    public double governorAggression = 0.5;
    /** Seconds between cap re-evaluations. */
    public int capReevaluateIntervalSec = 10;

    // ── Entity culling ──────────────────────────────────────────────────────
    /** Skip rendering entities beyond this distance (in blocks), 0 = off. */
    public boolean entityCull = true;
    /** Hard distance beyond which entities are never rendered. */
    public int entityCullDistance = 96;
    /** Cull small entities (item frames, items, xp orbs) earlier than this distance. */
    public int smallEntityCullDistance = 48;

    // ── GC monitoring ───────────────────────────────────────────────────────
    /** Track GC pauses and surface them on the HUD / log. */
    public boolean gcMonitor = true;
    /** Log a warning when a single GC pause exceeds this many ms. */
    public double gcPauseWarnMs = 40.0;
    /** Log recommended JVM flags to the console on startup. */
    public boolean printGcAdvice = true;

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

    /** Clamp helper used by config screen sliders. */
    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
