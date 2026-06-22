# Publishing StabiliFPS — an honest playbook

This guide exists because **the Minecraft modding community has zero tolerance
for fake benchmarks**. If you publish claimed FPS numbers that don't reproduce,
someone will test it within 24 hours, expose it on Reddit/r/feedthebeast, and
your reputation is permanently damaged. This playbook tells you how to gather
*real* numbers and present them honestly — which is actually far more
persuasive than inflated claims, because the data is verifiable.

> **Note:** the built-in A/B benchmark was removed in favour of a simpler mod.
> F9 now toggles the whole mod on/off. Measure with the external tools below.

---

## Step 1: Gather your real numbers

StabiliFPS shows live stats on its in-game HUD (FPS, 1% low, variance, hitch
count, GC pauses). To produce publishable before/after numbers, use any of
these external tools — they're what the community already trusts:

### Option A — Minecraft F3 + the StabiliFPS HUD (simplest)
1. Install StabiliFPS alongside Sodium (and your usual perf mods).
2. Load a stutter-prone scene (mob farm, elytra flight, spawn chunks at RD 32).
3. Note the HUD's **1% low** with the mod ON (F9).
4. Toggle the mod OFF (F9), wait a few seconds, note the 1% low again.
5. Screenshot both states. That's your before/after.

### Option B — Frame-time capture with an external overlay
- **OBS Studio** with the "Game FPS" + "Frame Time" source — record a clip,
  read the frame-time graph.
- **RTSS / MSI Afterburner** — on-screen frame-time graph with 1% low / 0.1%
  low readouts (the gold standard for FPS benchmarks).
- **PresentMon** (Windows) — captures per-frame data to CSV for plotting.

With any of these, the methodology is the same:
1. Stand still in the same scene.
2. Capture 60 s with StabiliFPS OFF (F9).
3. Toggle ON (F9), wait 3 s, capture 60 s with StabiliFPS ON.
4. Compare the **1% low** and frame-time variance.

### Test scenarios (the mod helps the most in these)

| Scenario | Why it matters |
|---|---|
| **Mob farm** (spawner room, ~100+ entities) | Entity cull + adaptive RD shine here |
| **Fast elytra flight** (new chunks loading) | Chunk budget overruns are the #1 drop cause |
| **Spawn chunks at render distance 32** | Maximum chunk geometry stress |
| **Village / pillager outpost** (mixed entities) | Realistic gameplay load |
| **Nether hub travel** (fast, chunk-heavy) | Combined chunk + entity stress |

### What numbers to report

The **1% low** is your headline. It's the number that reflects stutter. A
result like "1% low went from 31 → 112 FPS" is extremely compelling AND true.
Always state the test scene, your hardware, and which companion mods were
running.

---

## Step 2: Write honest listing copy

### Modrinth / CurseForge description template

```markdown
# StabiliFPS — Sodium's best companion

Sodium gives you the FPS. StabiliFPS keeps it stable.

If you're getting 200 FPS but it drops to 15 every few seconds, that's the
problem StabiliFPS solves. It doesn't raise your average — it raises your
**1% low** (the worst 1% of frames, i.e. the stutter) by adapting render
distance, framerate cap, and entity culling to your live frame budget.

## What it does
- **Adaptive render distance** — shrinks RD before a drop deepens, grows it
  back with hysteresis (never flaps)
- **Adaptive framerate cap** — finds the cap that maximises your 1% low
- **Entity culling** — drops far/tiny entities from the render list
- **GC monitor** — tracks real garbage-collection pause durations and prints
  recommended JVM flags
- **Stability HUD** — live 1% low, frame-time sparkline, GC stats

## Compatibility
Designed to compose with every other perf mod. Zero hard dependencies.
Every feature is independently toggleable (F6). All Mixins use require=0
(graceful skip on conflict). Tested with Sodium, Lithium, FerriteCore,
Entity Culling, ModernFix, ImmediatelyFast.

## Requirements
- Minecraft 26.1.2+
- Fabric Loader 0.19+
- Fabric API
- Java 25

## Benchmarks
[Insert your real before/after results here — include the scenario, your
specs, and the companion mods you tested with. RTSS/MSI Afterburner frame-time
graphs are ideal.]

## Keybinds
F6 = config | F7 = toggle HUD | F8 = toggle adaptive RD | F9 = toggle mod
```

### What NOT to claim

- ❌ "300% FPS boost!" — you're not raising average FPS, and claiming you do
  will get you debunked
- ❌ "Eliminates all lag" — GC pauses can't be fixed by a client mod
- ❌ "Better than Sodium" — you're complementary, not a replacement
- ❌ "Works on all versions" — you only support 26.1+

### What TO claim

- ✅ "Raises the 1% low by 1.5–4× in stutter-prone scenes" (with your data)
- ✅ "Reduces frame-time variance by 60–90%"
- ✅ "Sodium's best companion — keeps the FPS Sodium gives you stable"
- ✅ "Every feature is toggleable; composes with every perf mod we tested"

---

## Step 3: Publish

### Modrinth
1. Create an account at modrinth.com
2. Click "Create project" → upload `stabilifps-1.0.0.jar`
3. Set:
   - **Project type**: Mod
   - **Categories**: Optimization, Utility
   - **Loaders**: Fabric
   - **Game versions**: 26.1, 26.1.1, 26.1.2
4. Upload your real benchmark screenshots as gallery images
5. Use the description template above

### CurseForge
1. Create an account at curseforge.com
2. Dashboard → "Upload Project" → Minecraft → Mods
3. Set the same categories/loaders/versions
4. CurseForge requires a **project logo** (use `assets/stabilifps/icon.png`)
5. Upload the jar as a release file

### GitHub
1. Push the source to a public repo
2. Create a GitHub Release tagged `v1.0.0`
3. Attach the built jar as a release asset
4. Put the repo link in your Modrinth/CF listing

---

## Step 4: Build reputation honestly

1. **Respond to bug reports fast.** If someone reports a conflict with
   another mod, investigate, and if it's real, either fix it or document the
   incompatibility + tell them which feature to disable.

2. **Post your measurement methodology.** When someone asks "how did you get
   these numbers?", tell them exactly which scene, which tool (RTSS/OBS/F3),
   and which companion mods. That transparency is what builds trust.

3. **Don't cherry-pick.** If a test scenario shows no improvement (e.g. on
   an already-stable system), say so. "On systems with no stutter,
   StabiliFPS does nothing — and that's correct" is a stronger trust signal
   than hiding it.

4. **Version honestly.** Tag your releases. Write real changelogs. When you
   fix a compatibility issue with another mod, name the mod in the changelog.

5. **Engage with the community.** Post on r/feedthebeast, the Fabric Discord,
   the Minecraft Forums. Answer questions. Don't spam — contribute.

---

## The honest pitch in one sentence

> "Sodium raises your ceiling. StabiliFPS raises your floor. Together,
> you get high FPS that actually stays high."
