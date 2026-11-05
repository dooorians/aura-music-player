package com.ikko.aura.source

/**
 * A place tracks come from. It is written into the keys that get stored, so it never contains ':'.
 */
@JvmInline
value class SourceId(val value: String) {
    init {
        require(value.isNotBlank() && ':' !in value) { "bad source id: \"$value\"" }
    }

    companion object {
        /** The device's own files, found through MediaStore. */
        val LOCAL = SourceId("local")
    }
}

/** A track as one source knows it: the source, and that source's own id for it. */
data class SourceTrackRef(val source: SourceId, val itemId: String) {

    /**
     * As a Media3 mediaId. A local track keeps the bare MediaStore id it has always had — playlists, the lyrics
     * cache, the recents and the Automix analyses are all keyed on it, and none of them is rewritten for this —
     * and any other source is written "source:item".
     */
    fun encode(): String = if (source == SourceId.LOCAL) itemId else "${source.value}:$itemId"

    companion object {
        /**
         * The inverse of [encode]. Split at the first ':' only, so an item id may contain one itself; an id with
         * none is a local track, which is every id that exists today.
         */
        fun decode(mediaId: String): SourceTrackRef {
            val colon = mediaId.indexOf(':')
            return if (colon < 0) {
                SourceTrackRef(SourceId.LOCAL, mediaId)
            } else {
                SourceTrackRef(SourceId(mediaId.substring(0, colon)), mediaId.substring(colon + 1))
            }
        }
    }
}

/**
 * What the app stores and hands around for a track. Opaque on purpose: only the resolver turns it into something
 * that can be played, so no screen ever holds, or builds, a URI.
 */
@JvmInline
value class TrackKey(val value: String)
