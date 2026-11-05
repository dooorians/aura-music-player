# Liquid Glass Pills Performance Optimization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate stutter and jank during NowPlaying player opening and closing sheet animations caused by live liquid glass shaders in the pills, while preserving 100% of the liquid glass design.

**Architecture:** Extend the existing `GlassSettle` freeze mechanism so that during sheet card animation (opening and closing), `SheetCard.frozen` pauses both redundant full-screen `pillBackdrop` re-recordings and expensive effect chain passes (`RenderEffect.blur` and `RuntimeShader.lens`) on the underlying pills (`TabCapsule`, `SearchCapsuleBubble`, and `FloatingPlayerPill`), reusing their hardware-accelerated cached `GraphicsLayer` textures during travel and resuming live reactivity once landed.

**Tech Stack:** Kotlin, Jetpack Compose UI, Android Graphics (`GraphicsLayer`, `RenderEffect`, `RuntimeShader`), Skia HWUI, `adb dumpsys gfxinfo`.

**Spec:** User request: "depuis que le liquid glass dans les pillules on étaient réintroduis, l'animation d'ouverture et de fermeture du lecteur est saccadé. analyse rigouresemment, établis un plan, utilise superpowers pour t'aider, et propose moi une solution sans détériorer le design".

## Global Constraints

- Android target branch: `qa-liquid-glass-pills` in worktree `copilot-worktrees/Aura/dooorians-shiny-giggle`.
- Build target: `./gradlew assembleQa` with `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- App ID: `com.ikko.aura.qa`.
- Keep desktop APK synced: `/Users/doriansemlali/Desktop/Aura_QA.apk`.
- Zero visual degradation: When resting, all liquid glass surfaces must retain full refraction, blur, chromatic highlights, and tint.

## Review Focus

1. Open sheet travel: The rising card must not stutter or drop frames when transitioning from collapsed mini player to fullscreen.
2. Close sheet travel: The sinking card must transition smoothly down to the pill without 30ms+ janky frame spikes around the handoff threshold.
3. Liquid glass fidelity at rest: The mini player pill, search bubble, and tab capsule must retain pixel-identical refraction and blur over album covers and library lists.
4. Seamless unfreeze: No visual popping or flash when `frozen` transitions between true and false at the end of travel.
5. Interruptions: Interrupting an opening or closing animation (e.g., immediate back gesture or retap) must cleanly reset `frozen` via `try ... finally`.

---

### Task 1: Add Cached Layer Reuse on Frozen State in `DrawBackdropModifier.kt`

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/ui/components/backdrop/DrawBackdropModifier.kt:380-480`

**Interfaces:**
- Consumes: `LocalGlassSettle` (`GlassSettle.frozen`)
- Produces: `hasRecordedLayer` caching inside `DrawBackdropNode.drawBackdropLayer` to avoid re-running `recordLayer` (color matrix, blur, RuntimeShader lens) when `settle?.frozen == true`.

- [x] **Step 1: Inspect `DrawBackdropNode` and add `hasRecordedLayer` flag**

In `DrawBackdropNode`:
```kotlin
private var hasRecordedLayer = false
```
In `resetEffect()`:
```kotlin
hasRecordedLayer = false
```

- [x] **Step 2: Update `drawBackdropLayer` to bypass `recordLayer` while frozen**

In `drawBackdropLayer`:
```kotlin
val settle = currentValueOf(LocalGlassSettle)
val frozen = settle?.frozen == true && hasRecordedLayer

if (!frozen) {
    recordLayer(
        this@DrawBackdropNode,
        layer,
        size = IntSize(
            ((size.width * scale).toInt() + padding.toInt() * 2).coerceAtLeast(1),
            ((size.height * scale).toInt() + padding.toInt() * 2).coerceAtLeast(1)
        ),
        block = recordBackdropBlock
    )
    hasRecordedLayer = true
}
```

- [x] **Step 3: Verify compilation of `DrawBackdropModifier.kt`**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew compileQaKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 4: Commit Task 1 changes**

```bash
git add app/src/main/java/com/ikko/aura/ui/components/backdrop/DrawBackdropModifier.kt
git commit -m "perf(backdrop): bypass expensive layer re-recording when glass settle is frozen"
```

---

### Task 2: Add Animation `frozen` State to `SheetCard` and Wire in `NowPlayingScreen.kt`

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/MainLayout.kt:220-285`
- Modify: `app/src/main/java/com/ikko/aura/ui/screens/nowplaying/NowPlayingScreen.kt:270-325`

**Interfaces:**
- Consumes: Sheet open/close animation lifecycle in `NowPlayingScreen.kt`
- Produces: `SheetCard.frozen` observable property indicating when the sheet is actively animating between collapsed and open states.

- [x] **Step 1: Add `frozen` property to `SheetCard` in `MainLayout.kt`**

In `class SheetCard`:
```kotlin
/** True while the sheet card is actively traveling (animating open or closed), so underlying backdrops and glass freeze. */
var frozen: Boolean by mutableStateOf(false)
    internal set
```
In `SheetCard.release(token)`:
```kotlin
frozen = false
```

- [x] **Step 2: Connect `SheetCard.frozen` to the `animate` blocks in `NowPlayingScreen.kt`**

In `NowPlayingScreen.kt` opening animation:
```kotlin
settle.offset = Offset(0f, sheetCard.edge * cardHeightPx)
withFrameNanos { }
settle.frozen = true
sheetCard.frozen = true
try {
    animate(...) { value, _ -> ... }
} finally {
    settle.frozen = false
    sheetCard.frozen = false
    sheetCard.markReady()
    landed = true
}
```

In `NowPlayingScreen.kt` closing animation:
```kotlin
settle.frozen = true
sheetCard.frozen = true
try {
    animate(...) { value, _ -> sheetCard.edge = value }
} finally {
    settle.frozen = false
    sheetCard.frozen = false
}
```

- [x] **Step 3: Verify compilation of `SheetCard` and `NowPlayingScreen.kt`**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew compileQaKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 4: Commit Task 2 changes**

```bash
git add app/src/main/java/com/ikko/aura/MainLayout.kt app/src/main/java/com/ikko/aura/ui/screens/nowplaying/NowPlayingScreen.kt
git commit -m "perf(player): expose SheetCard.frozen during sheet card travel"
```

---

### Task 3: Optimize `pillBackdrop` Recording and Wire `LocalGlassSettle` to Pills in `MainLayout.kt`

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/MainLayout.kt:820-840,1070-1135,1260-1280`

**Interfaces:**
- Consumes: `SheetCard.frozen`, `pillBackdrop`
- Produces:
  1. Gated `pageBackdropNeeded`: skips full-screen `NavHost` recording while `sheetCard.frozen == true`.
  2. `pillGlassSettle` provided via `LocalGlassSettle` to `TabCapsule` and `PlayerPillHost`.

- [x] **Step 1: Gate `pageBackdropNeeded` to stop re-recording during sheet motion**

In `MainLayout.kt`:
```kotlin
val playerOpening = currentRoute == AuraDestination.NowPlaying.route && sheetCard.owner == null
val pageBackdropNeeded = (showPill || playerOpening || (sheetCard.owner != null && !cardOverPill)) && !sheetCard.frozen
```

- [x] **Step 2: Create `pillGlassSettle` and provide `LocalGlassSettle` to pills**

In `MainLayout.kt`:
```kotlin
val pillGlassSettle = remember { GlassSettle() }
LaunchedEffect(sheetCard.frozen) {
    pillGlassSettle.frozen = sheetCard.frozen
}
```
Wrap `TabCapsule` and `PlayerPillHost` in `CompositionLocalProvider(LocalGlassSettle provides pillGlassSettle)`:
```kotlin
CompositionLocalProvider(LocalGlassSettle provides pillGlassSettle) {
    TabCapsule(...)
    if (pillComposed) {
        PlayerPillHost(...)
    }
}
```

- [x] **Step 3: Add early return on `alpha <= 0f` in `TabCapsule` graphicsLayer**

In `MainLayout.kt` lines 1084-1102:
```kotlin
val targetAlpha = searchClear(searchPull.value, !searchOpen) * when { ... }
if (targetAlpha <= 0f) {
    alpha = 0f
    return@graphicsLayer
}
alpha = targetAlpha
```

- [x] **Step 4: Verify compilation of `MainLayout.kt`**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew compileQaKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 5: Commit Task 3 changes**

```bash
git add app/src/main/java/com/ikko/aura/MainLayout.kt
git commit -m "perf(pills): freeze pill glass and avoid full-screen backdrop re-recording during sheet travel"
```

---

### Task 4: Empirical Benchmark and Verification on Physical Device

**Files:**
- Test verification via `dumpsys gfxinfo` and video capture on `192.168.50.130:5555`.

- [x] **Step 1: Build QA APK and install on device**

Run:
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleQa
cp app/build/outputs/apk/qa/app-qa.apk /Users/doriansemlali/Desktop/Aura_QA.apk
adb install -r app/build/outputs/apk/qa/app-qa.apk
adb shell am start -n com.ikko.aura.qa/com.ikko.aura.MainActivity
```

- [x] **Step 2: Measure player opening framestats**

Benchmark open transition via `adb shell dumpsys gfxinfo com.ikko.aura.qa reset` + tap + dump:
Target:
- Janky frames < 5% (down from 12%-48%)
- 90th percentile < 17ms (down from 30ms)
- 95th percentile < 18ms (down from 44ms)
- 99th percentile < 22ms (down from 77ms)
- Slow issue draw commands == 0 (down from 8-16)

- [x] **Step 3: Measure player closing framestats**

Benchmark close transition via swipe down + dump:
Target:
- Janky frames < 5% (down from 12%-20%)
- 90th percentile < 17ms (down from 32ms)
- 95th percentile < 18ms (down from 34ms)
- 99th percentile < 22ms (down from 65ms)
- Slow issue draw commands == 0 (down from 12-73)

- [x] **Step 4: Verify visual design preservation**

Capture screenshot of resting library with mini player, search button, and tab capsule to confirm 100% authentic liquid glass look (blur, refraction, highlights, border) is intact.
