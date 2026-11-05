package com.ikko.aura.reco.authority

import com.ikko.aura.reco.ArtistIdentityEntity
import com.ikko.aura.reco.TrackMetaDao
import javax.inject.Inject
import javax.inject.Singleton

/** Where what the catalogues said of an artist is kept between launches (unlike [ArtistIdentityStore], which holds only the artists the listener chose). */
interface ArtistResolutionStore {
    suspend fun get(key: String, nowMs: Long): DisambiguatedArtist?
    suspend fun put(key: String, artist: DisambiguatedArtist, nowMs: Long)
}

/** On the device, for [TTL_MS]: a catalogue is asked about an artist a few times a season, not at every launch (MusicBrainz answers one request a second). */
@Singleton
class ArtistResolutionRoom @Inject constructor(private val dao: TrackMetaDao) : ArtistResolutionStore {

    override suspend fun get(key: String, nowMs: Long): DisambiguatedArtist? {
        val row = dao.identity(key) ?: return null
        // Resolved before the scoring was fixed (a namesake could be picked over the best match): asked again.
        if (row.resolvedAt < VALID_SINCE_MS || nowMs - row.resolvedAt > TTL_MS) return null
        return DisambiguatedArtist(
            canonicalKey = com.ikko.aura.reco.ArtistNames.key(row.canonicalName),
            canonicalName = row.canonicalName,
            mbid = row.mbid,
            deezerId = row.deezerId,
            country = row.country,
            primaryTags = row.tags.split('\u0000').filter { it.isNotEmpty() }.toSet(),
            disambiguation = row.disambiguation,
        )
    }

    override suspend fun put(key: String, artist: DisambiguatedArtist, nowMs: Long) {
        // Only what a catalogue really identified: an artist nobody knew is worth asking about again.
        if (artist.deezerId == null && artist.mbid.isNullOrBlank()) return
        dao.putIdentity(
            ArtistIdentityEntity(
                artistKey = key, canonicalName = artist.canonicalName, mbid = artist.mbid, deezerId = artist.deezerId,
                country = artist.country, tags = artist.primaryTags.joinToString("\u0000"), disambiguation = artist.disambiguation,
                resolvedAt = nowMs,
            ),
        )
    }

    companion object {
        const val TTL_MS = 90L * 86_400_000L

        /** 2026-11-05 02:00 (Europe/Brussels): what was kept before is not trusted. */
        const val VALID_SINCE_MS = 1_793_840_400_000L
    }
}
