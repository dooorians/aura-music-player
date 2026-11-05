package com.ikko.aura.util

/**
 * Starting a service, or putting one in the foreground, is refused by the system in situations the app does not
 * control, and the refusal is an exception that takes the whole process down if nobody catches it:
 *
 * - Android 12+: `ForegroundServiceStartNotAllowedException` when the app is in the background (a download queue
 *   resumed by a view model created while the activity was going away, a resume posted after the screen went off);
 * - Android 8+: `BackgroundServiceStartNotAllowedException` / `IllegalStateException` for a plain `startService`
 *   from the background;
 * - Android 14+: `SecurityException` when the foreground-service type's prerequisites are not met.
 *
 * All three are answered the same way: the start did not happen, and the caller is told so it can fall back.
 */
object ForegroundStart {

    /** Runs [start] and says whether it went through. Never throws what the system throws for a refused start. */
    inline fun attempt(start: () -> Unit): Boolean =
        try {
            start()
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException and BackgroundServiceStartNotAllowedException both extend it.
            false
        } catch (e: SecurityException) {
            false
        }
}
