package com.ikko.aura.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

/** See [Track]'s doc comment — same [Uri]-caused stability-inference issue applies here. */
@Immutable
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val artworkUri: Uri?,
    val trackCount: Int,
    val year: Int,
    /** A streamed album's id on its source, when it is one: its page is the source's, not a local album's. */
    val remoteId: String? = null,
)
