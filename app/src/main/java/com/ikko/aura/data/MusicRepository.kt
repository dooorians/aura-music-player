package com.ikko.aura.data

import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Artist
import com.ikko.aura.data.model.Track
import kotlinx.coroutines.flow.StateFlow

interface MusicRepository {
    val tracks: StateFlow<List<Track>>
    val albums: StateFlow<List<Album>>
    val artists: StateFlow<List<Artist>>
    val isScanning: StateFlow<Boolean>

    suspend fun refresh(forceRescan: Boolean = false)

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
