# Fiabilisation du Système de Recommandation Musicale : Autorité & Désambiguïsation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Éliminer définitivement les erreurs d'homonymes et les fausses attributions de morceaux dans le système de recommandation en connectant la logique exclusivement aux sources gratuites et ouvertes (Deezer API publique, Last.fm API gratuite, MusicBrainz API open-source).

**Architecture:** 
1. Module d'accès MusicBrainz (`MusicBrainzClient`) avec respect du rate limiting et cache persistant.
2. Module Deezer enrichi (`DeezerClient`) pour discographies officielles, top titres et graphe d'artistes reliés.
3. Résolveur d'autorité et désambiguïsation (`ArtistAuthority` / `ArtistDisambiguator`) associant chaque artiste à un profil vérifié `[MBID, deezerId, country, primaryTags]`.
4. Vérificateur d'intégrité de discographie (`DiscographyVerifier`) validant chaque piste proposée contre le catalogue officiel et éliminant tout scrobble pollué ou fausse attribution.
5. Recommandations par graphe de données réelles (Deezer related + Last.fm similar + MusicBrainz) avec validation stricte de la paire `[artist_id_officiel] -> [track_id_officiel]`.

**Tech Stack:** Kotlin, OkHttp / HttpURLConnection, Kotlin Coroutines, JSON, Room / SharedPreferences Cache, JUnit4.

**Spec:** Directives de l'utilisateur pour la résolution des homonymes (MusicBrainz MBID, Deezer id, Last.fm mbid/toptags), intégrité de discographie (zéro hallucination, filtrage des faux scrobbles Last.fm), et recommandations par graphe d'API réelles.

## Global Constraints
- Aucune API payante ou restreinte (pas de Spotify). Uniquement Deezer, Last.fm et MusicBrainz.
- Aucune association basée uniquement sur une chaîne de texte brute sans vérification.
- Validation stricte de chaque morceau proposé contre la discographie officielle.
- Zéro devinette interne ou hallucination textuelle.
- Performance et respect des quotas réseau (cache persistant, 1 req/s pour MusicBrainz).

## Review Focus
1. Homonymes stricts (ex: YENA K-pop vs homonymes d'autres pays/genres) correctement isolés par pays/tags/MBID.
2. Scrobbles pollués Last.fm (ex: "Girlboss energy", "OG Bichotas" attribué à Lady Gaga) rejetés à 100% car absents de la discographie officielle Deezer/MusicBrainz.
3. Morceaux YouTube Music / JioSaavn non officiels, fanmades ou vidéos exclus s'ils ne correspondent pas à la discographie officielle vérifiée de l'artiste.
4. Graphe de similarité basé sur Deezer `/artist/{id}/related` et Last.fm `getSimilar` filtré par cohérence de tags/MBID.
5. Robustesse hors-ligne et en cas de panne réseau grâce au cache persistant sur disque.

---

### Task 1: Client API MusicBrainz (`MusicBrainzClient.kt`)

**Files:**
- Create: `app/src/main/java/com/ikko/aura/data/musicbrainz/MusicBrainzClient.kt`
- Create: `app/src/test/java/com/ikko/aura/data/musicbrainz/MusicBrainzClientTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class MusicBrainzArtist(
      val mbid: String,
      val name: String,
      val sortName: String?,
      val country: String?,
      val disambiguation: String?,
      val tags: List<Pair<String, Int>>,
      val aliases: List<String>,
      val score: Int
  )
  data class MusicBrainzReleaseGroup(
      val id: String,
      val title: String,
      val primaryType: String?,
      val firstReleaseDate: String?
  )
  interface MusicBrainzService {
      suspend fun searchArtist(name: String): List<MusicBrainzArtist>
      suspend fun getArtistReleaseGroups(mbid: String): List<MusicBrainzReleaseGroup>
  }
  ```

- [ ] **Step 1: Write the failing test**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `MusicBrainzClient` with rate limiter (1 req/s) and JSON parser**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 2: Client API Deezer Dédié (`DeezerClient.kt`)

**Files:**
- Create: `app/src/main/java/com/ikko/aura/data/deezer/DeezerClient.kt`
- Create: `app/src/test/java/com/ikko/aura/data/deezer/DeezerClientTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class DeezerArtistProfile(
      val id: Long,
      val name: String,
      val nbFan: Int,
      val pictureXl: String?,
      val tracklistUrl: String?
  )
  data class DeezerAlbumInfo(
      val id: Long,
      val title: String,
      val recordType: String, // album, single, ep, compile
      val releaseDate: String?
  )
  data class DeezerTrackInfo(
      val id: Long,
      val title: String,
      val titleShort: String,
      val artistId: Long,
      val artistName: String,
      val albumId: Long?,
      val durationSec: Int
  )
  interface DeezerService {
      suspend fun searchArtist(name: String): List<DeezerArtistProfile>
      suspend fun getArtistAlbums(artistId: Long, limit: Int = 100): List<DeezerAlbumInfo>
      suspend fun getArtistTopTracks(artistId: Long, limit: Int = 100): List<DeezerTrackInfo>
      suspend fun getAlbumTracks(albumId: Long): List<DeezerTrackInfo>
      suspend fun getRelatedArtists(artistId: Long, limit: Int = 30): List<DeezerArtistProfile>
  }
  ```

- [ ] **Step 1: Write the failing test**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `DeezerClient` using Deezer public endpoints**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 3: Désambiguïsateur d'Artistes (`ArtistAuthority.kt`)

**Files:**
- Create: `app/src/main/java/com/ikko/aura/reco/authority/ArtistAuthority.kt`
- Create: `app/src/test/java/com/ikko/aura/reco/authority/ArtistAuthorityTest.kt`

**Interfaces:**
- Consumes: `MusicBrainzService`, `DeezerService`, `LastFm`
- Produces:
  ```kotlin
  data class DisambiguatedArtist(
      val canonicalKey: String,
      val canonicalName: String,
      val mbid: String?,
      val deezerId: Long?,
      val country: String?,
      val primaryTags: Set<String>,
      val disambiguation: String?
  )
  interface ArtistAuthority {
      suspend fun resolve(name: String, listenerContextTags: Set<String> = emptySet()): DisambiguatedArtist?
  }
  ```

- [ ] **Step 1: Write the failing test for homonym resolution (e.g. YENA K-pop vs homonymes)**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement cross-referencing resolution logic (MusicBrainz MBID + Deezer ID + Last.fm tags)**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 4: Vérificateur d'Intégrité de Discographie (`DiscographyVerifier.kt`)

**Files:**
- Create: `app/src/main/java/com/ikko/aura/reco/authority/DiscographyVerifier.kt`
- Create: `app/src/test/java/com/ikko/aura/reco/authority/DiscographyVerifierTest.kt`

**Interfaces:**
- Consumes: `DeezerService`, `MusicBrainzService`
- Produces:
  ```kotlin
  interface DiscographyVerifier {
      suspend fun getOfficialTrackTitles(artist: DisambiguatedArtist): Set<String>
      suspend fun isOfficialTrack(artist: DisambiguatedArtist, trackTitle: String): Boolean
      suspend fun filterOfficialTracks(artist: DisambiguatedArtist, candidates: List<String>): List<String>
  }
  ```

- [ ] **Step 1: Write failing test rejecting fake titles ("Girlboss energy", "OG Bichotas" for Lady Gaga)**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement official release & track fetching + title normalization**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 5: Intégration dans le Graphe de Recommandation & Filtrage des Candidats

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/reco/RecoPipeline.kt`
- Modify: `app/src/main/java/com/ikko/aura/reco/Candidates.kt`
- Modify: `app/src/main/java/com/ikko/aura/reco/LastFmEnricher.kt`
- Modify: `app/src/main/java/com/ikko/aura/lastfm/LastFm.kt`
- Test: `app/src/test/java/com/ikko/aura/reco/RecoPipelineTest.kt`

**Interfaces:**
- Valide pour chaque suggestion la paire stricte `[artist_id_officiel] -> [track_id_officiel]`.
- Utilise Deezer `getRelatedArtists` et Last.fm `similar` désambiguïsé.

- [ ] **Step 1: Write integration tests verifying non-official candidates are excluded**
- [ ] **Step 2: Run test to verify failure**
- [ ] **Step 3: Connect DiscographyVerifier and ArtistAuthority into CandidateGatherer, LastFm and RecoPipeline**
- [ ] **Step 4: Run all unit tests to verify pass**
- [ ] **Step 5: Compile and verify on device**
- [ ] **Step 6: Commit**
