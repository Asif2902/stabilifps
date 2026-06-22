package dev.stabilifps.config;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * In-game config screen (opened with the F6 keybind).
 *
 * <p>Implemented with plain {@link Button} widgets rather than the slider
 * widgets, so it stays robust across Minecraft 26.x refactors. Toggles flip on
 * click; numeric settings use a [-] value [+] triplet. Every change is saved
 * immediately to {@code config/stabilifps.json}.</p>
 *
 * <p>26.1 renders screens via {@code extractRenderState(GuiGraphicsExtractor, ...)}
 * rather than the old {@code render(GuiGraphics, ...)}; drawing goes through
 * the extractor's {@code fill/centeredText} helpers.</p>
 */
public class ConfigScreen extends Screen {
    private static final int ROW_H = 22;
    private static final int PAD = 8;

    private int cursorY;
    private int centerX;
    private int contentW;

    public ConfigScreen(Screen parent) {
        super(Component.literal("StabiliFPS Configuration"));
    }

    @Override
    protected void init() {
        centerX = this.width / 2;
        contentW = Math.min(360, this.width - 2 * PAD);
        cursorY = 28;

        addToggle("Mod enabled", () -> cfg().enabled, v -> cfg().enabled = v);
        addToggle("Show HUD (F7)", () -> cfg().showHud, v -> cfg().showHud = v);
        addToggle("Compact HUD", () -> cfg().hudCompact, v -> cfg().hudCompact = v);

        section("Adaptive render distance");
        addToggle("Adaptive RD (F8)", () -> cfg().adaptiveRenderDistance, v -> cfg().adaptiveRenderDistance = v);
        addStepper("Min RD", () -> cfg().minRenderDistance,
                v -> cfg().minRenderDistance = StabiliConfig.clamp(v, 2, 24), 2, 2, 24);
        addStepper("Max RD", () -> cfg().maxRenderDistance,
                v -> cfg().maxRenderDistance = StabiliConfig.clamp(v, 4, 64), 2, 4, 64);
        addStepper("Degrade threshold (ms)", () -> (int) cfg().degradeThresholdMs,
                v -> cfg().degradeThresholdMs = StabiliConfig.clamp((double) v, 16, 100), 1, 16, 100);

        section("Adaptive framerate cap");
        addToggle("Adaptive cap", () -> cfg().adaptiveCap, v -> cfg().adaptiveCap = v);
        addStepper("Min cap (fps)", () -> cfg().minCap,
                v -> cfg().minCap = StabiliConfig.clamp(v, 30, 240), 5, 30, 240);
        addStepper("Max cap (fps, 0=inf)", () -> cfg().maxCap,
                v -> cfg().maxCap = StabiliConfig.clamp(v, 0, 260), 5, 0, 260);

        section("Entity culling");
        addToggle("Entity cull", () -> cfg().entityCull, v -> cfg().entityCull = v);
        addStepper("Entity cull distance", () -> cfg().entityCullDistance,
                v -> cfg().entityCullDistance = StabiliConfig.clamp(v, 16, 256), 8, 16, 256);
        addStepper("Small entity cull distance", () -> cfg().smallEntityCullDistance,
                v -> cfg().smallEntityCullDistance = StabiliConfig.clamp(v, 8, 128), 4, 8, 128);

        section("GC monitoring");
        addToggle("GC monitor", () -> cfg().gcMonitor, v -> cfg().gcMonitor = v);
        addToggle("Print GC advice", () -> cfg().printGcAdvice, v -> cfg().printGcAdvice = v);

        // Done button at the bottom.
        int doneY = Math.min(this.height - 26, cursorY + PAD);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(centerX - 60, doneY, 120, 20).build());
    }

    private void section(String name) {
        cursorY += 6;
        addRenderableWidget(Button.builder(Component.literal("\u00A77" + name), b -> {})
                .bounds(centerX - contentW / 2, cursorY, contentW, 16).build());
        cursorY += ROW_H - 4;
    }

    private void addToggle(String label, java.util.function.Supplier<Boolean> get,
                           java.util.function.Consumer<Boolean> set) {
        int x = centerX - contentW / 2;
        Button btn = Button.builder(toggleLabel(label, get.get()), b -> {
            set.accept(!get.get());
            b.setMessage(toggleLabel(label, get.get()));
            StabiliConfig.save();
        }).bounds(x, cursorY, contentW, 20).build();
        addRenderableWidget(btn);
        cursorY += ROW_H;
    }

    private void addStepper(String label, java.util.function.Supplier<Integer> get,
                            java.util.function.Consumer<Integer> set, int step, int lo, int hi) {
        int x = centerX - contentW / 2;
        int minusW = 20;
        int plusW = 20;
        int labelW = contentW - minusW - plusW - 4;
        int lx = x;
        int mx = x + labelW + 4;
        int px = mx + minusW + 2;

        Button labelBtn = Button.builder(stepperLabel(label, get.get()), b -> {})
                .bounds(lx, cursorY, labelW, 20).build();
        Button minus = Button.builder(Component.literal("-"), b -> {
            int nv = StabiliConfig.clamp(get.get() - step, lo, hi);
            set.accept(nv);
            labelBtn.setMessage(stepperLabel(label, get.get()));
            StabiliConfig.save();
        }).bounds(mx, cursorY, minusW, 20).build();
        Button plus = Button.builder(Component.literal("+"), b -> {
            int nv = StabiliConfig.clamp(get.get() + step, lo, hi);
            set.accept(nv);
            labelBtn.setMessage(stepperLabel(label, get.get()));
            StabiliConfig.save();
        }).bounds(px, cursorY, plusW, 20).build();

        addRenderableWidget(labelBtn);
        addRenderableWidget(minus);
        addRenderableWidget(plus);
        cursorY += ROW_H;
    }

    private static Component toggleLabel(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "\u00A7aON" : "\u00A7cOFF"));
    }
    private static Component stepperLabel(String label, int value) {
        return Component.literal(label + ": \u00A7e" + value);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        // Dim the world behind the screen, then let super render the widgets,
        // then draw the title on top.
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), argb(180, 10, 10, 14));
        super.extractRenderState(g, mouseX, mouseY, partial);
        g.centeredText(this.font, this.title, centerX, 10, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private StabiliConfig cfg() { return StabiliConfig.get(); }

    private static int argb(int a, int r, int gg, int b) {
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }
}
