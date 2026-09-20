package com.ikko.aura.player

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.C
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The audio session id needs to exist before [PlaybackService] builds its `ExoPlayer` (so the
 * player can be told to use it) and also needs to reach [com.ikko.aura.viewmodel.EqualizerViewModel]
 * (so `android.media.audiofx.Equalizer`/`BassBoost` can attach to that same session) — two call
 * sites that otherwise wouldn't share a dependency. Generating it once here, independent of where
 * the player itself lives, means both sides just read the same [Int] rather than one having to
 * reach into the other's internals.
 */
@Singleton
class AudioSessionIdProvider @Inject constructor(@ApplicationContext context: Context) {
    val id: Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.generateAudioSessionId()
            ?: C.AUDIO_SESSION_ID_UNSET
}
