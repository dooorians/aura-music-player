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
        // Synonyms Last.fm and the taggers write in many ways.
        "dnb" to "Drum & Bass", "drumnbass" to "Drum & Bass", "drumandbass" to "Drum & Bass", "dandb" to "Drum & Bass",
        "rapfrancais" to "French Rap", "frenchrap" to "French Rap", "rapfr" to "French Rap", "frenchhiphop" to "French Rap",
        "afrobeat" to "Afrobeats", "afrobeats" to "Afrobeats", "afropop" to "Afrobeats", "afroswing" to "Afrobeats",
        "neosoul" to "Neo-Soul", "postpunk" to "Post-Punk", "posthardcore" to "Post-Hardcore", "postrock" to "Post-Rock",
        "synthwave" to "Synthwave", "retrowave" to "Synthwave", "outrun" to "Synthwave", "chillhop" to "Lo-Fi", "chillwave" to "Chillwave",
        "deephouse" to "Deep House", "techhouse" to "Tech House", "progressivehouse" to "Progressive House", "trap" to "Trap", "traprap" to "Trap",
        "reggaeton" to "Reggaeton", "musicaurbana" to "Reggaeton", "singersongwriter" to "Singer-Songwriter",
        "bedroompop" to "Bedroom Pop", "altpop" to "Alternative Pop", "alternativepop" to "Alternative Pop", "altrnb" to "Alternative R&B",
        "alternativernb" to "Alternative R&B", "kindie" to "K-Indie", "khiphop" to "K-Hip-Hop", "krnb" to "K-R&B", "jrock" to "J-Rock",
    )

    private val YEAR = Regex("^(?:19|20)\\d{2}s?$|^\\d{2}s$")
    private val ID3_NUMBER = Regex("^\\(?(\\d{1,3})\\)?\\s*(.*)$")
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val SPACES = Regex("\\s+")

    /** Too wide to say a style: kept as a genre, but ignored when two songs are compared by style. */
    val GENERIC = setOf("pop", "rock", "music", "alternative", "electronic", "indie", "dance", "world")

    /** The genre as the app names it, or null when [raw] is no genre. */
    fun normalize(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        // A year, or a decade, is not a genre.
        if (YEAR.matches(s)) return null
        // ID3v1: "(17)", "17", "(17)Rock".
        ID3_NUMBER.matchEntire(s)?.let { m ->
            val number = m.groupValues[1].toIntOrNull()
            val rest = m.groupValues[2].trim()
            if (rest.isNotEmpty()) s = rest
            else if (number != null) s = ID3V1.getOrNull(number) ?: return null
        }
        // "Pop; Rock" and "Rock/Pop" name several: the first is the one that is kept.
        s = s.split(';', '\u0000', '|').first().trim()
        val plain = s.lowercase()
        if (plain in JUNK) return null
        val key = java.text.Normalizer.normalize(plain, java.text.Normalizer.Form.NFD).replace(MARKS, "").replace(NON_ALNUM, "")
        if (key in JUNK) return null
        CANONICAL[key]?.let { return it }
        // "Rap/Hip-Hop": a slash joins variants of one thing as often as two genres; each side is looked up.
        if ('/' in s) s.split('/').map { it.trim() }.firstNotNullOfOrNull { normalize(it) }?.let { return it }
        return s.split(SPACES).joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
    }

    fun isGeneric(genre: String): Boolean = genre.lowercase() in GENERIC

    /** The broad family of a genre, by the words of its name; the first that fits (the order is from the most specific to the widest). */
    private val FAMILIES = listOf(
        "metal" to listOf("metal", "metalcore", "deathcore"),
        "punk" to listOf("punk", "emo", "grunge", "hardcore", "ska"),
        "hip-hop" to listOf("hip-hop", "hip hop", "rap", "trap", "drill", "grime", "boom bap"),
        "rnb" to listOf("r&b", "rnb", "soul", "funk", "motown", "neo-soul", "disco"),
        "electronic" to listOf("electro", "house", "techno", "trance", "dubstep", "drum and bass", "drum & bass", "idm", "ambient", "downtempo", "trip-hop", "edm", "dance", "synthwave", "garage", "breakbeat", "lo-fi"),
        "jazz" to listOf("jazz", "bossa", "swing", "bebop"),
        "blues" to listOf("blues", "gospel"),
        "classical" to listOf("classical", "orchestral", "opera", "baroque", "piano", "symphon"),
        "folk" to listOf("folk", "country", "bluegrass", "americana", "singer-songwriter", "acoustic"),
        "reggae" to listOf("reggae", "dub", "dancehall"),
        "latin" to listOf("latin", "salsa", "reggaeton", "bachata", "cumbia", "flamenco", "samba"),
        "afro" to listOf("afrobeat", "afropop", "afroswing", "amapiano", "highlife"),
        "chanson" to listOf("chanson", "variété", "variete", "french"),
        "soundtrack" to listOf("soundtrack", "score", "ost"),
        "pop" to listOf("pop", "k-pop", "j-pop", "c-pop", "dream"),
        "rock" to listOf("rock", "shoegaze", "britpop", "new wave", "post-", "alternative", "indie", "psychedelic", "garage rock", "grunge"),
    )

    /** Families that sit next to each other: close enough for a quarter of a match. */
    private val NEIGHBOURS = setOf(
        "rock" to "pop", "rock" to "punk", "rock" to "metal", "rock" to "folk", "rock" to "blues", "punk" to "metal",
        "pop" to "electronic", "pop" to "rnb", "pop" to "chanson", "pop" to "latin", "hip-hop" to "rnb", "electronic" to "hip-hop",
        "jazz" to "blues", "jazz" to "rnb", "jazz" to "classical", "folk" to "blues", "reggae" to "hip-hop", "soundtrack" to "classical",
        "electronic" to "soundtrack", "afro" to "pop", "afro" to "rnb", "afro" to "hip-hop", "afro" to "latin", "afro" to "reggae",
    )

    /** Families by genre, remembered: [family] scans every word of every family, and the same few hundred genres are asked about all day. */
    private val familyMemo = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val NO_FAMILY = "\u0000"
    private const val FAMILY_MEMO_MAX = 20_000

    fun family(genre: String): String? {
        familyMemo[genre]?.let { return if (it == NO_FAMILY) null else it }
        val g = genre.lowercase()
        val found = FAMILIES.firstOrNull { (_, words) -> words.any { g.contains(it) } }?.first
        if (familyMemo.size > FAMILY_MEMO_MAX) familyMemo.clear()
        familyMemo[genre] = found ?: NO_FAMILY
        return found
    }

    /** How alike two genres are: 1 for the same, 0.5 within a family ("Dream Pop", "Synthpop"), 0.25 between neighbouring families, else 0. */
    fun similarity(a: String, b: String): Double {
        if (a.equals(b, ignoreCase = true)) return 1.0
        val fa = family(a) ?: return 0.0
        val fb = family(b) ?: return 0.0
        return when {
            fa == fb -> 0.5
            (fa to fb) in NEIGHBOURS || (fb to fa) in NEIGHBOURS -> 0.25
            else -> 0.0
        }
    }

    /** A Jaccard that gives partial credit to genres of the same family: 1 for identical sets, 0 for sets with nothing in common. */
    fun softJaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val fromA = a.sumOf { x -> b.maxOf { y -> similarity(x, y) } }
        val fromB = b.sumOf { y -> a.maxOf { x -> similarity(x, y) } }
        val inter = (fromA + fromB) / 2.0
        return (inter / (a.size + b.size - inter)).coerceIn(0.0, 1.0)
    }
}
