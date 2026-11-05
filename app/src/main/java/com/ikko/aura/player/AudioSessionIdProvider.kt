package com.ikko.aura.player

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.C
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One audio session id for the app, generated before [PlaybackService] builds its players so both of them can be
 * told to use it: anything attached to the session (a system visualiser, an external effect) then follows whichever
 * player is audible. The equaliser no longer needs it — it is the app's own processor in the audio sink
 * ([com.ikko.aura.core.audio.dsp.DspAudioProcessor]), not `android.media.audiofx`.
 */
@Singleton
class AudioSessionIdProvider @Inject constructor(@ApplicationContext context: Context) {
    val id: Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.generateAudioSessionId()
            ?: C.AUDIO_SESSION_ID_UNSET
}
