package com.ikko.aura.source.jiosaavn

import com.ikko.aura.source.ytm.YtmLibrary

/**
 * The album id a JioSaavn song carries in the app: its album's when JioSaavn says which, the song's own otherwise.
 *
 * Every JioSaavn song used to carry 0, and covers, colours and the notification's art are cached per album id — so every
 * one of them showed the first one's cover. Negative like every streamed album's, so it can never be a MediaStore id,
 * and never 0 ("no album").
 */
internal fun jioSaavnAlbumId(albumId: String, songId: String): Long =
    YtmLibrary.pseudoAlbumId(if (albumId.isNotBlank()) "jiosaavn-album:$albumId" else "jiosaavn:$songId")
