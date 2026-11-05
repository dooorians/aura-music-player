package com.ikko.aura.reco.authority

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/** Where the identity of an artist, once settled, is kept: by the artist's key (see [com.ikko.aura.reco.ArtistNames.key]). */
interface ArtistIdentityStore {
    fun get(key: String): DisambiguatedArtist?
    fun put(key: String, artist: DisambiguatedArtist)
    fun remove(key: String)
}

/**
 * The artists the listener chose, with who they are exactly (which Deezer artist, which MusicBrainz one), settled when they were
 * chosen. Two artists of one name are common ("F5ve", "Yena"): looked up by name every time, they get mixed. Kept on the device,
 * in a file of their own, and never lost with the caches.
 */
@Singleton
class ArtistIdentityPrefs @Inject constructor(@ApplicationContext context: Context) : ArtistIdentityStore {
    private val prefs = context.getSharedPreferences("artist_identities", Context.MODE_PRIVATE)

    override fun get(key: String): DisambiguatedArtist? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            DisambiguatedArtist(
                canonicalKey = o.getString("canonicalKey"),
                canonicalName = o.getString("canonicalName"),
                mbid = o.optString("mbid").takeIf { it.isNotBlank() },
                deezerId = o.optLong("deezerId", 0L).takeIf { it > 0 },
                country = o.optString("country").takeIf { it.isNotBlank() },
                primaryTags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).mapTo(LinkedHashSet()) { a.getString(it) } }.orEmpty(),
                disambiguation = o.optString("disambiguation").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    override fun put(key: String, artist: DisambiguatedArtist) {
        val o = JSONObject()
            .put("canonicalKey", artist.canonicalKey)
            .put("canonicalName", artist.canonicalName)
            .put("mbid", artist.mbid.orEmpty())
            .put("deezerId", artist.deezerId ?: 0L)
            .put("country", artist.country.orEmpty())
            .put("tags", JSONArray(artist.primaryTags.toList()))
            .put("disambiguation", artist.disambiguation.orEmpty())
        prefs.edit().putString(key, o.toString()).apply()
    }

    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
