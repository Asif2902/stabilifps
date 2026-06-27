package dev.stabilifps;

import dev.stabilifps.config.StabiliConfig;
import dev.stabilifps.config.ConfigScreen;
import dev.stabilifps.core.AllocationBudget;
import dev.stabilifps.core.ChunkLoadPacer;
import dev.stabilifps.core.FpsGovernor;
import dev.stabilifps.core.FrameTimeTracker;
import dev.stabilifps.core.GcMonitor;
import dev.stabilifps.core.RenderDistanceGovernor;
import dev.stabilifps.hud.StabilityHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * StabiliFPS client entrypoint.
 *
 * <p>StabiliFPS is a client-side Fabric mod whose single goal is <b>frame-time
 * stability</b>: preventing the sudden 100 -> 8 -> 100 FPS stutter that
 * plagues Minecraft Java on a wide range of hardware. Rather than chasing a
 * higher average FPS (which Sodium and friends already do well), StabiliFPS
 * chases a <i>flat</i> frame-time graph.</p>
 *
 * <h2>Design philosophy: measure honestly, intervene surgically</h2>
 * <p>Every feature is either <b>pure-good</b> (measures/paces and cannot
 * degrade gameplay) or <b>opt-in</b> (changes something the player can see).
 * The one gameplay-affecting system that ships ON by default — the
 * {@link RenderDistanceGovernor} — is strictly additive: it only ever
 * <i>raises</i> render distance when the GPU is comfortable, and never lowers
 * it below what the player chose. The player's render distance is a hard
 * floor. This makes the old "chunk flash" feedback loop structurally
 * impossible.</p>
 *
 * <ul>
 *   <li>{@link FrameTimeTracker} — the honest core: 1% low, 0.1% low,
 *       variance, hitch count, a documented stability score, and hitch
 *       attribution (chunk / gc / entity / unknown).</li>
 *   <li>{@link RenderDistanceGovernor} — raises RD toward max when healthy;
 *       never lowers it.</li>
 *   <li>{@link ChunkLoadPacer} — spreads chunk-mesh bursts across frames so
 *       loading fresh terrain doesn't dominate the frame budget (the #1 cause
 *       of Minecraft stutter).</li>
 *   <li>{@link AllocationBudget} — defers non-critical allocating work when a
 *       GC pause is imminent.</li>
 *   <li>{@link GcMonitor} — tracks real GC pauses and surfaces them.</li>
 *   <li>{@link FpsGovernor} — optional adaptive cap; off by default.</li>
 * </ul>
 *
 * <p>All heavy logic lives in the {@code core} package and is driven from here
 * via stable Fabric API events ({@link ClientTickEvents} and the 26.x
 * {@link HudElementRegistry}).</p>
 */
public class StabiliFPS implements ClientModInitializer {
    public static final String MOD_ID = "stabilifps";
    public static final Logger LOGGER = LoggerFactory.getLogger("StabiliFPS");

    /** Open the in-game config screen. */
    public static KeyMapping CONFIG_KEY;
    /** Toggle the stability HUD overlay. */
    public static KeyMapping HUD_KEY;
    /** Toggle the render-distance governor (raise-only; never lowers RD). */
    public static KeyMapping TOGGLE_GOVERNOR_KEY;
    /** Toggle the entire mod on/off (master kill-switch). */
    public static KeyMapping TOGGLE_MOD_KEY;

    /** Tracks world transitions so the chunk pacer can boost during loads. */
    private static boolean wasInWorld = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("StabiliFPS v1.2: surgical frame-time stabiliser for Minecraft 26.2+");

        // 1. Load config first — every subsystem reads it.
        StabiliConfig.load();

        // 2. Wire up subsystems. Order matters only in that FrameTimeTracker
        //    must be ready before anything that consults it.
        FrameTimeTracker.init();
        AllocationBudget.init();
        GcMonitor.start();
        RenderDistanceGovernor.init();
        FpsGovernor.init();

        // 3. Register keybinds. 26.x KeyMapping takes a KeyMapping.Category
        //    (a record) instead of a plain string category; we use MISC.
        CONFIG_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.stabilifps.config", GLFW.GLFW_KEY_F6, KeyMapping.Category.MISC));
        HUD_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.stabilifps.hud", GLFW.GLFW_KEY_F7, KeyMapping.Category.MISC));
        TOGGLE_GOVERNOR_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.stabilifps.toggle_governor", GLFW.GLFW_KEY_F8, KeyMapping.Category.MISC));
        TOGGLE_MOD_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.stabilifps.toggle_mod", GLFW.GLFW_KEY_F9, KeyMapping.Category.MISC));

        // 4. Register the stability HUD as a 26.x HudElement (rendered last so
        //    it sits on top of the vanilla HUD).
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "stability_hud"),
                new StabilityHud());

        // 5. Per-tick (20 Hz) logic: subsystem decisions, GC sampling, keybind polling.
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        LOGGER.info("StabiliFPS ready. F6=config  F7=HUD  F8=RD governor  F9=toggle mod");
    }

    private void onClientTick(Minecraft mc) {
        // Poll keybinds.
        while (CONFIG_KEY.consumeClick()) {
            // 26.2 moved screen setters off Minecraft into the Gui component.
            mc.gui.setScreen(new ConfigScreen(null));
        }
        while (HUD_KEY.consumeClick()) {
            StabiliConfig c = StabiliConfig.get();
            c.showHud = !c.showHud;
            StabiliConfig.save();
            feedback(mc, c.showHud ? "\u00A7a[StabiliFPS] HUD shown" : "\u00A7e[StabiliFPS] HUD hidden");
        }
        while (TOGGLE_GOVERNOR_KEY.consumeClick()) {
            StabiliConfig c = StabiliConfig.get();
            c.rdGovernor = !c.rdGovernor;
            StabiliConfig.save();
            RenderDistanceGovernor.onToggled();
            feedback(mc, "\u00A7a[StabiliFPS] RD governor: " + (c.rdGovernor ? "ON (raise-only)" : "OFF"));
        }
        while (TOGGLE_MOD_KEY.consumeClick()) {
            StabiliConfig c = StabiliConfig.get();
            c.enabled = !c.enabled;
            StabiliConfig.save();
            LOGGER.info("StabiliFPS {}", c.enabled ? "ENABLED" : "DISABLED");
            feedback(mc, c.enabled
                    ? "\u00A7a[StabiliFPS] ENABLED"
                    : "\u00A7c[StabiliFPS] DISABLED (all features off, F9 to re-enable)");
        }

        // Drive subsystems.
        GcMonitor.tick();
        RenderDistanceGovernor.tick(mc);
        FpsGovernor.tick(mc);
        trackWorldLoad(mc);
    }

    /**
     * Tell the chunk pacer when the player joins/leaves a world so it can
     * full-throttle the initial load (a few hitches while the world appears is
     * worth it) and then settle back to adaptive pacing.
     */
    private static void trackWorldLoad(Minecraft mc) {
        boolean inWorld = mc.level != null && mc.player != null;
        if (inWorld && !wasInWorld) {
            ChunkLoadPacer.notifyWorldLoad();
        } else if (!inWorld && wasInWorld) {
            ChunkLoadPacer.notifyWorldLoadDone();
        }
        wasInWorld = inWorld;
    }

    /** Send a local-only chat message so the player gets feedback for keybinds. */
    private static void feedback(Minecraft mc, String text) {
        try {
            if (mc.gui != null && mc.gui.getChat() != null) {
                mc.gui.getChat().addClientSystemMessage(
                        net.minecraft.network.chat.Component.literal(text));
            }
        } catch (Throwable ignored) {}
    }
}
