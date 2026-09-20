package com.ikko.aura.player.smart

import kotlinx.coroutines.flow.MutableStateFlow

/** CPU budget for Automix's background analysis, not its audible mix algorithm. */
enum class AutomixPerformanceMode(val inferenceThreads: Int) {
    EFFICIENT(1),
    BALANCED(2),
    PERFORMANCE(4),
}

/**
 * Where one track stands in Automix's analysis.
 *
 * The three no-result states are kept apart because they call for different reactions:
 * [WAITING] resolves itself once bytes arrive, [ANALYSING] resolves itself in a few seconds,
 * and [FAILED] never resolves at all. From outside they look identical, which is precisely
 * why the state has to say which.
 */
enum class TrackAnalysisState {
    WAITING,
    ANALYSING,
    ANALYSED,

    /**
     * Measured off the track's opening, with the whole-track pass running now to replace
     * those numbers with better ones. Its own state rather than either neighbour, because it
     * is genuinely both.
     */
    REFINING,
    FAILED,
}

/** Both sides of the next transition, for the status line. */
data class SmartAnalysis(
    val current: TrackAnalysisState = TrackAnalysisState.WAITING,
    val next: TrackAnalysisState = TrackAnalysisState.WAITING,
)

/** A span of the playing track, in fractions of its duration, the next transition will occupy. */
data class TransitionWindow(val start: Float, val end: Float)

/**
 * The settings the Automix engine reads, and the state it publishes back.
 *
 * BitChord's engine reads its app-wide `AppSettings` singleton directly, from a service, a
 * player and three DSP classes. Rather than thread a dependency through all of them, this is
 * the same shape: a plain object of [MutableStateFlow]s, mirrored from Aura's DataStore by
 * [com.ikko.aura.player.PlaybackService] on startup.
 *
 * The engine reads `.value` on the audio thread's neighbours, so these are deliberately
 * flows-with-a-value rather than suspending reads.
 */
object AutomixSettings {

    /**
     * Lets Automix's analyzer decide the transition's timing and length from each track's
     * tempo, energy and structure. Off by default: analysis costs a background decode and two
     * ONNX inferences per track.
     */
    val smartFadeEnabled = MutableStateFlow(false)

    /**
     * A plain, fixed-length crossfade, independent of Automix. Zero — off — because Aura has no
     * setting for it and never had one: with Automix off, tracks follow each other exactly as
     * they did before it existed. Automix does not need this above zero; the analysis sizes its
     * own overlap, and this only stands in as a fallback while a pair is still being measured.
     */
    val crossfadeSeconds = MutableStateFlow(0)

    /** Aura has no speed control; the engine multiplies by it, so it stays at 1. */
    val playbackSpeed = MutableStateFlow(1f)

    /** The CPU budget used by Beat This! and the vocal model. */
    val automixPerformanceMode = MutableStateFlow(AutomixPerformanceMode.BALANCED)

    /**
     * True only while a transition is running that a plain crossfade could not have produced —
     * one that beat-matched, cued the incoming track into its arrangement, or rode a filter.
     * Deliberately not "a crossfade is running".
     */
    val smartMixInProgress = MutableStateFlow(false)

    /** How much of the *upcoming* transition has been analysed. */
    val smartAnalysis = MutableStateFlow(SmartAnalysis())

    /** Where on the playing track the next transition is planned, or null when not worth drawing. */
    val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)
}
