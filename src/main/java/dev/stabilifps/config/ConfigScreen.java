package dev.stabilifps.config;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In-game config screen (opened with the F6 keybind).
 *
 * <p>Implemented with plain {@link Button} widgets rather than slider widgets,
 * so it stays robust across Minecraft 26.x refactors. Toggles flip on click;
 * numeric settings use a [-] value [+] triplet. Every change is saved
 * immediately to {@code config/stabilifps.json}.</p>
 *
 * <h2>Scrolling</h2>
 * <p>The content is laid out at a virtual y-offset that scrolls. When the
 * total content height exceeds the viewport, the user scrolls with the mouse
 * wheel. Widgets are repositioned each frame relative to the current scroll
 * offset, and the title bar + Done button are pinned outside the scroll region
 * so they never move or overlap content.</p>
 *
 * <p>26.1 renders screens via {@code extractRenderState(GuiGraphicsExtractor, ...)}
 * rather than the old {@code render(GuiGraphics, ...)}; drawing goes through
 * the extractor's {@code fill/centeredText} helpers.</p>
 */
public class ConfigScreen extends Screen {
    private static final int ROW_H = 22;
    private static final int PAD = 8;
    private static final int TITLE_H = 24;     // reserved top band for the title
    private static final int FOOTER_H = 32;    // reserved bottom band for Reset + Done

    private int centerX;
    private int contentW;

    /** Logical (unscrolled) y where the next row will be placed during layout. */
    private int layoutY;
    /** Total content height after layout (for clamping scroll). */
    private int contentHeight = 0;
    /** Current scroll offset in pixels (always >= 0, clamped to maxScroll). */
    private int scroll = 0;

    /** Flat list of every widget plus the layout y it should sit at. */
    private final List<Placed> placed = new ArrayList<>();

    private static final class Placed {
        final Button button;
        final int baseX;     // x relative to content layout (never changes)
        int baseY;           // y relative to top of content (pre-scroll)
        final int width;
        final int height;
        Placed(Button button, int baseX, int baseY, int width, int height) {
            this.button = button; this.baseX = baseX;
            this.baseY = baseY; this.width = width; this.height = height;
        }
    }

    public ConfigScreen(Screen parent) {
        super(Component.literal("StabiliFPS Configuration"));
    }

    @Override
    protected void init() {
        centerX = this.width / 2;
        contentW = Math.min(360, this.width - 2 * PAD);
        layoutY = 0;
        placed.clear();

        addToggle("Mod enabled", () -> cfg().enabled, v -> cfg().enabled = v);
        addToggle("Show HUD (F7)", () -> cfg().showHud, v -> cfg().showHud = v);
        addToggle("Compact HUD", () -> cfg().hudCompact, v -> cfg().hudCompact = v);

        section("Render-distance governor (raise-only; never lowers your RD)");
        addToggle("RD governor (F8)", () -> cfg().rdGovernor, v -> cfg().rdGovernor = v);
        addStepper("Max RD", () -> cfg().maxRenderDistance,
                v -> cfg().maxRenderDistance = StabiliConfig.clamp(v, 4, 64), 2, 4, 64);
        addStepper("Degrade threshold (ms)", () -> (int) cfg().degradeThresholdMs,
                v -> cfg().degradeThresholdMs = StabiliConfig.clamp((double) v, 16, 100), 1, 16, 100);

        section("Chunk-load pacer");
        addToggle("Chunk pacer", () -> cfg().chunkPacer, v -> cfg().chunkPacer = v);
        addStepper("Frame budget (us)", () -> (int) cfg().chunkPacerFrameBudgetMicros,
                v -> cfg().chunkPacerFrameBudgetMicros = StabiliConfig.clamp(v, 200, 8000), 100, 200, 8000);

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

        section("GC monitoring + allocation");
        addToggle("GC monitor", () -> cfg().gcMonitor, v -> cfg().gcMonitor = v);
        addToggle("Print GC advice", () -> cfg().printGcAdvice, v -> cfg().printGcAdvice = v);
        addToggle("GC-aware deferral", () -> cfg().gcAwareDeferral, v -> cfg().gcAwareDeferral = v);

        contentHeight = layoutY;

        // Footer: Reset to recommended (left) + Done (right), pinned.
        int footY = this.height - FOOTER_H + 4;
        addRenderableWidget(Button.builder(Component.literal("Reset to recommended"), b -> {
            StabiliConfig.resetToRecommended();
            this.rebuildWidgets();
        }).bounds(centerX - 175, footY, 160, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(centerX + 15, footY, 120, 20).build());

        // Clamp scroll in case the viewport shrank (window resize).
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    /** Available vertical space for scrolling content. */
    private int viewportHeight() {
        return Math.max(0, this.height - TITLE_H - FOOTER_H);
    }

    /** Maximum number of pixels we can scroll down. 0 when content fits. */
    private int maxScroll() {
        return Math.max(0, contentHeight - viewportHeight());
    }

    private void section(String name) {
        layoutY += 6;
        int x = centerX - contentW / 2;
        Button btn = Button.builder(Component.literal("\u00A77" + name), b -> {})
                .bounds(x, 0, contentW, 16).build();
        register(btn, x, layoutY, contentW, 16);
        layoutY += ROW_H - 4;
    }

    private void addToggle(String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        int x = centerX - contentW / 2;
        Button btn = Button.builder(toggleLabel(label, get.get()), b -> {
            set.accept(!get.get());
            b.setMessage(toggleLabel(label, get.get()));
            StabiliConfig.save();
        }).bounds(x, 0, contentW, 20).build();
        register(btn, x, layoutY, contentW, 20);
        layoutY += ROW_H;
    }

    private void addStepper(String label, Supplier<Integer> get, Consumer<Integer> set, int step, int lo, int hi) {
        int x = centerX - contentW / 2;
        int minusW = 20;
        int plusW = 20;
        int labelW = contentW - minusW - plusW - 4;
        int lx = x;
        int mx = x + labelW + 4;
        int px = mx + minusW + 2;

        Button labelBtn = Button.builder(stepperLabel(label, get.get()), b -> {})
                .bounds(lx, 0, labelW, 20).build();
        Button minus = Button.builder(Component.literal("-"), b -> {
            int nv = StabiliConfig.clamp(get.get() - step, lo, hi);
            set.accept(nv);
            labelBtn.setMessage(stepperLabel(label, get.get()));
            StabiliConfig.save();
        }).bounds(mx, 0, minusW, 20).build();
        Button plus = Button.builder(Component.literal("+"), b -> {
            int nv = StabiliConfig.clamp(get.get() + step, lo, hi);
            set.accept(nv);
            labelBtn.setMessage(stepperLabel(label, get.get()));
            StabiliConfig.save();
        }).bounds(px, 0, plusW, 20).build();

        register(labelBtn, lx, layoutY, labelW, 20);
        register(minus, mx, layoutY, minusW, 20);
        register(plus, px, layoutY, plusW, 20);
        layoutY += ROW_H;
    }

    /** Register a widget for both rendering (addRenderableWidget) and layout (placed). */
    private void register(Button btn, int x, int y, int w, int h) {
        addRenderableWidget(btn);
        placed.add(new Placed(btn, x, y, w, h));
    }

    private static Component toggleLabel(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "\u00A7aON" : "\u00A7cOFF"));
    }
    private static Component stepperLabel(String label, int value) {
        return Component.literal(label + ": \u00A7e" + value);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        // Backdrop over the whole screen.
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), argb(180, 10, 10, 14));

        // Position each content widget for the current scroll offset. A widget
        // that falls entirely outside the viewport is parked far off-screen so
        // it can neither render nor absorb clicks.
        int top = TITLE_H;
        int bottom = this.height - FOOTER_H;
        for (Placed p : placed) {
            int y = top + p.baseY - scroll;
            if (y + p.height <= top || y >= bottom) {
                p.button.setPosition(p.baseX, -10000);
            } else {
                p.button.setPosition(p.baseX, y);
            }
        }

        // Dim bands behind the pinned title and footer so content scrolling
        // under them reads as separate regions.
        g.fill(0, 0, g.guiWidth(), TITLE_H, argb(200, 0, 0, 0));
        g.fill(0, bottom, g.guiWidth(), g.guiHeight(), argb(200, 0, 0, 0));

        super.extractRenderState(g, mouseX, mouseY, partial);

        // Title on top of everything.
        g.centeredText(this.font, this.title, centerX, 8, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // scrollY > 0 = wheel up = scroll toward top.
        int before = scroll;
        int delta = (int) Math.copySign(Math.ceil(Math.abs(scrollY) * ROW_H), -scrollY);
        scroll = Math.max(0, Math.min(maxScroll(), scroll + delta));
        return scroll != before;
    }

    /** Reposition widgets whenever they are ticked (covers resize, reopen). */
    @Override
    public void tick() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private StabiliConfig cfg() { return StabiliConfig.get(); }

    private static int argb(int a, int r, int gg, int b) {
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }
}
