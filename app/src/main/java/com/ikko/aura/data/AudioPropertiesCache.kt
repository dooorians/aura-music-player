package com.ikko.aura.data

import com.ikko.aura.data.model.AudioFormat
import java.io.File

/**
 * What the library scan measured of each file's audio stream, kept between launches.
 *
 * Measuring means opening the file with a MediaExtractor, and the scan used to do that for every
 * track in the library on every cold start: 437 files, measured on the device as ~43% of a core for
 * ~16 seconds after each launch, re-emitting the whole library twice a second while it went. The
 * answer only changes when the file does, so each entry is stamped with the file's modification
 * date and size from MediaStore, and only a file whose stamp has moved (or that is new) is opened
 * again.
 *
 * A plain tab-separated file rather than a Room table: it is read whole once per scan and written
 * whole once after, and a table would mean a schema migration for a cache that can always be
 * rebuilt. Anything unreadable is treated as empty, which costs one full scan and nothing else.
 */
internal class AudioPropertiesCache(private val file: File) {

    class Entry(val stamp: String, val properties: TrackAudioProperties)

    fun read(): Map<String, Entry> = runCatching {
        if (!file.exists()) return emptyMap()
        val entries = HashMap<String, Entry>()
        file.bufferedReader().use { reader ->
            if (reader.readLine() != HEADER) return emptyMap()
            reader.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size != FIELDS) return@forEachLine
                val format = runCatching { AudioFormat.valueOf(parts[2]) }.getOrNull() ?: return@forEachLine
                entries[parts[0]] = Entry(
                    stamp = parts[1],
                    properties = TrackAudioProperties(
                        format = format,
                        sampleRateHz = parts[3].toIntOrNull() ?: 0,
                        bitDepth = parts[4].toIntOrNull() ?: 16,
                        bitrateKbps = parts[5].toIntOrNull() ?: 0,
                    ),
                )
            }
        }
        entries
    }.getOrDefault(emptyMap())

    /** Written to a temporary file and renamed over the old one, so a crash mid-write leaves the old cache. */
    fun write(entries: Map<String, Entry>) {
        runCatching {
            val temp = File(file.path + ".tmp")
            temp.bufferedWriter().use { out ->
                out.append(HEADER).append('\n')
                for ((id, entry) in entries) {
                    val p = entry.properties
                    out.append(id).append('\t')
                        .append(entry.stamp).append('\t')
                        .append(p.format.name).append('\t')
                        .append(p.sampleRateHz.toString()).append('\t')
                        .append(p.bitDepth.toString()).append('\t')
                        .append(p.bitrateKbps.toString()).append('\n')
                }
            }
            if (!temp.renameTo(file)) temp.delete()
        }
    }

    private companion object {
        /**
         * Bumped whenever what the extractor measures changes, so older measurements are redone. v3: this
         * version's extractor — a file written by another one (v2) holds different answers for some files.
         */
        const val HEADER = "aura-audio-properties v3"
        const val FIELDS = 6
    }
}

/**
 * The files known at the previous scan whose modification date or size has moved since: re-tagged, re-encoded or
 * replaced in place under the same id. A new file is not in it (nothing was known about it), nor is one that is gone.
 */
internal fun changedSinceLastScan(previous: Map<String, String>, current: Map<String, String>): Set<String> =
    previous.filter { (id, stamp) -> current[id]?.let { it != stamp } == true }.keys
