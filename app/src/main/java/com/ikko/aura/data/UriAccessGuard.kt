package com.ikko.aura.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
        releaseOrphanedGrants()
    }

    /**
     * Makes sure a picture just chosen is still readable after a restart: its grant is taken (again — taking a grant
     * already held is harmless) and, if the system refuses because the app holds as many grants as it may (128 before
     * Android 11, 512 since), the grants no picture uses any more are given back and it is tried once more.
     *
     * Returns whether the grant is held. When it is not, the picture still shows for this run and the next launch's
     * [pruneLostGrants] drops it.
     */
    suspend fun keep(uri: String): Boolean {
        val parsed = contentUriOrNull(uri) ?: return true
        if (takeRead(parsed)) return true
        releaseOrphanedGrants()
        return takeRead(parsed)
    }

    /**
     * Gives back every persisted grant no stored picture refers to: a profile photo or a playlist cover that was
     * replaced, removed or belonged to a deleted playlist. Nothing ever released them, so they piled up until the
     * system's cap, after which no new picture survived a restart. Directory (tree) grants are left alone: none is
     * taken today, and one taken tomorrow would not be a picture.
     */
    suspend fun releaseOrphanedGrants() {
        val held = runCatching {
            context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        }.getOrNull() ?: return
        // An unread setting is no evidence that a grant is unused: nothing is released then.
        val referenced = runCatching {
            buildSet {
                settingsStore.profileImageUri.first()?.let { add(it.trim()) }
                settingsStore.playlistCustomArtworks.first().values.forEach { add(it.trim()) }
            }
        }.getOrNull() ?: return
        for (orphan in orphanedGrants(held, referenced)) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(orphan),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { Log.w(TAG, "Could not release $orphan", it) }
        }
    }

    private fun takeRead(uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.onFailure { Log.w(TAG, "No persistable grant for $uri", it) }.isSuccess

    private fun contentUriOrNull(uri: String): Uri? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("content://", ignoreCase = true)) return null
        return runCatching { Uri.parse(trimmed) }.getOrNull()
    }

    companion object {
        private const val TAG = "UriAccessGuard"

        /** The held grants that no stored picture refers to, directory grants excepted. */
        fun orphanedGrants(held: Set<String>, referenced: Set<String>): Set<String> =
            held.filterTo(LinkedHashSet()) { it !in referenced && !isTreeGrant(it) }

        /** `content://authority/tree/…`: a whole directory, not a document. */
        private fun isTreeGrant(uri: String): Boolean =
            uri.substringAfter("://", "").substringAfter('/', "").startsWith("tree/")

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
