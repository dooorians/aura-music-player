package com.ikko.aura.catalog

import com.ikko.aura.source.SourceId
import com.ikko.aura.source.SourceTrackRef
import com.ikko.aura.source.TrackBindings
import com.ikko.aura.source.TrackKey
import com.ikko.aura.source.local.LegacyLocalBindings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The catalogue's answer to "which sources hold this track". A key it has never heard of (before the first scan has
 * run, say, on a resume from a cold start) falls back to the legacy reading, where a bare key is its own item.
 */
@Singleton
class CatalogBindings @Inject constructor(private val dao: CatalogDao) : TrackBindings {
    override suspend fun bindingsFor(key: TrackKey): List<SourceTrackRef> {
        val rows = dao.bindingsFor(key.value)
        if (rows.isEmpty()) return LegacyLocalBindings.bindingsFor(key)
        return rows.filter { it.availability == Availability.AVAILABLE.name }
            .map { SourceTrackRef(SourceId(it.sourceId), it.itemId) }
    }
}
