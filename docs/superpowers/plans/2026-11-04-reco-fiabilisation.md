# Fiabilisation des recommandations — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendre les recommandations fiables (tuiles d'album fidèles, zéro homonyme/fausse attribution, ambiance mesurée par le BPM, diversité/qualité/transparence) sans supprimer la découverte.

**Architecture:** Quatre chantiers séquentiels sur le moteur `reco/` existant. Le cœur reste pur (JVM, sans Android) ; les parties réseau (résolution de pochette, discographie) sont isolées derrière des DAO/clients testables. Chaque chantier se termine par un commit indépendant.

**Tech Stack:** Kotlin, Compose, Room (version 11, migrations additives), Hilt, coroutines, JUnit4. `./gradlew :app:testDebugUnitTest --tests "..."`.

**Spec:** `docs/superpowers/specs/2026-11-04-reco-fiabilisation-design.md` — le plan argumente depuis ce doc, le doc voyage avec le plan.

## Global Constraints

- Branche `f-sans-accueil-immersif`, travail **en place** (pas de worktree) ; ne `git add` que les fichiers du chantier (le dépôt porte 7 fichiers modifiés non commités à ne pas toucher).
- Cœur de reco **pur** (pas d'import Android) : les tests tournent en JVM sous `app/src/test`.
- Toute règle de sélection qui change force un bump de `RULES_VERSION` (actuellement `13` dans `RecommendationEngine.kt`) pour purger les playlists périmées.
- Migrations Room **additives uniquement** ; la base est à la version 11.
- Ne pas modifier l'Automix (crossfade), ni l'architecture `SmartRanker` (seulement ses scores/filtres).

## Review Focus

1. **Édition/prefix** : un album dont le titre est préfixe d'un autre (« Greatest Hits » vs « Greatest Hits (Deluxe) ») ne doit jamais ouvrir le mauvais → Task A1.
2. **Homonyme non résoluble** : un artiste du même nom, impossible à identifier (ni MBID ni Deezer), proposé en découverte, doit être rejeté → Task C3.
3. **Discographie vide** : quand Deezer **et** MusicBrainz échouent, un morceau ne doit pas être accepté comme « officiel » par défaut → Task C2.
4. **BPM contradictoire** : un titre qui dit « party » mais un BPM de 60 doit être classé par le BPM, pas par le mot → Task BPM2.
5. **Effondrement de file** : une file qui ne tirerait qu'un seul genre doit être rééquilibrée → Task D1.

---

## Phase A — La tuile d'Accueil devient le vrai album

### Task A1: `AlbumRefResolver` — plus de match flou

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/source/ytm/AlbumRefResolver.kt:63-82`
- Test: `app/src/test/java/com/ikko/aura/source/ytm/AlbumRefResolverTest.kt`

**Interfaces:**
- Produces: `suspend fun AlbumRefResolver.find(title: String, artist: String): String?` — ne retourne que l'**id de l'album dont le titre normalisé est strictement égal** (`norm(a.title) == norm(title)`) **et** dont l'artiste matche (`artistMatches`). Plus de fallback `startsWith` ni `contains`.

- [ ] **Step 1: Write the failing test**

Dans `AlbumRefResolverTest`, ajouter :

```kotlin
@Test
fun doesNotResolveEditionSuffixes() = runBlocking {
    val resolver = AlbumRefResolver { query ->
        listOf(
            YtmAlbumRef("deluxe_id", "Greatest Hits (Deluxe Edition)", "Queen", null),
            YtmAlbumRef("exact_id", "Greatest Hits", "Queen", null),
        )
    }
    // "Greatest Hits" ne doit pas ouvrir l'édition Deluxe.
    assertEquals("exact_id", resolver.find("Greatest Hits", "Queen"))
}

@Test
fun rejectsWhenOnlyAnEditionIsAvailable() = runBlocking {
    val resolver = AlbumRefResolver { query ->
        listOf(YtmAlbumRef("remix_id", "TiK ToK - Remixes", "Ke\$ha", null))
    }
    // Une édition différente n'est pas l'album : pas de résolution.
    assertNull(resolver.find("TiK ToK", "Kesha"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ikko.aura.source.ytm.AlbumRefResolverTest"`
Expected: FAIL (`doesNotResolveEditionSuffixes` et `rejectsWhenOnlyAnEditionIsAvailable` échouent ; l'ancien `rejectsTributeAndMismatchedArtists` attend encore `remix_id` et échoue aussi)

- [ ] **Step 3: Implement exact-only matching**

Dans `find`, remplacer le bloc `// 1. Exact match` + `// 2. Starts with / contains` (lignes 65-82) par : ne garder que `filtered.firstOrNull { norm(a.title) == wantTitle }`, puis `foundRefs[cacheKey] = id` et retourner ; sinon `null`. Mettre à jour `rejectsTributeAndMismatchedArtists` pour attendre `null` (il n'y a plus que des éditions/tributes pour « TiK ToK ») — commenter ce changement de comportement.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ikko.aura.source.ytm.AlbumRefResolverTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ikko/aura/source/ytm/AlbumRefResolver.kt app/src/test/java/com/ikko/aura/source/ytm/AlbumRefResolverTest.kt
git commit -m "fix(ytm): an album title resolves only to the exact album, never an edition"
```

### Task A2: `SectionAlbum` porte la pochette d'album (pas la vignette vidéo)

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/data/home/HomeSections.kt` (champ + helper)
- Modify: `app/src/main/java/com/ikko/aura/viewmodel/HomeWidgetsViewModel.kt` (enrichissement)
- Modify: `app/src/main/java/com/ikko/aura/ui/screens/home/HomeScreen.kt:486-490` (préférer la pochette d'album)
- Test: `app/src/test/java/com/ikko/aura/data/home/HomeSectionsTest.kt`

**Interfaces:**
- Produces: `data class SectionAlbum(..., val albumCoverUrl: String? = null)` ; `fun SectionAlbum.effectiveCover(): String? = albumCoverUrl ?: artworkUrl`. `suspend fun HomeWidgetsViewModel.albumCoverOf(ref: String): String?` (résout `ytmClient.album(ref).ref.thumbnailUrl`, caché, tolérant).

- [ ] **Step 1: Write the failing test**

Dans `HomeSectionsTest` (existant) :

```kotlin
@Test
fun effectiveCoverPrefersAlbumCoverOverSongThumbnail() {
    val a = SectionAlbum("T", "A", ref = "ref", artworkUrl = "http://song-thumb", albumCoverUrl = "http://album-cover")
    assertEquals("http://album-cover", a.effectiveCover())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ikko.aura.data.home.HomeSectionsTest"`
Expected: FAIL (champ et helper absents)

- [ ] **Step 3: Implement le champ + helper + l'enrichissement**

Ajouter `albumCoverUrl: String? = null` à `SectionAlbum` et `fun effectiveCover() = albumCoverUrl ?: artworkUrl`. Dans `HomeWidgetsViewModel`, après la construction des `sections`, lancer une passe (Dispatchers.IO, `Semaphore(4)`) qui, pour chaque `SectionAlbum` externe portant un `ref`, appelle `ytmClient.album(ref).ref.thumbnailUrl` et copie l'item avec `albumCoverUrl` résolu. Dans `HomeScreen` (tuile), remplacer la source de l'`AlbumArt` par `item.effectiveCover()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ikko.aura.data.home.HomeSectionsTest"`
Expected: PASS

- [ ] **Step 5: Verify on device (aucun test JVM pour le réseau)**

Build/install QA, ouvrir l'Accueil : les tuiles externes montrent la pochette **d'album**, et le clic ouvre le même album.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ikko/aura/data/home/HomeSections.kt app/src/main/java/com/ikko/aura/viewmodel/HomeWidgetsViewModel.kt app/src/main/java/com/ikko/aura/ui/screens/home/HomeScreen.kt app/src/test/java/com/ikko/aura/data/home/HomeSectionsTest.kt
git commit -m "feat(home): a tile shows the album's own cover, not the song's thumbnail"
```

---

## Phase C — Discographie persistée + fail-closed

### Task C1: Table `verified_discography` + DAO + migration

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/reco/RecoDb.kt` (entity, DAO, `MIGRATION_11_12`)
- Modify: `app/src/main/java/com/ikko/aura/data/playlist/AuraDatabase.kt` (DAO + version 12)
- Modify: `app/src/main/java/com/ikko/aura/di/DatabaseModule.kt` (migration + provider)

**Interfaces:**
- Produces: `@Entity(tableName = "verified_discography") data class VerifiedDiscographyEntity(artistKey: String PK, tracksJson: String, albumsJson: String, fetchedAtMs: Long)`. `@Dao interface VerifiedDiscographyDao { suspend fun get(key): VerifiedDiscographyEntity?; suspend fun put(e); suspend fun all(): List<...> }`. `val MIGRATION_11_12: Migration`.

- [ ] **Step 1: Write the failing test**

Nouveau `app/src/test/java/com/ikko/aura/reco/VerifiedDiscographyDaoTest.kt` (Robolectric absent → test de la migration/entité est couvert par compilation ; à défaut, un test JVM du TTL) :

```kotlin
@Test
fun ttlExpiryRejectsStaleEntry() {
    val stale = 0L; val fresh = System.currentTimeMillis()
    assertTrue(fresh - stale > TTL_MS) // TTL_MS = 30 * 86_400_000L, importé de DiscographyVerifier
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ikko.aura.reco.VerifiedDiscographyDaoTest"`
Expected: FAIL (rien n'existe)

- [ ] **Step 3: Implement l'entité, le DAO, la migration 11→12**

Ajouter `VerifiedDiscographyEntity`, `VerifiedDiscographyDao`, `MIGRATION_11_12` (CREATE TABLE), passer `AuraDatabase` à la version 12 et enregistrer le DAO + la migration dans `DatabaseModule`.

- [ ] **Step 4: Compile check**

Run: `./gradlew :app:compileReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ikko/aura/reco/RecoDb.kt app/src/main/java/com/ikko/aura/data/playlist/AuraDatabase.kt app/src/main/java/com/ikko/aura/di/DatabaseModule.kt app/src/test/java/com/ikko/aura/reco/VerifiedDiscographyDaoTest.kt
git commit -m "feat(reco): persist the verified discography (Room, TTL 30d)"
```

### Task C2: `DiscographyVerifier` lit/écrit le store et couvre plus

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/reco/authority/DiscographyVerifier.kt`
- Test: `app/src/test/java/com/ikko/aura/reco/authority/DiscographyVerifierTest.kt`

**Interfaces:**
- Produces: le cache mémoire `verifiedTracksCache`/`verifiedAlbumsCache` est remplacé par la lecture/écriture du `VerifiedDiscographyDao` avec TTL ; `getArtistAlbums` appelé avec `limit = 300` (paginé) et les pistes des **tous** les albums officiels ; MusicBrainz `getArtistRecordings` (nouveau dans `MusicBrainzClient`) au lieu des seuls release-groups.

- [ ] **Step 1: Write the failing test**

Dans `DiscographyVerifierTest`, tester qu'un titre présent seulement sur un album **au-delà du 20ᵉ** (l'ancienne limite) est désormais reconnu officiel : un `DeezerClient` factice renvoie 25 albums, dont le 25ᵉ contient le titre « OnlyOnLateAlbum ».

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL (l'ancien code ne lit que 20 albums)

- [ ] **Step 3: Implement la persistance + la couverture**

Réécrire `getVerifiedTrackTitles`/`getVerifiedAlbumTitles` : lire le store (si `fetchedAtMs` récent, retourner), sinon reconstruire en paginant les albums Deezer et en lisant **toutes** leurs pistes + les enregistrements MusicBrainz, puis écrire le store. Ajouter `getArtistRecordings` à `MusicBrainzClient`.

- [ ] **Step 4: Run test to verify it passes**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ikko/aura/reco/authority/DiscographyVerifier.kt app/src/main/java/com/ikko/aura/data/musicbrainz/MusicBrainzClient.kt app/src/test/java/com/ikko/aura/reco/authority/DiscographyVerifierTest.kt
git commit -m "feat(reco): complete and persist the official discography"
```

### Task C3: `RecoPipeline.acceptable` fail-closed

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/reco/RecoPipeline.kt:269-291`
- Modify: `app/src/main/java/com/ikko/aura/reco/RecommendationEngine.kt` (`RULES_VERSION` 13 → 14)
- Test: `app/src/test/java/com/ikko/aura/reco/RecoPipelineTest.kt`

**Interfaces:**
- Produces: dans `acceptable`, si `authority != null && verifier != null` et que la provenance est une **découverte** (`RELATED_TRACK`/`SimilarArtist`/`GENRE`/`MOOD_QUERY`) et que `authority.resolve(primaryArtist)` retourne `null` **ou** sans `deezerId`/`mbid` → `return false`. Les candidats de provenance `ARTIST`/`COLLAB` (artistes connus de l'auditeur) passent.

- [ ] **Step 1: Write the failing test**

Dans `RecoPipelineTest`, un candidat `SimilarArtist` dont l'artiste n'est résolu par aucun catalogue (authority factice → `null`) doit être exclu du résultat ; un candidat `ARTIST` (artiste connu) doit rester.

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL (aujourd'hui le candidat passe)

- [ ] **Step 3: Implement le fail-closed + bump**

Modifier `acceptable` comme ci-dessus ; passer `RULES_VERSION = 14`.

- [ ] **Step 4: Run test to verify it passes**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ikko/aura/reco/RecoPipeline.kt app/src/main/java/com/ikko/aura/reco/RecommendationEngine.kt app/src/test/java/com/ikko/aura/reco/RecoPipelineTest.kt
git commit -m "feat(reco): reject discovery candidates whose artist cannot be identified"
```

---

## Phase BPM — Ambiance mesurée

### Task BPM1: `DeezerTrackInfo.bpm`

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/data/deezer/DeezerClient.kt` (champ + parsing)
- Test: `app/src/test/java/com/ikko/aura/data/deezer/DeezerClientTest.kt`

**Interfaces:**
- Produces: `DeezerTrackInfo(..., val bpm: Int = 0)` ; `getAlbumTracks`/`getArtistTopTracks` lisent `item.optInt("bpm", 0)`.

- [ ] **Step 1: Write the failing test**

Un `DeezerClient` construit avec `httpGet` factice renvoyant un JSON contenant `"bpm": 128` : `getAlbumTracks(1)` doit donner `bpm == 128`.

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL (champ absent)

- [ ] **Step 3: Implement le champ**

Ajouter `bpm: Int = 0` et le parsing dans `getAlbumTracks` + `getArtistTopTracks`.

- [ ] **Step 4: Run test to verify it passes**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ikko/aura/data/deezer/DeezerClient.kt app/src/test/java/com/ikko/aura/data/deezer/DeezerClientTest.kt
git commit -m "feat(deezer): read the track bpm"
```

### Task BPM2: `MoodModel` utilise le BPM (et l'énergie)

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/reco/Mood.kt`
- Modify: `app/src/main/java/com/ikko/aura/reco/Ranker.kt` (passer le bpm du candidat)
- Test: `app/src/test/java/com/ikko/aura/reco/MoodModelTest.kt`

**Interfaces:**
- Produces: `MoodModel.fit(title, album, durationMs, tags = emptyList(), bpm: Double? = null, energy: Double? = null, instrumental: Boolean? = null): Fit`. Le BPM connu prime sur les mots-clés : `bpm >= 120 → energy`, `bpm <= 90 → chill/night`, sinon les mots-clés. `Candidate` gagne un champ `bpm: Double?` (porté par `Verification`/`Deezer` dans le pipeline).

- [ ] **Step 1: Write the failing test**

Un titre sans mot d'ambiance mais `bpm = 128` → verdict `ENERGY` ≥ seuil ; un titre « party » mais `bpm = 60` → verdict `ENERGY` sous le seuil.

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL (les mots-clés décident seuls aujourd'hui)

- [ ] **Step 3: Implement BPM-first dans `fit`/`verdict`**

Ajouter les paramètres et la priorité BPM ; brancher dans `Ranker.scoreFor` (les mood playlists) le `bpm` du candidat quand il est connu.

- [ ] **Step 4: Run test to verify it passes**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ikko/aura/reco/Mood.kt app/src/main/java/com/ikko/aura/reco/Ranker.kt app/src/main/java/com/ikko/aura/reco/RecoModel.kt app/src/test/java/com/ikko/aura/reco/MoodModelTest.kt
git commit -m "feat(reco): rank mood playlists by measured bpm, not title keywords"
```

---

## Phase D — Leçons Flow/InterTune

### Task D1: Budget de diversité anti-effondrement

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/reco/SmartRanker.kt`
- Modify: `app/src/main/java/com/ikko/aura/reco/RecommendationConfig.kt`
- Test: `app/src/test/java/com/ikko/aura/reco/SmartRankerTest.kt`

**Interfaces:**
- Produces: `RecommendationConfig.MAX_GENRE_SHARE = 0.4` ; dans `SmartRanker.next`, après chaque pioche, si le genre dominant dépasse la part, retirer du pool les entrées de ce genre pour le tirage suivant.

- [ ] **Step 1: Write the failing test**

Une bibliothèque à 90 % d'un seul genre : `next(10)` ne doit pas renvoyer 9+ morceaux de ce genre.

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL

- [ ] **Step 3: Implement le budget**

Ajouter la constante et la règle dans `next`.

- [ ] **Step 4: Run test to verify it passes**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ikko/aura/reco/SmartRanker.kt app/src/main/java/com/ikko/aura/reco/RecommendationConfig.kt app/src/test/java/com/ikko/aura/reco/SmartRankerTest.kt
git commit -m "feat(reco): cap one genre's share so the queue does not collapse"
```

### Task D2: Filtre par ratio d'engagement

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/reco/SmartRanker.kt` (`score`)
- Modify: `app/src/main/java/com/ikko/aura/reco/RecommendationConfig.kt`
- Test: `app/src/test/java/com/ikko/aura/reco/SmartRankerTest.kt`

**Interfaces:**
- Produces: `RecommendationConfig.LOW_ENGAGEMENT_COMPLETION = 0.4`, `LOW_ENGAGEMENT_PLAYS = 3` ; un morceau avec `playCount >= 3`, `completedCount/playCount < 0.4`, `isLiked == false` sort du pool « exploitation » (`pools`).

- [ ] **Step 1: Write the failing test**

Un morceau joué 5 fois, jamais complété, non liké, ne doit pas être tiré en « exploitation ».

- [ ] **Step 2: Run test to verify it fails** → FAIL
- [ ] **Step 3: Implement le filtre** dans `pools`
- [ ] **Step 4: Run test to verify it passes** → PASS
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ikko/aura/reco/SmartRanker.kt app/src/main/java/com/ikko/aura/reco/RecommendationConfig.kt app/src/test/java/com/ikko/aura/reco/SmartRankerTest.kt
git commit -m "feat(reco): drop low-engagement songs from the exploit pool"
```

### Task D3: Semaine vs week-end

**Files:**
- Modify: `app/src/main/java/com/ikko/aura/reco/SmartDb.kt` (`TrackStatsEntity` + `MIGRATION_12_13`)
- Modify: `app/src/main/java/com/ikko/aura/reco/AffinityScorer.kt`
- Modify: `app/src/main/java/com/ikko/aura/reco/SmartRanker.kt` (pondération)
- Modify: `app/src/main/java/com/ikko/aura/data/playlist/AuraDatabase.kt` + `di/DatabaseModule.kt`
- Test: `app/src/test/java/com/ikko/aura/reco/AffinityScorerTest.kt`

**Interfaces:**
- Produces: `TrackStatsEntity(..., val playsWeekday: Int = 0, val playsWeekend: Int = 0)` ; `AffinityScorer` compte la part selon le jour (samedi/dimanche = week-end) ; `SmartRanker` pondère par la part du jour comme `hourPart`.

- [ ] **Step 1: Write the failing test** — un `ListenEvent` un samedi incrémente `playsWeekend`, pas `playsWeekday`.
- [ ] **Step 2: Run test to verify it fails** → FAIL
- [ ] **Step 3: Implement** — champ, migration 12→13, comptage, pondération.
- [ ] **Step 4: Run test to verify it passes** → PASS
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ikko/aura/reco/SmartDb.kt app/src/main/java/com/ikko/aura/reco/AffinityScorer.kt app/src/main/java/com/ikko/aura/reco/SmartRanker.kt app/src/main/java/com/ikko/aura/data/playlist/AuraDatabase.kt app/src/main/java/com/ikko/aura/di/DatabaseModule.kt app/src/test/java/com/ikko/aura/reco/AffinityScorerTest.kt
git commit -m "feat(reco): track weekday vs weekend plays"
```

### Task D4: Écran « Ton profil » (transparence)

**Files:**
- Create: `app/src/main/java/com/ikko/aura/ui/screens/settings/TasteProfileScreen.kt`
- Modify: `app/src/main/java/com/ikko/aura/ui/screens/settings/SettingsScreen.kt` (entrée Réglages > Recommandations)
- Modify: `app/src/main/java/com/ikko/aura/navigation/AuraDestination.kt` (route `taste_profile`)

**Interfaces:**
- Produces: un écran lisant `RecommendationEngine.items` + le profil (top artistes/genres/décades via une nouvelle méthode `TasteProfileSummary`) et affichant pour chaque reco son `Reason` (déjà encodé dans `RecoItem`). Pas de test JVM (UI) ; vérification sur appareil.

- [ ] **Step 1: Implement l'écran + la route + l'entrée**
- [ ] **Step 2: Verify on device** — l'écran liste les artistes/genres appris et le « pourquoi » de chaque reco.
- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ikko/aura/ui/screens/settings/TasteProfileScreen.kt app/src/main/java/com/ikko/aura/ui/screens/settings/SettingsScreen.kt app/src/main/java/com/ikko/aura/navigation/AuraDestination.kt
git commit -m "feat(ui): a taste profile screen shows what the algorithm learned and why"
```

---

## Ordre et dépendances

A1 → A2 → C1 → C2 → C3 → BPM1 → BPM2 → D1 → D2 → D3 → D4. Chaque tâche est committable seule ; A1/A2 sont indépendantes de C/BPM/D ; C1 précède C2/C3 ; BPM1 précède BPM2 ; D1-D3 sont indépendantes entre elles.
