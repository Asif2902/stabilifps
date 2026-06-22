# StabiliFPS

**A frame-time stabiliser mod for Minecraft Java Edition 26.1+ (Fabric).**

StabiliFPS does not try to raise your *average* FPS — Sodium, Lithium and
friends already do that well. Instead it chases a **flat frame-time graph**:
its single goal is to prevent the sudden `100 -> 8 -> 100` FPS stutter that
plagues Minecraft on a wide range of hardware, and to keep the framerate
stabilised for as long as possible.

> Built and verified against **Minecraft 26.1.2** with **Fabric Loader 0.19.3**,
> **Fabric API 0.152.1+26.1.2**, **Fabric Loom 1.17.12**, **Gradle 9.6.0** and
> **JDK 25**.

---

## Why this mod exists

A lot of players sit at a comfortable 100–300 FPS, but every few seconds the
framerate collapses to single digits for a frame or two and snaps back. That
hiccup is what feels "laggy" — not the average rate. StabiliFPS attacks the
four real causes of those drops:

| Cause of the drop | What StabiliFPS does |
|---|---|
| Chunk geometry stops fitting the frame budget | **Adaptive render distance** shrinks RD *before* the drop deepens, then grows it back with hysteresis |
| GPU burst → driver-queue stall → burst oscillation | **Adaptive framerate cap** hill-climbs toward the cap that maximises the 1% low |
| Dense entity scenes (farms, spawn chunks) | **Distance entity cull** drops far / tiny entities from the render list |
| Garbage-collection stop-the-world pauses | **GC monitor** tracks real pause durations and prints recommended JVM flags |

Everything is visible on a live HUD so you can *see* the stabilisation working.

---

## Features

- **Frame-time tracker** — 240-sample ring buffer computing average FPS, **1% low**,
  **0.1% low**, frame-time variance, hitch count, and a 0–100 stability score.
- **Adaptive render distance** — proactively lowers RD when frame time degrades,
  restores it when stable, with hysteresis + a floor so it never flaps or starves you.
- **Adaptive framerate cap** — finds the cap with the best 1% low / lowest variance
  for your current scene and hardware, and re-converges when the load changes.
- **Entity cull** — skips entities beyond a configurable distance (small entities
  culled even earlier), via a clean mixin on `ClientLevel.entitiesForRendering`.
- **GC monitor** — JMX-based, records the *actual duration* of every GC pause,
  warns on long pauses, and prints Aikar's-flag-style advice to the console.
- **Stability HUD** — overlay with FPS, 1% low, variance, hitch count, GC stats,
  active interventions, and a colour-coded frame-time sparkline.
- **In-game config screen** — every tunable is adjustable live (F6).

---

## Install (player)

1. Install the **Fabric Loader** 0.19+ for Minecraft **26.1.x** (use the
   [Fabric installer](https://fabricmc.net/use/installer/)).
2. Download **Fabric API** for 26.1.2 from
   [Modrinth](https://modrinth.com/mod/fabric-api/versions) and drop it in
   `.minecraft/mods`.
3. Drop `stabilifps-1.0.0.jar` into `.minecraft/mods`.
4. Launch Minecraft with the Fabric profile.

**Requirements:** Minecraft 26.1.2 · Java 25 · Fabric Loader 0.19+ · Fabric API.

### Keybinds

| Key | Action |
|---|---|
| `F6` | Open the config screen |
| `F7` | Show / hide the stability HUD (hidden by default) |
| `F8` | Toggle adaptive render distance |
| `F9` | Toggle the entire mod on/off |

Every keybind sends a brief in-game chat message so you know what changed.

---

## Build (developer)

```bash
# Requires JDK 25 (Temurin 25 works).
export JAVA_HOME=/path/to/jdk-25
./gradlew build
```

The mod jar is written to `build/libs/stabilifps-1.0.0.jar`.

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

## How it prevents drops (technical)

```
                ┌─────────────────────────────────────────────┐
   every frame  │  GameRenderer.render mixin ──► FrameTime     │
                │                                 Tracker      │
                │   (avg / 1% low / variance / hitches)        │
                └───────────────┬─────────────────────────────┘
                                │ degraded?
        ┌───────────────────────┼───────────────────────┐
        ▼                       ▼                       ▼
 AdaptiveRenderDistance    FpsGovernor           DistanceEntityCuller
  RD ──► smaller            cap ──► shallower     entities ──► fewer
  (less chunk geometry)     (shallower GPU queue) (less draw work)
        │                       │                       │
        └──────────► all reduce main-thread frame time ◄┘
                                │
                         frame time recovers
                                │
              adaptive systems grow RD / raise cap back up
              (hysteresis prevents flapping)
```

GcMonitor runs independently via the platform JMX
`GarbageCollectorMXBean` notification stream so GC-induced hitches are
attributable rather than mysterious.

---

## Config

Lives at `.minecraft/config/stabilifps.json` (auto-created). All values are
also editable in-game via F6. Key defaults:

| Option | Default | Meaning |
|---|---|---|
| `adaptiveRenderDistance` | true | shrink/grow RD with frame budget |
| `degradeThresholdMs` | 33.0 | frame time that triggers a shrink (~30 FPS) |
| `recoverThresholdMs` | 20.0 | frame time that triggers a grow (~50 FPS) |
| `hysteresisSamples` | 40 | consecutive ticks needed to transition |
| `adaptiveCap` | true | hill-climb the framerate cap |
| `entityCull` | true | cull far entities |
| `entityCullDistance` | 96 | blocks |
| `gcMonitor` | true | track GC pauses |

---

## Compatibility

StabiliFPS is **client-side only** and makes no gameplay changes, so it is
safe on any server. It composes well with Sodium / Lithium / FerriteCore
(those raise the average; StabiliFPS keeps it flat). It targets the Minecraft
26.1.x line specifically — it will not load on 1.21.x or older.

## License

MIT. Not an official Minecraft product; not affiliated with Mojang or Microsoft.
