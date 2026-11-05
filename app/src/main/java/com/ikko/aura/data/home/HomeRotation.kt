package com.ikko.aura.data.home

import com.ikko.aura.reco.ArtistNames
import com.ikko.aura.reco.ReasonCode
import com.ikko.aura.reco.RecoItem
import java.time.Instant
import java.time.ZoneId

/** What the arrangement of Home's rows is made from. All plain data, so the arrangement is tested without a device. */
class RotationContext(
    /** The rows [HomeSections] made: the sources' "Nouvelles sorties", "Plus de…", "similaire à…", and "often". */
    val base: List<HomeSection>,
    /** What is looped now: the albums most listened to in the last weeks, the device's and the sources' together. */
    val loop: List<SectionAlbum>,
    /** The whole history by album, the device's and the sources' together. */
    val listens: List<AlbumListen>,
    /** Albums of the sources found for this listener's taste, without any the library already has. */
    val external: List<SectionAlbum>,
    /** Artist key (see [ArtistNames.key]) → lowercase tags, from what Last.fm says of the sound. */
    val artistTags: Map<String, Set<String>>,
    /** Every recommended song by the playlist it was made for ("chill", "night", "energy"…). */
    val recoItems: Map<String, List<RecoItem>>,
    val nowMs: Long,
    val seed: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Keys (see [ArtistNames.key]) of the artists of the device's own library: the listener knows them. */
    val knownArtists: Set<String> = emptySet(),
    /** Style (a Last.fm tag, lowercase) → how much the listener's artists carry it: what the rows of genres are made from. */
    val tasteTags: Map<String, Double> = emptyMap(),
    /** The albums most listened to in each Deezer genre (by its id): what completes a row of a genre the listener's artists do not fill. */
    val chartAlbums: Map<Int, List<SectionAlbum>> = emptyMap(),
    /** Albums of the device's own library whose analysed songs are slow (under [HomeRotation.SLOW_BPM] BPM): what "Au ralenti" shows. */
    val slowAlbums: List<SectionAlbum> = emptyList(),
)

/**
 * The order of Home's rows: a fixed head (what has just come out), then 9 to 11 rows drawn from a wide
 * online pool across four families of rows (artists, recommendations, styles, and time/rediscovery), and "En boucle" last, always. The draw depends on
 * [RotationContext.seed] only, so it does not move while the data changes under it; a new seed (a launch, an explicit refresh)
 * draws another set.
 *
 * What is the library's own appears only in "En boucle" (which counts the sources' albums as much as the device's); every
 * other row is made of what the sources offer online for the listener's taste. A row is never offered without a full line of
 * three albums, and two variants of one theme never appear together.
 */
object HomeRotation {
    /** Home has at least this many rows, "En boucle" and what has just come out included. */
    const val MIN_ROWS = 12
    const val MIN_DYNAMIC = 10
    const val MAX_DYNAMIC = 13

    /** Styles the listener does not want in Home, whoever carries them (a Last.fm tag holding one of these words). */
    private val UNWANTED_STYLES = listOf("jazz")

    /** Whether an artist's tags hold a style the listener does not want. */
    fun isUnwantedStyle(tags: Collection<String>): Boolean = tags.any { t -> UNWANTED_STYLES.any { it in t.lowercase() } }
    private const val ROW = HomeSections.PER_ROW
    private const val FAVOR = Long.MAX_VALUE / 4
    private const val MAX_OWN_GENRES = 10

    /** [kind] is what the row is about (an artist, a style, a time, a recommendation): the draw takes one of each before any second. */
    private class Candidate(val id: String, val group: String, val kind: String, val favored: Boolean = false, val build: (Set<String>) -> HomeSection?)

    fun arrange(c: RotationContext): List<HomeSection> {
        val used = HashSet<String>()
        val out = ArrayList<HomeSection>()
        fun take(section: HomeSection?) {
            if (section == null) return
            section.albums.forEach { used += it.key; rowArtists(it).forEach { k -> used += MARK + k } }
            out += section
        }

        // --- the fixed head, and the loop set aside for the end ---
        take(c.base.firstOrNull { it.id == "new" }?.let { freshOnline(it, used) })
        // "En boucle" is the last row, always. Its albums are set aside now all the same, so that no row above shows them again.
        val loop = row("loop", "En boucle", c.loop, used, c.seed, "loop", shuffle = false, kicker = "Tu y reviens")
            ?: c.base.firstOrNull { it.id == "often" }?.let { fresh(it.copy(id = "loop", kicker = "Tu y reviens", title = "En boucle"), used) }
        loop?.albums?.forEach { used += it.key; rowArtists(it).forEach { k -> used += MARK + k } }

        // --- the draw: one row of each kind before a second of any, so that the Home is varied ---
        // At least MIN_ROWS in all: the head and the loop count, and when the draw is short it asks for more.
        val fixed = out.size + (if (loop != null) 1 else 0)
        val target = maxOf(MIN_DYNAMIC + (hash(c.seed, "count") % (MAX_DYNAMIC - MIN_DYNAMIC + 1)).toInt(), MIN_ROWS - fixed)
        val groups = HashSet<String>()
        // What the listener's own taste carries comes first within its kind; the rest follows, in the seed's order.
        val all = candidates(c).sortedBy { hash(c.seed, it.id) - if (it.favored) FAVOR else 0L }
        val kinds = all.map { it.kind }.distinct().sortedBy { hash(c.seed, "kind$it") }
        var chosen = 0
        for (round in 0 until target) {
            var addedInRound = false
            for (kind in kinds) {
                if (chosen >= target) break
                for (cand in all) {
                    if (cand.kind != kind || cand.group in groups) continue
                    val section = cand.build(used) ?: continue
                    if (section.albums.size < ROW) continue
                    groups += cand.group
                    take(section)
                    chosen++
                    addedInRound = true
                    break
                }
            }
            if (chosen >= target || !addedInRound) break
        }
        loop?.let { out += it }
        return out
    }

    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Everything that fits the row [rowId] in the arrangement of [c], not only what it showed or kept in reserve: its theme asked again from the
     * whole pool, with nothing yet shown elsewhere set aside. What takes the place of an album that is refused or of an artist that is blocked
     * is drawn from here when the reserve has run out. Empty for a row that has no theme to ask again (the head, "En boucle").
     */
    fun pool(rowId: String, c: RotationContext): List<SectionAlbum> {
        val candidates = candidates(c)
        val wanted = when {
            rowId.startsWith("artist:") && !rowId.startsWith("artist:ext:") -> listOf(rowId, "artist:ext:" + rowId.removePrefix("artist:"))
            else -> listOf(rowId)
        }
        val section = candidates.firstOrNull { it.id in wanted }?.build?.invoke(emptySet()) ?: return emptyList()
        return (section.albums + section.spare).distinctBy { it.key }
    }

    private fun hash(seed: Long, key: String): Long = ((seed * 31 + key.hashCode()) * 0x9E3779B97F4A7C15uL.toLong()).let { (it xor (it ushr 29)) and Long.MAX_VALUE }

    private const val MARK = "\u0001a:"

    /** The artists a tile stands for: none for a radio or a mood (they mix several), the name for an artist, else its credits. */
    private fun rowArtists(a: SectionAlbum): Set<String> = when {
        a.isArtist -> setOf(ArtistNames.key(a.title))
        a.playsItems -> emptySet()
        else -> creditKeys(a)
    }

    /** Not shown yet, and by no artist that is already shown in any row. */
    private fun free(a: SectionAlbum, used: Set<String>) = a.key !in used && rowArtists(a).none { (MARK + it) in used }

    /** At most one tile per artist in a row (but for the rows that are about one artist), at most a line. */
    private fun distinctRow(albums: List<SectionAlbum>, sameArtist: Boolean): List<SectionAlbum> {
        val seen = HashSet<String>()
        val out = ArrayList<SectionAlbum>()
        for (a in albums) {
            val r = rowArtists(a)
            if (!sameArtist && r.any { it in seen }) continue
            seen += r
            out += a
            if (out.size >= ROW) break
        }
        return out
    }

    /** The section with what is already shown elsewhere (the album, or any album of its artist) removed, kept to one line; null when fewer than a line is left. */
    private fun fresh(s: HomeSection, used: Set<String>, sameArtist: Boolean = false): HomeSection? {
        val all = (s.albums + s.spare).filter { free(it, used) }.distinctBy { it.sameAs }
        val albums = distinctRow(all, sameArtist)
        return if (albums.size >= ROW) s.copy(albums = albums, spare = spareFor(all, used, albums, sameArtist)) else null
    }

    /** Same as [fresh], restricted to online albums of the sources so local files never leak into online discovery rows. */
    private fun freshOnline(s: HomeSection, used: Set<String>, sameArtist: Boolean = false): HomeSection? {
        val all = (s.albums + s.spare).filter { it.album == null && free(it, used) }.distinctBy { it.sameAs }
        val albums = distinctRow(all, sameArtist)
        return if (albums.size >= ROW) s.copy(albums = albums, spare = spareFor(all, used, albums, sameArtist)) else null
    }

    private fun pickRow(source: List<SectionAlbum>, used: Set<String>, seed: Long, key: String, shuffle: Boolean, sameArtist: Boolean = false): List<SectionAlbum> {
        val free = source.filter { free(it, used) }.distinctBy { it.sameAs }
        val ordered = if (shuffle) free.sortedBy { hash(seed, key + it.key) } else free
        return distinctRow(ordered, sameArtist)
    }

    private fun section(id: String, title: String, albums: List<SectionAlbum>, kicker: String? = null, subtitle: String? = null, spare: List<SectionAlbum> = emptyList()): HomeSection? =
        if (albums.size >= ROW) HomeSection(id, kicker, title, albums.take(ROW), subtitle, spare) else null

    /** What a row keeps in reserve: enough that refusing a few albums (or blocking their artists) still leaves what takes their place. */
    private const val SPARE = 12

    /** What fits the row as well as what it shows, one per artist: what takes the place of an album that is refused or of an artist that is blocked. */
    private fun spareFor(source: List<SectionAlbum>, used: Set<String>, picked: List<SectionAlbum>, sameArtist: Boolean): List<SectionAlbum> {
        val shown = picked.mapTo(HashSet()) { it.key }
        val seen = HashSet<String>().also { s -> if (!sameArtist) picked.forEach { s += rowArtists(it) } }
        val out = ArrayList<SectionAlbum>()
        for (a in source) {
            if (!free(a, used) || a.key in shown || out.any { it.sameAs == a.sameAs } || picked.any { it.sameAs == a.sameAs }) continue
            val r = rowArtists(a)
            if (!sameArtist && r.any { it in seen }) continue
            seen += r
            out += a
            if (out.size >= SPARE) break
        }
        return out
    }

    /** A row of one line made from [source] (ordered best first when [shuffle] is off), with a reserve of what else fits it. */
    private fun row(
        id: String, title: String, source: List<SectionAlbum>, used: Set<String>, seed: Long, key: String, shuffle: Boolean,
        sameArtist: Boolean = false, kicker: String? = null, subtitle: String? = null,
    ): HomeSection? {
        val picked = pickRow(source, used, seed, key, shuffle, sameArtist)
        return section(id, title, picked, kicker, subtitle, spareFor(source, used, picked, sameArtist))
    }

    // ------------------------------------------------------------------------------------------------------------------

    /** The scene of a country: its artists are the ones whose tags say where they come from (Last.fm's nationality tags, and the country MusicBrainz gives). */
    private class Scene(val id: String, val title: String, val words: List<String>, val not: List<String> = emptyList(), val group: String = "scene:$id")

    /** Slow means under this many beats a minute. */
    const val SLOW_BPM = 90.0

    private val SCENES = listOf(
        Scene("be", "Scène belge", listOf("belgian", "belgium", "flemish", "belgian pop", "belgian rock", "belgian hip hop", "brussels")),
        Scene("jp", "Scène japonaise", listOf("japanese", "japan", "j-pop", "jpop", "j-rock", "japanese rock", "city pop", "shibuya-kei"), not = listOf("k-pop", "kpop"), group = "tag:jpop"),
        Scene("uk", "Scène britannique", listOf("british", "uk", "britpop", "uk garage", "uk hip hop", "uk drill", "grime", "british indie", "english")),
        Scene("de", "Scène allemande", listOf("german", "germany", "krautrock", "neue deutsche welle", "deutschrock", "german hip hop", "berlin")),
        Scene("se", "Scène suédoise", listOf("swedish", "sweden", "swedish pop", "swedish rock")),
        Scene("ca", "Scène canadienne", listOf("canadian", "canada", "canadian pop", "canadian rock", "canadian hip hop")),
        Scene("au", "Scène australienne", listOf("australian", "australia", "australian pop", "australian rock", "aussie")),
        Scene("br", "Scène brésilienne", listOf("brazilian", "brazil", "mpb", "tropicalia", "bossa nova", "samba", "funk carioca")),
    )

    /** An album of a concert: "Live at…", "Unplugged", "En concert", "(Live)". A song called "Live Forever" is not one. */
    private val LIVE_TITLE = Regex("(?i)(?:[(\\[]\\s*live\\b|\\blive (?:at|in|from|on|à|au|aux|session|sessions|and|\\d{4})\\b|\\blive[!.]?\\s*$|[-–:]\\s*live\\b|unplugged|en concert|in concert|en direct|\\bconcert\\b)")
    fun isLiveAlbum(title: String): Boolean = LIVE_TITLE.containsMatchIn(title)

    private class Family(val id: String, val titles: List<String>, val subtitle: String? = null, val tags: List<String>, val needs: List<String> = emptyList(), val not: List<String> = emptyList(), val allowed: Set<String>? = null)

    private val FAMILIES = listOf(
        Family(
            id = "pop_fr",
            titles = listOf("Pop française"),
            tags = listOf("french pop", "chanson française", "chanson francaise", "variété française", "variete francaise", "chanson", "pop", "synthpop", "electropop", "indie pop"),
            needs = listOf("french", "france", "french pop", "chanson", "chanson française", "chanson francaise", "variété française", "variete francaise", "belgian", "belgium", "francophone"),
            not = listOf("rap", "hip-hop", "hip hop", "french rap", "french hip-hop", "french hip hop", "rap français", "rap francais", "rap fr", "cloud rap", "trap", "metal", "death metal", "black metal", "hardcore", "punk", "house", "techno"),
        ),
        Family(
            id = "fr",
            titles = listOf("Musique francophone"),
            tags = listOf("french", "france", "francophone", "french pop", "chanson", "chanson française", "chanson francaise", "variété française", "variete francaise", "québécois", "quebecois", "french touch", "french electro"),
            not = listOf("death metal", "black metal", "thrash metal", "k-pop", "kpop", "reggaeton"),
        ),
        Family(
            allowed = setOf("hip-hop"),
            id = "rap_fr",
            titles = listOf("Rap francophone"),
            tags = listOf("french rap", "french hip hop", "french hip-hop", "rap français", "rap francais", "rap fr", "rap", "hip-hop", "hip hop", "cloud rap", "trap"),
            needs = listOf("french", "france", "french rap", "french hip hop", "french hip-hop", "rap français", "rap francais", "rap fr", "belgian", "belgium", "francophone"),
            not = listOf("k-pop", "kpop", "latin", "reggaeton", "rock", "metal", "country"),
        ),
        Family(
            allowed = setOf("pop", "hip-hop"),
            id = "kpop",
            titles = listOf("K-pop festive", "Pop coréenne"),
            tags = listOf("k-pop", "kpop", "korean pop"),
            not = listOf("k-indie", "korean indie", "k-hip-hop", "k-rap", "ballad", "ost", "soundtrack", "classical", "metal"),
        ),
        Family(
            id = "hyperpop",
            titles = listOf("Explorez l'hyperpop", "Hyperpop"),
            tags = listOf("hyperpop", "glitchcore", "digicore", "pc music", "bubblegum bass", "dariacore"),
            not = listOf("k-pop", "kpop", "korean", "boy band", "girl group", "reggaeton", "latin", "country", "metal"),
        ),
        Family(
            id = "ballroom",
            titles = listOf("Musique de danse de salon"),
            tags = listOf("ballroom", "tango", "waltz", "foxtrot", "quickstep", "cha-cha", "pasodoble", "viennese waltz", "mambo", "bolero", "swing", "big band"),
            not = listOf("pop", "rnb", "r&b", "rap", "hip-hop", "hip hop", "rock", "indie", "electronic", "edm", "house", "k-pop", "kpop", "reggaeton", "metal"),
        ),
        Family(
            id = "superbloom",
            titles = listOf("Explorez l'univers Superbloom"),
            subtitle = "À la découverte d'une nouvelle génération d'artistes audacieux.",
            tags = listOf("alt-pop", "alt pop", "art pop", "synthpop", "electropop", "indietronica", "bedroom pop", "dream pop"),
            not = listOf("rap", "hip-hop", "hip hop", "k-pop", "kpop", "reggaeton", "latin", "metal", "country", "hard rock"),
        ),
        Family(
            id = "indie",
            titles = listOf("Indé"),
            tags = listOf("indie rock", "indie folk", "shoegaze", "slowcore", "post-punk", "lo-fi", "lofi", "jangle pop", "dream pop", "bedroom pop", "math rock", "chamber pop", "psychedelic pop", "psychedelic rock", "indie pop", "indie", "slacker rock", "midwest emo", "noise pop", "neo-psychedelia"),
            not = listOf("pop", "rnb", "r&b", "soul", "alternative rnb", "alternative r&b", "contemporary r&b", "hip-hop", "hip hop", "rap", "trap", "k-pop", "kpop", "korean", "dance-pop", "dance pop", "dance", "electropop", "synthpop", "electronic", "edm", "house", "hyperpop", "reggaeton", "latin", "latin pop", "boy band", "girl group", "metal", "hard rock"),
        ),
        Family(
            allowed = setOf("rnb"),
            id = "rnb",
            titles = listOf("R&B contemporain", "R&B & Soul"),
            tags = listOf("rnb", "r&b", "contemporary r&b", "alternative rnb", "alternative r&b", "neo-soul", "neo soul", "soul"),
            not = listOf("k-pop", "kpop", "rock", "indie rock", "metal", "punk", "country", "edm", "techno"),
        ),
        Family(
            allowed = setOf("latin", "pop", "hip-hop"),
            id = "latin",
            titles = listOf("Musique latine"),
            tags = listOf("reggaeton", "latin pop", "urbano latino", "trap latino", "latin trap", "bachata", "salsa", "cumbia", "merengue", "dembow", "corridos tumbados", "regional mexicano", "latin"),
            not = listOf("k-pop", "kpop", "french", "french pop", "french rap", "j-pop", "metal", "classical"),
        ),
    )

    // ------------------------------------------------------------------------------------------------------------------
    // The rows of genres. Not listed one by one: the great genres are a catalogue, and the styles the listener's artists carry (a Last.fm
    // tag with a weight) become rows of their own, so that "dream pop" or "city pop" appear without anyone having thought of them.

    private class Genre(val id: String, val title: String, val tags: List<String>, val not: List<String>, val favored: Boolean, val chart: Int? = null, val allowed: Set<String>? = null)

    private class Popular(val id: String, val title: String, val tags: List<String>, val not: List<String> = emptyList(), val chart: Int? = null)

    private val POPULAR = listOf(
        Popular("pop", "Pop", listOf("pop"), listOf("k-pop", "hip-hop", "r&b", "rock", "metal", "latin", "reggaeton", "j-pop", "hyperpop", "country", "jazz"), chart = 132),
        Popular("rock", "Rock", listOf("rock", "classic rock", "hard rock", "alternative rock", "garage rock", "glam rock"), listOf("k-pop", "hip-hop", "r&b", "electronic", "edm", "reggaeton", "latin", "hyperpop", "j-pop"), chart = 152),
        Popular("hiphop", "Hip-hop & rap", listOf("hip-hop", "trap", "cloud rap", "boom bap", "drill", "southern hip hop", "east coast hip hop"), listOf("french hip hop", "french hip-hop", "french rap", "rap français", "rap francais", "k-pop", "k-hip-hop"), chart = 116),
        Popular("electro", "Électro", listOf("electronic", "electronica", "synthwave", "electro", "idm", "future bass", "downtempo"), listOf("k-pop", "hyperpop", "metal"), chart = 106),
        Popular("dance", "House & dance", listOf("house", "deep house", "tech house", "techno", "trance", "drum and bass", "dubstep", "uk garage", "club", "eurodance"), listOf("k-pop", "metal"), chart = 113),
        Popular("funk", "Funk & disco", listOf("funk", "disco", "motown", "boogie", "nu-disco", "northern soul"), listOf("k-pop", "metal"), chart = 169),
        Popular("metal", "Metal", listOf("metal", "heavy metal", "thrash metal", "death metal", "black metal", "metalcore", "nu metal", "progressive metal"), listOf("k-pop"), chart = 464),
        Popular("punk", "Punk & emo", listOf("punk", "pop punk", "emo", "post-hardcore", "hardcore", "skate punk", "punk rock"), listOf("k-pop")),
        Popular("reggae", "Reggae & dancehall", listOf("reggae", "dancehall", "dub", "ska", "roots reggae", "reggae fusion"), listOf("k-pop", "reggaeton"), chart = 144),
        Popular("folk", "Country & folk", listOf("country", "folk", "americana", "bluegrass", "singer-songwriter", "country pop", "indie folk"), listOf("k-pop", "hip-hop"), chart = 466),
        Popular("classical", "Classique", listOf("classical", "orchestral", "neoclassical", "opera", "baroque", "contemporary classical", "piano"), listOf("k-pop", "hip-hop"), chart = 98),
        Popular("blues", "Blues", listOf("blues", "blues rock", "delta blues", "electric blues"), listOf("k-pop"), chart = 153),
        Popular("jpop", "J-pop", listOf("j-pop", "jpop", "japanese pop", "city pop", "j-rock", "vocaloid"), listOf("k-pop")),
        Popular("afro", "Afrobeats", listOf("afrobeats", "afrobeat", "afropop", "amapiano", "afro house", "afroswing"), listOf("k-pop"), chart = 2),
        Popular("chill", "Chill & ambiance", listOf("chillhop", "chillout", "ambient", "trip-hop", "new age", "lounge"), listOf("k-pop", "metal")),
        Popular("world", "Musiques du monde", listOf("world", "worldbeat", "bossa nova", "samba", "mpb", "flamenco", "celtic", "raï", "rai"), listOf("k-pop")),
    )

    /** Tags the rows of themes above already answer to: a style they hold is not made into a second row. */
    // ("pop" is left out: it would swallow every style that ends in it, "chamber pop" as much as the others.)
    private val COVERED: Set<String> by lazy { (FAMILIES.flatMap { it.tags } + POPULAR.flatMap { it.tags }).filter { it != "pop" }.toHashSet() }

    private val YEAR_TAG = Regex("^(?:\\d{2}|\\d{4})s?$")

    private fun displayTag(tag: String) = tag.trim().replaceFirstChar { it.uppercase() }

    /** Every genre row there can be: the great genres, and the listener's own styles (their top tags, as many as are worth a row). */
    private fun genres(c: RotationContext): List<Genre> {
        val taste = c.tasteTags.filterValues { it > 0.0 }.entries.sortedByDescending { it.value }
        val tasteKeys = taste.map { it.key.lowercase() }.toHashSet()
        val out = ArrayList<Genre>()
        for (p in POPULAR) out += Genre(p.id, p.title, p.tags, p.not, favored = p.tags.any { it in tasteKeys }, chart = p.chart, allowed = FAMILIES_OF[p.id])
        for ((raw, _) in taste) {
            val tag = raw.trim().lowercase()
            if (tag.isEmpty() || YEAR_TAG.matches(tag) || com.ikko.aura.reco.GenreNormalizer.isGeneric(tag) || isUnwantedStyle(listOf(tag))) continue
            if (tag in com.ikko.aura.reco.TasteGate.NOISE || tag in com.ikko.aura.reco.TasteGate.GENERIC || tag in com.ikko.aura.reco.TasteGate.REGIONAL) continue
            // Already a row of its own: the same tag, or a sort of one of the great ones ("folk rock" is rock).
            if (tag in COVERED || COVERED.any { cov -> tag.endsWith(" $cov") }) continue
            out += Genre("auto:$tag", displayTag(tag), listOf(tag), emptyList(), favored = true)
            if (out.count { it.id.startsWith("auto:") } >= MAX_OWN_GENRES) break
        }
        return out
    }

    /** How much a tag weighs by its place among an artist's tags (Last.fm lists the most telling first): the first says what the artist is, the fifth hardly. */
    private val RANK_WEIGHT = doubleArrayOf(1.0, 0.85, 0.6, 0.4, 0.25)

    /** Styles almost every artist carries somewhere in their tags: they only count when they are among the first two. */
    private val BROAD = setOf("pop", "rock")

    /**
     * How central one of [wanted] is to an artist whose tags are [tags], most telling first: 1 when it is the first tag, less down the list, and 0
     * when none is central enough or one of [not] is there. "Pop" in the fifth place is not a pop artist: it is what is left of whatever else they are.
     */
    private fun fit(tags: Collection<String>, wanted: List<String>, not: List<String>): Double {
        if (tags.isEmpty() || not.any { it in tags }) return 0.0
        val ordered = tags.toList()
        return wanted.maxOf { w ->
            val i = ordered.indexOf(w)
            val weight = if (i < 0) 0.0 else RANK_WEIGHT.getOrElse(i) { 0.2 }
            if (weight >= (if (w in BROAD) 0.85 else 0.6)) weight else 0.0
        }
    }

    private fun matchesGenre(g: Genre, tags: Set<String>): Boolean = fit(tags, g.tags, g.not) > 0.0

    /** The broad families (see [com.ikko.aura.reco.GenreNormalizer.family]) a great genre's row accepts: what its artists are, whatever the tags a source gave an album. */
    private val FAMILIES_OF = mapOf(
        "pop" to setOf("pop"), "rock" to setOf("rock", "punk", "metal"), "hiphop" to setOf("hip-hop"), "electro" to setOf("electronic"),
        "dance" to setOf("electronic"), "funk" to setOf("rnb"), "metal" to setOf("metal"), "punk" to setOf("punk"), "reggae" to setOf("reggae"),
        "folk" to setOf("folk"), "classical" to setOf("classical", "soundtrack"), "blues" to setOf("blues"), "chill" to setOf("electronic"),
    )

    /** How many of an artist's first tags say what they are; the rest is what is left of whatever else they are. */
    private const val LEADING_TAGS = 3

    /**
     * Whether an artist belongs in a row of [allowed] families: one of their first [LEADING_TAGS] tags is of one of them. An artist with no tags known
     * cannot be placed, so is not: a row of "Rock" with Katy Perry in it (pop, female vocalists, dance first) is worse than a row without her. [unknownOk] is
     * for when no artist has any tag (Last.fm cannot be asked): the genre's chart then stands as Deezer made it, or Home would have no row of a style at all.
     * No restriction for a row that has no family (a style of its own, a country, a mood).
     */
    private fun belongs(allowed: Set<String>?, tags: Set<String>, unknownOk: Boolean = false): Boolean {
        if (allowed == null) return true
        if (tags.isEmpty()) return unknownOk
        return tags.take(LEADING_TAGS).any { com.ikko.aura.reco.GenreNormalizer.family(it) in allowed }
    }

    /** How much of the listener's own taste an album carries: an artist they own or play first, then what the engine found for the artists they play, and the loosest last. */
    private fun tie(a: SectionAlbum, c: RotationContext): Double {
        if (creditKeys(a).any { it in c.knownArtists }) return 1.0
        return a.items.mapNotNull { it.reason?.code }.maxOfOrNull { code ->
            when (code) {
                ReasonCode.LISTENED_ARTIST -> 1.0
                ReasonCode.COLLAB, ReasonCode.LIBRARY_ARTIST, ReasonCode.NEW_FROM_ARTIST -> 0.9
                ReasonCode.RELATED_TO -> 0.8
                ReasonCode.EXPLORATION -> 0.5
                else -> 0.3
            }
        } ?: 0.3
    }

    /** The albums best first: those the style is central to and the listener's taste is close to, then the others, a draw by [key] among equals so that two days do not show the same row. */
    private fun ranked(albums: List<SectionAlbum>, c: RotationContext, key: String, fitOf: (SectionAlbum) -> Double): List<SectionAlbum> =
        albums.sortedWith(
            compareByDescending<SectionAlbum> { ((0.5 * tie(it, c) + 0.5 * fitOf(it)) * 5).toInt() }.thenBy { hash(c.seed, key + it.key) },
        )

    private fun artistKey(artist: String) = ArtistNames.primaryKey(artist)

    private fun tagsOfAlbum(a: SectionAlbum, c: RotationContext): Set<String> =
        c.artistTags[artistKey(a.artist)].orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    private fun matches(f: Family, tags: Set<String>): Boolean {
        if (f.needs.isNotEmpty() && f.needs.none { it in tags }) return false
        return fit(tags, f.tags, f.not) > 0.0
    }

    private fun candidates(c: RotationContext): List<Candidate> {
        val list = ArrayList<Candidate>()
        val known = knownKeys(c)
        val knownSet = known.toHashSet()
        c.base.forEach { s ->
            when {
                s.id.startsWith("artist:") -> {
                    val artKey = artistKey(s.title)
                    if (artKey in knownSet) {
                        list += Candidate(s.id, s.id, "artist") { used -> freshOnline(s, used, sameArtist = true) }
                    }
                }
                s.id.startsWith("similar:") -> list += Candidate(s.id, s.id, "artist") { used -> freshOnline(s, used) }
            }
        }
        // More of an artist the listener already knows (the device's own library, or what they have played): the sources'
        // albums credited to that artist, whether the library's own is of the same name or a collaboration.
        val onlineAlbums = c.external.filter { it.album == null && it.artist.isNotBlank() && !HomeSections.isExcludedAlbum(it.title) }
        val albumsByCredit = HashMap<String, MutableList<SectionAlbum>>()
        for (a in onlineAlbums) {
            for (cred in creditKeys(a)) {
                albumsByCredit.getOrPut(cred) { ArrayList() }.add(a)
            }
        }
        for (k in known) {
            if (isUnwantedStyle(c.artistTags[k].orEmpty())) continue
            val albums = albumsByCredit[k] ?: continue
            val distinct = albums.filter { !HomeSections.isExcludedAlbum(it.title) }.distinctBy { it.sameAs }
            if (distinct.size < ROW) continue
            val name = displayName(k, distinct)
            val id = "artist:ext:$name"
            list += Candidate(id, id, "artist") { used ->
                row("artist:$name", name, distinct, used, c.seed, id, shuffle = true, sameArtist = true, kicker = "Plus de")
            }
        }
        list += Candidate("fav_artists", "fav_artists", "artist") { used -> favoriteArtistsOnline(c, used, albumsByCredit) }
        // The scenes of the countries: the artists whose tags say where they come from, wherever in their tags it is said.
        for (s in SCENES) {
            list += Candidate("scene:${s.id}", s.group, "scene") { used ->
                val albums = c.external.filter { a ->
                    a.album == null && tagsOfAlbum(a, c).let { tags -> tags.any { it in s.words } && s.not.none { it in tags } }
                }
                row("scene:${s.id}", s.title, ranked(albums, c, "scene:${s.id}") { 1.0 }, used, c.seed, "scene:${s.id}", shuffle = false)
            }
        }
        // The concerts of the artists the listener knows.
        list += Candidate("live", "live", "live") { used ->
            val albums = c.external.filter { a -> a.album == null && isLiveAlbum(a.title) && creditKeys(a).any { it in c.knownArtists } }
            row("live", "En concert", ranked(albums, c, "live") { 1.0 }, used, c.seed, "live", shuffle = false, kicker = "Sur scène")
        }
        // The slow albums of the library, by the tempo Automix measured (the sources give none).
        list += Candidate("tempo:slow", "tempo:slow", "tempo") { used ->
            row("tempo:slow", "Au ralenti", c.slowAlbums, used, c.seed, "tempo:slow", shuffle = true, kicker = "Moins de ${SLOW_BPM.toInt()} BPM")
        }
        list += Candidate("radios", "radios", "reco") { used -> radios(c, used) }
        // The themes: albums of the sources whose artist's sound the tags place in them.
        for (f in FAMILIES) {
            list += Candidate("family:${f.id}", "family:${f.id}", "style") { used ->
                val albums = c.external.filter { it.album == null && tagsOfAlbum(it, c).let { tags -> matches(f, tags) && belongs(f.allowed, tags) } }
                val title = f.titles[(hash(c.seed, f.id) % f.titles.size).toInt()]
                row(
                    "family:${f.id}", title, ranked(albums, c, f.id) { fit(tagsOfAlbum(it, c), f.tags, f.not) }, used, c.seed, f.id,
                    shuffle = false, subtitle = f.subtitle,
                )
            }
        }
        // The rows of genres: the great ones, and the styles that the listener's own artists carry, all made by the tags.
        for (g in genres(c)) {
            list += Candidate("tag:${g.id}", "tag:${g.id}", "genre", favored = g.favored) { used ->
                val tagged = c.external.filter { it.album == null && tagsOfAlbum(it, c).let { tags -> matchesGenre(g, tags) && belongs(g.allowed, tags) } }
                // The albums of the listener's own artists in that style first; the genre's chart makes up the row when there are too few.
                // The genre's chart is Deezer's: its albums are of that genre by Deezer's say, which puts a pop record in the rock chart. Only the artists whose own tags agree stay.
                val charted = g.chart?.let { c.chartAlbums[it] }.orEmpty().filter { belongs(g.allowed, tagsOfAlbum(it, c), unknownOk = c.artistTags.isEmpty()) }
                val own = ranked(tagged, c, "tag:${g.id}") { fit(tagsOfAlbum(it, c), g.tags, g.not) }
                val chart = charted.sortedBy { hash(c.seed, "tag:${g.id}" + it.key) }
                row("tag:${g.id}", g.title, own + chart, used, c.seed, "tag:${g.id}", shuffle = false)
            }
        }
        // The moods, from what the engine found of the listener's own artists: calm, night, energy, whatever the hour.
        for ((kind, title) in listOf("chill" to "Pour se détendre", "night" to "Pour la nuit", "energy" to "Pour bouger")) {
            list += Candidate("mood:$kind", "mood:$kind", "time") { used ->
                val found = HomeSections.externalAlbums(mapOf(kind to c.recoItems[kind].orEmpty())).filter { it.album == null }
                row("mood:$kind", title, found, used, c.seed, "mood$kind", shuffle = true)
            }
        }
        // Time and rediscovery rows, strictly from online sources.
        list += Candidate("decade", "decade", "time") { used -> decade(c, used) }
        list += Candidate("tastemix", "tastemix", "reco") { used -> tasteMix(c, used) }
        list += Candidate("routine", "routine", "time") { used -> routine(c, used) }
        return list
    }

    private fun knownKeys(c: RotationContext): List<String> =
        (c.listens.map { artistKey(it.album.artist) } + c.knownArtists).filter { it.isNotEmpty() }.distinct()

    /** Every artist credited on an album, as keys: its primary one and the ones of a collaboration. */
    private fun creditKeys(a: SectionAlbum): Set<String> =
        (ArtistNames.split(a.artist) + artistKey(a.artist)).filter { it.isNotEmpty() }.toSet()

    private fun displayName(key: String, albums: List<SectionAlbum>): String {
        val own = albums.firstOrNull { artistKey(it.artist) == key } ?: albums.first()
        return ArtistNames.primary(own.artist)
    }

    /** Artists (not albums) the listener plays most or owns, each one with what the sources offer by them. */
    private fun favoriteArtistsOnline(c: RotationContext, used: Set<String>, albumsByCredit: Map<String, List<SectionAlbum>>): HomeSection? {
        val played = c.listens.groupBy { artistKey(it.album.artist) }
            .entries.sortedByDescending { e -> e.value.sumOf { it.count } }.map { it.key }
        val ranked = (played + c.knownArtists.sortedBy { hash(c.seed, "fa$it") }).filter { it.isNotEmpty() }.distinct()
        val tiles = ArrayList<SectionAlbum>()
        for (k in ranked) {
            val albums = albumsByCredit[k] ?: continue
            if (albums.isEmpty()) continue
            val cover = albums.firstOrNull { it.artworkUrl.isNotBlank() } ?: continue
            val songs = albums.flatMap { it.items }.distinctBy { it.trackId }.take(40)
            val tile = SectionAlbum(
                title = displayName(k, albums), artist = "Artiste", artworkUrl = cover.artworkUrl,
                items = songs, isArtist = true,
            )
            if (!free(tile, used) || tiles.any { it.title == tile.title }) continue
            tiles += tile
            if (tiles.size >= ROW) break
        }
        return section("fav_artists", "Artistes favoris", tiles)
    }

    private fun radios(c: RotationContext, used: Set<String>): HomeSection? {
        val allSongs = c.recoItems.values.flatten()
            .filter { it.artworkUrl.isNotBlank() && !(it.reason?.code == ReasonCode.EXPLORATION && it.sourceId == "jiosaavn") }
            .distinctBy { it.trackId }
        val seeds = allSongs
            // A station is of an artist: a style's name ("rock") names no one to start it from.
            .filter { it.reason != null && it.reason.subject.isNotBlank() && it.reason.code != ReasonCode.GENRE }
            .groupBy { it.reason!!.subject }
            .entries.filter { it.value.size >= 3 }
            .sortedBy { hash(c.seed, "radio:${it.key}") }
        val tiles = seeds.mapIndexed { idx, (rawArtist, songs) ->
            val seedKey = artistKey(rawArtist)
            val bySeedArtist = songs.firstOrNull { artistKey(it.artist) == seedKey }
                ?: allSongs.firstOrNull { artistKey(it.artist) == seedKey }
            val leadSong = bySeedArtist ?: songs.first()
            val leadArtist = ArtistNames.primary(if (bySeedArtist != null) rawArtist else leadSong.artist).ifBlank { rawArtist }
            val leadKey = artistKey(leadArtist)
            val otherArtists = (songs + allSongs)
                .filter { artistKey(it.artist) != leadKey && it.artworkUrl != leadSong.artworkUrl }
                .distinctBy { artistKey(it.artist) }
                .distinctBy { it.artworkUrl }
            val headThree = (listOf(leadSong) + otherArtists.take(2)).distinctBy { it.artworkUrl }
            val orderedItems = (headThree + songs + otherArtists.take(15)).distinctBy { it.trackId }.take(40)
            val multiArtist = headThree.size >= 3 && ((idx + (hash(c.seed, "rmode") % 2).toInt()) % 2 == 0)
            SectionAlbum(
                title = if (multiArtist) "$leadArtist et autres artistes" else leadArtist,
                artist = "Station",
                artworkUrl = leadSong.artworkUrl,
                items = orderedItems,
                playsItems = true,
                blockableArtist = if (multiArtist) null else leadArtist,
            )
        }.distinctBy { artistKey(it.title.removeSuffix(" et autres artistes")) }
        return section("radios", "Radios personnalisées", pickRow(tiles, used, c.seed, "radios", shuffle = false))
    }

    /** Only what is listened to on the sources: these rows are for discovering, the device's own albums have their places elsewhere. */
    private fun online(c: RotationContext) = c.listens.filter { it.album.album == null }

    private fun decade(c: RotationContext, used: Set<String>): HomeSection? {
        val fromListens = online(c).filter { it.year in 1950..2100 && free(it.album, used) }
            .groupBy({ it.year / 10 * 10 }, { it.album })
        val fromTags = HashMap<Int, MutableList<SectionAlbum>>()
        for (a in c.external) {
            if (a.album != null || !free(a, used)) continue
            val tags = c.artistTags[artistKey(a.artist)].orEmpty()
            for (d in listOf(1970, 1980, 1990, 2000, 2010, 2020)) {
                val short = "${(d % 100).toString().padStart(2, '0')}s"
                if ("${d}s" in tags || short in tags || d.toString() in tags) {
                    fromTags.getOrPut(d) { ArrayList() } += a
                }
            }
        }
        val allDecades = (fromListens.keys + fromTags.keys).distinct()
        val byDecade = allDecades.associateWith { d ->
            (fromListens[d].orEmpty() + fromTags[d].orEmpty()).distinctBy { it.sameAs }
        }.filterValues { it.size >= ROW }
        if (byDecade.isEmpty()) return null
        val decade = byDecade.keys.sorted().let { it[(hash(c.seed, "decade") % it.size).toInt()] }
        return row("decade:$decade", "Années $decade", byDecade.getValue(decade), used, c.seed, "decade$decade", shuffle = true)
    }

    /** What the engine found for the listener's own taste ("Dans ton style", the day's mix), as albums. */
    private fun tasteMix(c: RotationContext, used: Set<String>): HomeSection? {
        val found = HomeSections.externalAlbums(mapOf("mix" to (c.recoItems["style"].orEmpty() + c.recoItems["daily"].orEmpty())))
            .filter { it.album == null }
        return row("tastemix", "Dans ton style", found, used, c.seed, "tastemix", shuffle = true, kicker = "Sélection sur mesure")
    }

    /** The row of the hour, made of the songs the sources found for that mood: energy to start the day, calm to focus, night to end it. */
    private fun routine(c: RotationContext, used: Set<String>): HomeSection? {
        val hour = Instant.ofEpochMilli(c.nowMs).atZone(c.zone).hour
        val (kind, title) = when (hour) {
            in 5..11 -> "energy" to "Pour commencer la journée"
            in 12..17 -> "chill" to "Focus & Concentration"
            else -> return null
        }
        val fromMood = HomeSections.externalAlbums(mapOf(kind to c.recoItems[kind].orEmpty())).filter { it.album == null }
        val fromOnlineListens = online(c).filter {
            (when (hour) { in 5..11 -> it.parts[0]; in 12..17 -> it.parts[1]; else -> it.parts[2] + it.parts[3] }) > 0
        }.map { it.album }
        return row("routine", title, fromMood + fromOnlineListens, used, c.seed, "routine", shuffle = true)
    }
}
