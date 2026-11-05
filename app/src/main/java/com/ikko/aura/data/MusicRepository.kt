package com.ikko.aura.data

import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Artist
import com.ikko.aura.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

interface MusicRepository {
    val tracks: StateFlow<List<Track>>
    val albums: StateFlow<List<Album>>
    val artists: StateFlow<List<Artist>>
    val isScanning: StateFlow<Boolean>

    suspend fun refresh(forceRescan: Boolean = false)

    /**
     * The library once the device's first scan has completed, whatever it found. Waiting for [tracks] to be non-empty
     * instead never ends for a library that really is empty (every local file deleted or moved since the last run),
     * and a queue restored or adopted on that wait never came back, its streamed songs included.
     */
    suspend fun awaitScannedTracks(): List<Track> = tracks.first { it.isNotEmpty() }

    /**
     * Drops [trackIds] from the library immediately, without waiting for a rescan.
     *
     * A file the system has just confirmed deleted is gone whatever MediaStore's own index says
     * for the next moment or two, and a rescan fired straight after the confirmation regularly
     * came back with the row still in it — so the track stayed on screen, unplayable. Removing it
     * here is what makes the list honest at once; the rescan that follows only has to agree.
     */
    fun forget(trackIds: Set<String>)
}

/**
 * What the library holds once its first scan has completed: [current] read as soon as [scanned] says so, even if it is
 * empty. A scan that has not completed (no permission yet) is still waited for, so nothing is taken as deleted early.
 */
internal suspend fun <T> awaitLibrary(scanned: Flow<Boolean>, current: () -> List<T>): List<T> {
    scanned.first { it }
    return current()
}
