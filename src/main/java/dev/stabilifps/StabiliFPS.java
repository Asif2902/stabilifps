package dev.stabilifps;

import dev.stabilifps.config.StabiliConfig;
import dev.stabilifps.config.ConfigScreen;
import dev.stabilifps.core.AdaptiveRenderDistance;
import dev.stabilifps.core.FpsGovernor;
import dev.stabilifps.core.FrameTimeTracker;
import dev.stabilifps.core.GcMonitor;
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
 * chases a <i>flat</i> frame-time graph by:</p>
 *
 * <ul>
 *   <li>measuring the real frame-time distribution (avg, 1% low, 0.1% low,
 *       hitch count, variance);</li>
 *   <li>proactively reducing render load the moment frame time degrades
 *       (adaptive render distance + adaptive framerate cap);</li>
 *   <li>culling far / tiny entities so dense scenes do not spike the renderer;</li>
 *   <li>tracking garbage-collection pauses so GC-induced hitches are visible
 *       and can be tuned away with the recommended JVM flags.</li>
 * </ul>
 *
 * <p>All heavy logic lives in the {@code core} package and is driven from here
 * via stable Fabric API events ({@link ClientTickEvents} and the 26.1
 * {@link HudElementRegistry}), which keeps the mod resilient to Minecraft
 * internal refactors across the 26.x line.</p>
 */
public class StabiliFPS implements ClientModInitializer {
    public static final String MOD_ID = "stabilifps";
    public static final Logger LOGGER = LoggerFactory.getLogger("StabiliFPS");

    /** Open the in-game config screen. */
    public static KeyMapping CONFIG_KEY;
    /** Toggle the stability HUD overlay. */
    public static KeyMapping HUD_KEY;
    /** Manually force adaptive render distance on/off. */
    public static KeyMapping TOGGLE_ADAPTIVE_KEY;
    /** Toggle the entire mod on/off (master kill-switch). */
    public static KeyMapping TOGGLE_MOD_KEY;

    @Override
    public void onInitializeClient() {
        LOGGER.info("StabiliFPS: initialising frame-time stabiliser for Minecraft 26.1+");

        // 1. Load config first — every subsystem reads it.
        StabiliConfig.load();

        // 2. Wire up subsystems.
        FrameTimeTracker.init();
        GcMonitor.start();
        AdaptiveRenderDistance.init();
        FpsGovernor.init();

        // 3. Register keybinds. 26.1 KeyMapping takes a KeyMapping.Category
        //    (a record) instead of a plain string category; we use MISC.
        CONFIG_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.stabilifps.config", GLFW.GLFW_KEY_F6, KeyMapping.Category.MISC));
        HUD_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.stabilifps.hud", GLFW.GLFW_KEY_F7, KeyMapping.Category.MISC));
        TOGGLE_ADAPTIVE_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.stabilifps.toggle_adaptive", GLFW.GLFW_KEY_F8, KeyMapping.Category.MISC));
        TOGGLE_MOD_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.stabilifps.toggle_mod", GLFW.GLFW_KEY_F9, KeyMapping.Category.MISC));

        // 4. Register the stability HUD as a 26.1 HudElement (rendered last so
        //    it sits on top of the vanilla HUD).
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "stability_hud"),
                new StabilityHud());

        // 5. Per-tick (20 Hz) logic: adaptive decisions, GC sampling, keybind polling.
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        LOGGER.info("StabiliFPS ready. F6=config  F7=HUD  F8=adaptive RD  F9=toggle mod");
    }

    private void onClientTick(Minecraft mc) {
        // Poll keybinds.
        while (CONFIG_KEY.consumeClick()) {
            mc.setScreen(new ConfigScreen(null));
        }
        while (HUD_KEY.consumeClick()) {
            StabiliConfig c = StabiliConfig.get();
            c.showHud = !c.showHud;
            StabiliConfig.save();
            feedback(mc, c.showHud ? "\u00A7a[StabiliFPS] HUD shown" : "\u00A7e[StabiliFPS] HUD hidden");
        }
        while (TOGGLE_ADAPTIVE_KEY.consumeClick()) {
            StabiliConfig c = StabiliConfig.get();
            c.adaptiveRenderDistance = !c.adaptiveRenderDistance;
            StabiliConfig.save();
            AdaptiveRenderDistance.onToggled();
            feedback(mc, "\u00A7a[StabiliFPS] Adaptive RD: " + (c.adaptiveRenderDistance ? "ON" : "OFF"));
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
        AdaptiveRenderDistance.tick(mc);
        FpsGovernor.tick(mc);
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
