# Player Sheet 96 fps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Raise the player open/close animation from ~55 fps average (65 peak) toward the display's 96 Hz (10.4 ms/frame) with zero visual or functional change.

**Architecture:** The animation is already main-thread-cheap (edge is read only in layer/draw lambdas). The remaining cost is on the GPU/RenderThread: `dumpsys gfxinfo` shows GPU p50 8 ms / p90 10 ms, i.e. the whole 10.4 ms budget. So the plan removes per-frame GPU work that produces an unchanged picture (frozen glass effect chains, full-page path clips), then tests frame-rate hints. Every task is measured A/B and reverted if it gains <5% or changes a pixel.

**Tech Stack:** Compose `GraphicsLayer`, `RenderEffect`/`RuntimeShader`, HWUI/Skia, `adb dumpsys gfxinfo framestats`, Perfetto.

**Spec:** User request 2026-10-04: "il ne dépasse pas les 65fps, moyenne ~55 ... me rapprocher le plus possible des 96 du smartphone ... sans dégrader le design et les fonctionnalités". Prior work: `2026-10-04-liquid-glass-pills-jank-fix.md`.

## Global Constraints

- Worktree `copilot-worktrees/Aura/dooorians-shiny-giggle`, branch `qa-liquid-glass-pills`; build `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleQa`; app id `com.ikko.aura.qa`; device `192.168.50.130:5555` (MindOne, API 35, fixed 96.0 Hz, 1080x1240).
- Frame budget: 1000/96 = 10.4 ms.
- Pixel-identical at rest AND at mid-travel (blur, lens refraction, highlight, shadow, tint, corner radii, dim, wash).
- Keep `GlassSettle` semantics, the `try/finally` unfreeze, and queue/player behaviour untouched.
- Copy APK to `/Users/doriansemlali/Desktop/Aura_QA.apk` after each build.

## Findings (what costs GPU time during travel)

| # | Suspect | Where | Why it costs |
|---|---|---|---|
| A | Glass effect chain still **executes every frame while frozen** (6 player panels + pills: colorMatrix, blur, lens `RuntimeShader`, highlight shader, shadow) | `DrawBackdropModifier.kt` `drawBackdropLayer` (freeze only skips *re-recording*; `layer.renderEffect` is re-applied by HWUI on every replay) | Dominant suspect: ~8 effect passes/frame on a static picture |
| B | Page under the card clipped by a complex `Path` (`Difference`) every frame, plus a full-screen `saveLayer` for the reveal on close | `MainLayout.kt` ~L379-L399 (`setToCard`, `clipPath`, `saveLayer`) | Path clip is a mask pass over the whole page; card is opaque on open, so most of it is pure overdraw saving vs mask cost (measure) |
| C | Page recedes with scale + translate + `TopRoundedClip` each frame | `MainLayout.kt` ~L402-L420 | Clip on a full page layer; cheap if rect, costly if path |
| D | Card is a full-screen layer with rounded clip + (on close) alpha < 1 → offscreen buffer | `NowPlayingScreen.kt` L338-L359 | Already tuned; only the close tail (edge > 0.70) pays alpha |
| E | Frame-rate vote: nothing asks the system for 96 Hz during travel | no `setRequestedFrameRate` | Cheap to test; may explain the 65 ceiling if the system votes lower |
| F | Ambient work running during travel (Aurora background, seek-bar/progress ticker, lyric/art crossfades) | `AuroraBackground.kt`, `NowPlayingScreen.kt` | Dirties layers → extra record/raster passes |

## Review Focus

1. Mid-travel pixel fidelity: baked/frozen glass must equal live glass (screenshot diff at edge ≈ 0.5).
2. Seamless unfreeze at landing: no pop when `frozen` flips back.
3. Interrupted open/close (retap, back gesture) must never leave glass stuck on a stale bake.
4. Track change / cover change right before opening: the bake must reflect the new cover.
5. Battery-saver path (`LocalAuraBatterySaver`) and API < 31 (no RenderEffect) unchanged.

---

### Task 0: Repeatable benchmark + attribution (no product code changes)

**Files:**
- Create: `scripts/perf/sheet_bench.sh`

**Interfaces:**
- Produces: `sheet_bench.sh <runs>` → prints avg fps (from `dumpsys gfxinfo --framestats` intervals during the animation window only), janky %, p50/p90/p99 frame ms, p50/p90 GPU ms. Used by every later task.

- [ ] **Step 1:** Script: `gfxinfo reset`, tap the mini player, sleep 1 s, `gfxinfo framestats`, swipe down, sleep 1 s; repeat N=10; aggregate frames whose timestamps fall inside open/close windows.
- [ ] **Step 2:** Record baseline (3 invocations, median) in the PR description.
- [ ] **Step 3:** One Perfetto trace of open+close (`gfx`, `view`, `sched`, `gpu` categories); record top RenderThread slices (expected: effect/`SkImageFilter`, `clipPath`, `saveLayer`). This confirms or reorders A/B/C below before any code is touched.
- [ ] **Step 4:** Capture reference screenshots: rest (library + pills), edge ≈ 0.5 mid-open (via `screenrecord` frame extraction), player fully open. Stored under `docs/superpowers/perf/baseline/` for pixel diffs.
- [ ] **Step 5:** Commit `chore(perf): add sheet benchmark script`.

### Task 1: Ask for 96 Hz during travel (lowest risk, test first)

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/ui/screens/nowplaying/NowPlayingScreen.kt` L277-L288, L305-L327 (inside the existing try/finally)

**Interfaces:**
- Consumes: the `frozen` window already set around both `animate(...)` calls.
- Produces: `fun View.holdHighFrameRate(active: Boolean)` (private, same file): sets `requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH` (API 35, guarded by `Build.VERSION.SDK_INT`) while active, restores `REQUESTED_FRAME_RATE_CATEGORY_DEFAULT` in `finally`.

- [ ] **Step 1:** Run Task 0 benchmark; note baseline.
- [ ] **Step 2:** Implement `holdHighFrameRate` and call `true` right before each `animate`, `false` in each `finally`.
- [ ] **Step 3:** Re-run benchmark. Keep if avg fps improves; otherwise revert (then the ceiling is GPU, not voting).
- [ ] **Step 4:** Commit `perf(player): request high frame rate while the sheet travels`.

### Task 2: Bake the frozen glass instead of re-running its effect chain (suspect A, biggest expected win)

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/ui/components/backdrop/DrawBackdropModifier.kt` (`DrawBackdropNode.drawBackdropLayer`, `onDetach`, `resetEffect`)
- Test: `app/src/androidTest/.../GlassBakeTest.kt` (instrumented, pixel compare live vs baked)

**Interfaces:**
- Consumes: `LocalGlassSettle.frozen`, `hasRecordedLayer`.
- Produces: in `DrawBackdropNode`, `private var baked: ImageBitmap?` and `private fun bakeEffectOutput()`: when `frozen` becomes true and a recorded layer exists, render the effect layer **at the same 0.33 scale** into an `ImageBitmap` once (`GraphicsLayer.toImageBitmap()`), then while frozen draw that bitmap with the same `scale(1/scale)` upscale and shape clip instead of `drawLayer(layer)`; drop `baked` (set null) when `frozen` turns false, in `resetEffect()` and in `onDetach()`.

- [ ] **Step 1: Spike (time-boxed)** on one panel: confirm `toImageBitmap()` cost (<1 frame, done inside the existing 1-frame + `repeat(3)` waits before travel) and that the upscaled bitmap matches `drawLayer(layer)` pixel for pixel (diff ≤ 1/255 per channel). If it does not match or is async-unsafe, abandon Task 2 and fall back to Task 2b.
- [ ] **Step 2:** Write failing instrumented test `bakedGlassMatchesLiveGlass`: render a panel live, freeze + bake, render again, assert max channel diff ≤ 1.
- [ ] **Step 3:** Implement bake/draw/release as in Interfaces; highlight and shadow stay live (cheap) unless the trace shows otherwise.
- [ ] **Step 4:** Test `bakeIsDroppedOnUnfreezeAndDetach` (no stale bitmap after interrupted travel; covers Review Focus 3 and 4).
- [ ] **Step 5:** Benchmark; expected GPU p50 drops well below 8 ms. Visual diff vs Task 0 screenshots.
- [ ] **Step 6:** Commit `perf(backdrop): draw a baked glass picture while the sheet travels`.

**Task 2b (fallback if the bake spike fails):** keep live effects but cut work without visible change: skip `colorControls` and highlight re-evaluation while frozen is NOT allowed (changes pixels); instead only test lowering `GLASS_RESOLUTION_SCALE` 0.33 → 0.28 *during travel only* and judge by diff. Reject if any diff is visible.

### Task 3: Make the page-under-card clip cheaper (suspects B, C)

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/MainLayout.kt` L379-L420

**Interfaces:**
- Consumes: `setToCard(cutout, o, size, corner)`, `card.cardAlpha`.
- Produces: no new API; behaviour variants behind a local `const val` during measurement.

- [ ] **Step 1:** Using the Task 0 trace, check if the `clipPath(Difference)` mask pass and the close-time `saveLayer` show up in RenderThread. Skip the rest of this task if they cost <0.5 ms.
- [ ] **Step 2:** Variant 1 (opening only, card opaque): replace the `Difference` path clip with a `clipRect` limited to the exposed area plus the two corner patches, so only the corner regions use a path. Pixel-identical by construction since the card is opaque there.
- [ ] **Step 3:** Variant 2: in `TopRoundedClip`, use a rect clip when `corner` is below 0.5 px.
- [ ] **Step 4:** Keep the `reveal` `saveLayer` only while `reveal > 0.004f` (already) and confirm it never runs before `edge > SHEET_CLEAR_FROM`.
- [ ] **Step 5:** Benchmark + screenshot diff (corners at edge ≈ 0.5 are the risk). Revert whichever variant changes a pixel or gains <5%.
- [ ] **Step 6:** Commit `perf(sheet): cheaper clip for the page under the card`.

### Task 4: Quiet ambient work while the sheet travels (suspect F)

**Files:**
- Modify: `AuroraBackground.kt`, `NowPlayingScreen.kt` (progress ticker), only where the trace shows layers invalidating during travel

**Interfaces:**
- Consumes: `LocalSheetCard.current.frozen`.
- Produces: ambient animations hold their current value while `frozen` is true and resume after, with no jump.

- [ ] **Step 1:** From the trace, list composables/layers invalidated during the 320 ms window. Ignore anything not listed.
- [ ] **Step 2:** For each listed item, read `frozen` in the draw/layer lambda (not composition) and skip its time update.
- [ ] **Step 3:** Test `ambientResumesWithoutJump` (value after unfreeze continues from the held value).
- [ ] **Step 4:** Benchmark, commit `perf(player): pause ambient animations during sheet travel`.

### Task 5: Final verification

- [ ] **Step 1:** Run benchmark 3x; compare with Task 0 (target: avg ≥ 80 fps, p90 frame ≤ 12 ms, janky < 5%).
- [ ] **Step 2:** Pixel diff all reference screenshots (rest, mid-open, mid-close, open). Zero visible difference required.
- [ ] **Step 3:** Functional pass: open/close via tap, swipe, back; spam-tap the pill; change track then open; battery-saver on; queue and shuffle still consistent.
- [ ] **Step 4:** Build, copy APK to Desktop, install, commit.

## Expected impact / order of attack

Task 0 first (it can reorder everything). Then Task 1 (minutes, may by itself lift the 65 fps ceiling), Task 2 (largest expected GPU saving), Task 3, Task 4. Realistic target: 80-96 fps; the closing tail with alpha < 1 is the likeliest part to stay slightly below 96.
