# StabiliFPS

**A frame-time stabiliser mod for Minecraft Java Edition 26.1+ (Fabric).**

> **Sodium raises your ceiling. StabiliFPS raises your floor.**

StabiliFPS does not try to raise your *average* FPS — Sodium, Lithium and
friends already do that well. Instead it chases a **flat frame-time graph**:
its single goal is to prevent the sudden `100 -> 8 -> 100` FPS stutter that
plagues Minecraft on a wide range of hardware, and to keep the framerate
stabilised for as long as possible.

> Built and verified against **Minecraft 26.1.2** with **Fabric Loader 0.19.3**,
> **Fabric API 0.152.1+26.1.2**, **Fabric Loom 1.17.12**, **Gradle 9.6.0** and
> **JDK 25**.

---

## Design philosophy

**Measure honestly. Intervene surgically. Never compromise gameplay.**

Every feature is either **pure-good** (measures or paces and cannot degrade
your gameplay) or **opt-in** (changes something you can see). The one
gameplay-affecting system that ships ON by default — the render-distance
governor — is **strictly additive**: it only ever *raises* render distance when
your GPU is comfortable, and **never lowers it below what you chose**. Your
render distance is a hard floor. This makes the old "chunk flash" feedback loop
structurally impossible.

This philosophy came directly from a bug in v1.0: the adaptive systems changed
render distance / framerate cap live in-world, and because changing render
distance reloads chunks, that *caused* the stutter the mod existed to fix. v1.1
removes every code path that lowers your settings.

---

## Why this mod exists

Players sit at a comfortable 100–300 FPS, but every few seconds the framerate
collapses to single digits for a frame or two and snaps back. That hiccup is
what feels "laggy" — not the average rate. Research and community reports (and
[Mojang bug MC-166005](https://bugs.mojang.com/browse/MC/issues/MC-166005))
point at the same root cause: **chunk-meshing bursts** when you fly fast,
teleport, or cross into fresh terrain. Existing perf mods *speed up* meshing;
nobody *paces* it. StabiliFPS does.

| Cause of the drop | What StabiliFPS does |
|---|---|
| Chunk-mesh upload bursts dominate a frame | **Chunk-load pacer** spreads the burst across frames against an adaptive µs budget |
| GC stop-the-world pauses from allocation pressure | **Allocation budget** defers non-critical allocating work when a GC is imminent; **GC monitor** tracks real pauses |
| You set RD too high for your hardware | **Render-distance governor** raises RD when healthy and *only ever raises it* — on degradation it tells you, it never lowers your setting |
| Want a tighter frame pacing | **Adaptive framerate cap** (opt-in) hill-climbs toward the cap that maximises the 1% low |

Everything is visible on a live HUD that tells you **why** each frame dropped
(`chunk` / `gc` / `entity`), so the mod is transparent rather than mysterious.

---

## Features

- **Frame-time tracker** — 240-sample ring buffer computing average FPS, **1% low**,
  **0.1% low**, frame-time variance, hitch count, and a documented 0–100
  stability score (weighted: 1% low 40%, variance 25%, 0.1% low 20%, headroom 15%).
- **Render-distance governor** — raises RD toward your configured max when frame
  time is healthy; **never lowers it**. On degradation it freezes growth and hints
  that your RD may be too high. Your chosen RD is always respected.
- **Chunk-load pacer** — adaptive per-frame upload budget. Throttles when frame
  time is degraded, full-throttle during initial world load (so the world appears
  fast), normal otherwise. The headline white-space feature no other mod has.
- **Allocation budget** — defers non-critical allocating work by one frame when
  the young generation is near a GC boundary. Pure-good; can only reduce pauses.
- **GC monitor** — JMX-based, records the *actual duration* of every GC pause,
  warns on long pauses, attributes those hitches, and prints JVM-flag advice.
- **Entity cull** *(opt-in)* — skips entities beyond a configurable distance.
- **Stability HUD** — overlay with FPS, 1% low, variance, hitch count, GC stats,
  the **why-it-stuttered tag**, active interventions, and a frame-time sparkline.
- **In-game config screen** — every tunable adjustable live, scrollable, with a
  **Reset to recommended** button (F6).

---

## Install (player)

1. Install the **Fabric Loader** 0.19+ for Minecraft **26.1.x** (use the
   [Fabric installer](https://fabricmc.net/use/installer/)).
2. Download **Fabric API** for 26.1.2 from
   [Modrinth](https://modrinth.com/mod/fabric-api/versions) and drop it in
   `.minecraft/mods`.
3. Drop `stabilifps-1.1.0.jar` into `.minecraft/mods`.
4. Launch Minecraft with the Fabric profile.

**Requirements:** Minecraft 26.1.2 · Java 25 · Fabric Loader 0.19+ · Fabric API.

Out of the box the mod is **zero-config safe**: the HUD and GC monitor are on,
the RD governor is on (raise-only), the chunk pacer is on, and the cap / entity
cull are off. Install it and you immediately get smoother frames.

### Keybinds

| Key | Action |
|---|---|
| `F6` | Open the config screen |
| `F7` | Show / hide the stability HUD (on by default, compact) |
| `F8` | Toggle the render-distance governor (raise-only) |
| `F9` | Toggle the entire mod on/off |

Every keybind sends a brief in-game chat message so you know what changed.

---

## Build (developer)

```bash
# Requires JDK 25 (Temurin 25 works).
export JAVA_HOME=/path/to/jdk-25
./gradlew build      # produces build/libs/stabilifps-1.1.0.jar
./gradlew test       # runs the pure-logic regression suite
```

### Toolchain (verified)

| Component | Version |
|---|---|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.152.1+26.1.2 |
| Fabric Loom | 1.17.12 |
| Gradle | 9.6.0 |
| Java | 25 |

> 26.1 is the **first non-obfuscated** Minecraft release, so the build uses
> Mojang official mappings directly (no Yarn), plain `implementation` deps
> (no `modImplementation`), and the plain `jar` task (no `remapJar`). See the
> [Fabric "Porting to 26.1" guide](https://docs.fabricmc.net/develop/porting).

---

## How it keeps frames flat (technical)

```
 every frame  GameRenderer.render mixin ──► ChunkLoadPacer.beginFrame()
              └─────────────────────────► FrameTimeTracker.onFrame()
                                          (avg / 1% low / variance / hitches)

  chunk mesh about to upload ─► ChunkUploadMixin ──► ChunkLoadPacer.allowUpload()
                                  (charges the per-frame µs budget; attributes
                                   hitches to CHUNK when over budget)

  GC pause fires ─► GcMonitor (JMX) ──► records duration, attributes to GC

  20 Hz tick ─► RenderDistanceGovernor  (raise-only; never lowers your RD)
             └► AllocationBudget        (defer allocating work if GC imminent)
             └► FpsGovernor             (opt-in adaptive cap)
```

All mixins use `require = 0`, so if a future 26.x drop reshapes an injection
point, the mod no-ops that feature rather than crashing — vanilla behaviour
wins over a crash, always.

---

## Config

Lives at `.minecraft/config/stabilifps.json` (auto-created). All values are
editable in-game via F6, and **Reset to recommended** restores the zero-config
safe defaults. Key defaults:

| Option | Default | Meaning |
|---|---|---|
| `rdGovernor` | true | raise RD toward max when healthy; never lower |
| `chunkPacer` | true | spread chunk-mesh bursts across frames |
| `gcAwareDeferral` | true | defer allocating work before a GC |
| `gcMonitor` | true | track GC pauses |
| `showHud` | true | stability HUD on (compact) |
| `adaptiveCap` | false | opt-in adaptive framerate cap |
| `entityCull` | false | opt-in far-entity culling |
| `maxRenderDistance` | 32 | governor ceiling (floor = your setting) |

---

## Compatibility

StabiliFPS is **client-side only** and never changes anything you can see
against your will, so it is safe on any server. It composes with Sodium /
Lithium / FerriteCore (those raise the average; StabiliFPS keeps it flat) and
with ImmediatelyFast / ModernFix / EntityCulling. It targets the Minecraft
26.1.x line specifically — it will not load on 1.21.x or older.

## License

MIT. Not an official Minecraft product; not affiliated with Mojang or Microsoft.
