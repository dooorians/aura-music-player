package com.ikko.aura.reco

/**
 * Genres as the app compares them: variants written differently become one ("Hip Hop", "Hip-Hop", "Rap/Hip-Hop"), the numbers
 * of ID3v1 become their names ("(17)" is Rock), and what is no genre at all ("Other", "Unknown", a year) is dropped.
 * Only the app's model is touched: no file is written.
 */
object GenreNormalizer {

    private val ID3V1 = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies",
        "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks",
        "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental",
        "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise", "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative",
        "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk",
        "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle",
        "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk",
        "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )

    private val JUNK = setOf(
        "", "unknown", "other", "misc", "miscellaneous", "various", "genre", "none", "null", "undefined", "unclassified",
        "music", "audio", "<unknown>", "sound clip", "pranks", "unkown", "n a", "na", "genre inconnu", "inconnu", "autre",
    )

    /** Spelling variants, keyed by the name with everything but letters and digits removed. */
    private val CANONICAL = mapOf(
        "hiphop" to "Hip-Hop", "rapfhiphop" to "Hip-Hop", "hiphoprap" to "Hip-Hop", "rap" to "Hip-Hop", "raphiphop" to "Hip-Hop",
        "rnb" to "R&B", "randb" to "R&B", "rhythmandblues" to "R&B", "rnbsoul" to "R&B", "contemporaryrnb" to "R&B",
        "kpop" to "K-Pop", "koreanpop" to "K-Pop", "jpop" to "J-Pop", "japanesepop" to "J-Pop", "cpop" to "C-Pop",
        "electronica" to "Electronic", "electronic" to "Electronic", "electro" to "Electronic", "edm" to "Electronic", "dance" to "Dance",
        "alternativerock" to "Alternative Rock", "altrock" to "Alternative Rock", "alternative" to "Alternative", "indie" to "Indie",
        "indiepop" to "Indie Pop", "indierock" to "Indie Rock", "synthpop" to "Synthpop", "electropop" to "Electropop",
        "dancepop" to "Dance-Pop", "dreampop" to "Dream Pop", "poprock" to "Pop Rock", "popfunk" to "Pop", "popfolk" to "Pop",
        "chansonfrancaise" to "Chanson française", "variétéfrançaise" to "Chanson française", "varietefrancaise" to "Chanson française",
        "frenchpop" to "French Pop", "variete" to "Variété", "ostsoundtrack" to "Soundtrack", "ost" to "Soundtrack",
        "bandeoriginale" to "Soundtrack", "filmscore" to "Soundtrack", "soundtrack" to "Soundtrack", "score" to "Soundtrack",
        "lofi" to "Lo-Fi", "lofihiphop" to "Lo-Fi", "trip" to "Trip-Hop", "triphop" to "Trip-Hop", "rockroll" to "Rock & Roll",
        "rocknroll" to "Rock & Roll", "rockandroll" to "Rock & Roll", "hardrock" to "Hard Rock", "classicrock" to "Classic Rock",
        "heavymetal" to "Metal", "metal" to "Metal", "newwave" to "New Wave", "worldmusic" to "World", "world" to "World",
    )

    /** Too wide to say a style: kept as a genre, but ignored when two songs are compared by style. */
    val GENERIC = setOf("pop", "rock", "music", "alternative", "electronic", "indie", "dance", "world")

    /** The genre as the app names it, or null when [raw] is no genre. */
    fun normalize(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        // A year, or a decade, is not a genre.
        if (Regex("^(?:19|20)\\d{2}s?$|^\\d{2}s$").matches(s)) return null
        // ID3v1: "(17)", "17", "(17)Rock".
        Regex("^\\(?(\\d{1,3})\\)?\\s*(.*)$").matchEntire(s)?.let { m ->
            val number = m.groupValues[1].toIntOrNull()
            val rest = m.groupValues[2].trim()
            if (rest.isNotEmpty()) s = rest
            else if (number != null) s = ID3V1.getOrNull(number) ?: return null
        }
        // "Pop; Rock" and "Rock/Pop" name several: the first is the one that is kept.
        s = s.split(';', '\u0000', '|').first().trim()
        val plain = s.lowercase()
        if (plain in JUNK) return null
        val key = java.text.Normalizer.normalize(plain, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), "")
        if (key in JUNK) return null
        CANONICAL[key]?.let { return it }
        // "Rap/Hip-Hop": a slash joins variants of one thing as often as two genres; each side is looked up.
        if ('/' in s) s.split('/').map { it.trim() }.firstNotNullOfOrNull { normalize(it) }?.let { return it }
        return s.split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
    }

    fun isGeneric(genre: String): Boolean = genre.lowercase() in GENERIC
}
