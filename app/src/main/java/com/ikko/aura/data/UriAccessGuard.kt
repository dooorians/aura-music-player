package com.ikko.aura.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Forgets the pictures the app can no longer open.
 *
 * The profile photo and the playlists' own covers are documents picked through the Storage Access Framework, kept
 * by address with a persistable grant. That grant goes away on its own: the file is deleted or moved, the app that
 * provided it is uninstalled or cleared, or `takePersistableUriPermission` was refused in the first place. The address
 * stayed in the settings anyway, and every screen showing it went back to the provider for a picture that could only
 * answer with a SecurityException or a FileNotFoundException. Checked once per launch against the grants the system
 * still holds, a lost address is dropped: the screens show their default picture and a new one can be chosen.
 */
@Singleton
class UriAccessGuard @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
) {

    suspend fun pruneLostGrants() {
        // No answer from the system is no evidence that anything was lost: nothing is dropped then.
        val granted = runCatching {
            context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri.toString() }
                .toSet()
        }.getOrNull() ?: return

        runCatching {
            val profile = settingsStore.profileImageUri.first()
            if (profile != null && !isStillReadable(profile, granted)) settingsStore.setProfileImageUri(null)
        }
        runCatching {
            settingsStore.playlistCustomArtworks.first().forEach { (playlistId, uri) ->
                if (!isStillReadable(uri, granted)) settingsStore.setPlaylistCustomArtwork(playlistId, null)
            }
        }
    }

    companion object {
        /**
         * Whether a stored picture can still be opened after a restart. A `content://` document is readable only
         * through a persisted grant; a blank address is nothing to open; anything else (a file of the app's own, a
         * web address, a resource) does not depend on a grant and is left alone.
         */
        fun isStillReadable(uri: String, persistedReadGrants: Set<String>): Boolean {
            val trimmed = uri.trim()
            if (trimmed.isEmpty()) return false
            if (!trimmed.startsWith("content://", ignoreCase = true)) return true
            return trimmed in persistedReadGrants
        }
    }
}
