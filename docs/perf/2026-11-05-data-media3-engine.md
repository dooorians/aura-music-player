# Audit couche Data / Room / scanner / réseau / Media3 — branche `perf/data-media3-engine`

Base : `feat/algo-v2` @ `7d4a16c`. Aucun fichier `@Composable` (`ui/`) ni formule de recommandation n'a été modifié.
Les fichiers du paquet `reco/` touchés le sont uniquement pour leurs déclarations Room, leurs écritures en base ou la
lecture des tags (voir la liste en fin de document).

Validation : `./gradlew test assembleDebug` → **BUILD SUCCESSFUL**, 525 tests (483 avant + 42 nouveaux), 0 échec,
1 ignoré (déjà ignoré sur la base).

Les gains ci-dessous sont **estimés à partir du code** (nombre d'écritures, de requêtes, d'octets). Ils n'ont pas été
mesurés sur un appareil : ce conteneur n'en a pas.

## 1. Base de données (Room, schéma 19 → 20)

Migration `MIGRATION_19_20` (`data/db/PerformanceMigrations.kt`), additive. `MigrationSchemaTest` compare chaque
instruction au schéma exporté par Room (`schemas/…/20.json`), ce qui évite un plantage au premier lancement après la
mise à jour.

### Index ajoutés

| Table | Index | Requête servie |
|---|---|---|
| `playlist_tracks` | `(playlistId, position)` | toutes les lectures d'une playlist (`ORDER BY position`), `MIN/MAX(position)` à chaque ajout ou favori |
| `album_plays` | `(lastPlayedAtMs)` | « écoutés récemment » : `ORDER BY lastPlayedAtMs DESC LIMIT n` |
| `ytm_downloads` | `(state, updatedAt)` | `nextPending()`, appelée avant chaque téléchargement |
| `reco_history` | `(shownAtMs)` | `pruneHistory(before)` |
| `reco_items` | `(trackId)` | `removeItems(trackIds)`, `removeItem` (titre masqué, artiste bloqué) |
| `remote_cache` | `(fetchedAtMs)` | purge des réponses expirées (nouvelle table, §3) |

**Index supprimé** : `index_artist_similarity_sourceArtistKey`. Il doublait la clé primaire
`(sourceArtistKey, targetArtistKey)`, qui sert déjà `WHERE sourceArtistKey = ?`. Il ne faisait que ralentir les écritures.

**Index volontairement non ajoutés** (BPM, genres, artistes, `trackId` des écoutes) : aucune requête SQL ne filtre ni
ne trie sur ces colonnes. `track_stats`, `track_tags` et `song_features` sont lus par clé primaire ou en entier, puis
filtrés en mémoire par le moteur. Un index n'y apporterait rien en lecture et coûterait à chaque écriture.
`reco_listens.endedAtMs` et `track_bindings.trackKey` étaient déjà indexés.

### Requêtes N+1 et écritures par lots

- **Liste des playlists (N+1)** : chaque playlist avait sa propre requête observée (`combine` de N `Flow`). Une écriture
  dans `playlist_tracks` relançait donc N requêtes et reconstruisait la liste N fois. C'est maintenant une relation
  Room `@Relation` + `@Transaction` (`PlaylistWithEntries`) : **2 requêtes quel que soit N**.
- `replaceTracks` (playlists auto, renouvellement des recos) : un `INSERT` par titre → un seul insert par lot.
- `removeFromAutoPlaylists` : un `DELETE` par titre et par playlist → un `DELETE … IN (…)` par playlist, découpé par 500.
- `toggleFavorite` et `addTrack` : la lecture de la position et l'insertion passent dans une même `@Transaction`
  (`toggleTrackAtTop`, `appendTrack`). Avant, deux appuis rapprochés pouvaient lire la même position.
- `LastFmEnricher` : `putArtist` + `clearSimilarities` + `putSimilarities` passent dans une seule transaction
  (`replaceArtistGraph`). Avant, un crash entre les deux laissait l'artiste sans voisins pendant 30 jours.
- `favoriteEntries()` lit uniquement la playlist Favoris, au lieu de toute la table filtrée en mémoire.
- `forgetKeys` et la purge des tags sont découpés par 500 identifiants.

## 2. Scanner de bibliothèque et tags

- **Synchronisation différentielle du catalogue** (`catalog/LocalCatalogSync.kt`). À chaque lancement, le scan effaçait
  toutes les liaisons locales puis les réinsérait, et réécrivait toutes les lignes de `catalog_tracks` : environ 3
  écritures par fichier, plus l'index, plus une invalidation de chaque requête qui observe le catalogue. Il n'écrit
  plus que ce qui a changé. **Bibliothèque inchangée : 0 écriture** (avant : environ 15 000 lignes pour 5 000 fichiers).
  La date « vu pour la dernière fois » d'un fichier inchangé est rafraîchie une fois par jour au plus. Aucune requête
  ne la lit plus finement pour un fichier local.
- **Mesure des propriétés audio** (`MediaExtractor`) : 3 fichiers à la fois, par fenêtres de 12, au lieu d'un par un.
  La pause totale accordée à l'affichage des miniatures reste la même. Seuls les fichiers nouveaux ou modifiés sont
  mesurés : ce cache différentiel existait déjà.
- **Lecture des tags** (`TrackTagScanner`) :
  - 3 lectures à la fois par lot ;
  - délai maximal de 15 s par fichier : un fichier corrompu qui bloque l'analyseur ne bloque plus le scan, et il est
    enregistré comme « sans tags » ;
  - `Throwable` capturé, y compris un `OutOfMemoryError` sur une pochette géante ;
  - les lignes des fichiers disparus sont purgées. Rien ne les supprimait avant : la table ne faisait que grossir.
- **Analyse des tags** (`TagMap`, `ufidPair`) :
  - BOM UTF-16, `NUL` de fin de chaîne ID3 et caractères de contrôle sont retirés ;
  - les valeurs multiples séparées par `NUL` sont conservées ;
  - la trame UFID est décodée en ISO-8859-1 explicitement, plus avec le jeu de caractères de la plateforme.
  - **Bug corrigé** : Picard écrit le propriétaire UFID `http://musicbrainz.org`, que le code ne reconnaissait pas. Le
    MBID des MP3 taggés par Picard n'était donc jamais lu. `TagScanVersion` passe à 2, ce qui relit une fois les
    fichiers déjà scannés en arrière-plan.

## 3. Réseau (Last.fm) et cache

- **Cache persistant** : les réponses Last.fm (tags, similaires, top titres, MBID…) étaient une clé chacune dans un
  fichier SharedPreferences. Ce fichier ne fait que grossir (une clé par artiste, et une par titre recommandé), il est
  chargé entier en mémoire, et **réécrit entièrement à chaque `apply()`**, c'est-à-dire des centaines de fois par
  génération de recommandations. Une activité qui passe en pause attend aussi la fin de ces écritures : c'est une cause
  connue d'ANR. Elles sont maintenant dans la table Room `remote_cache`, une ligne par réponse. L'ancien fichier est
  migré par lots au premier usage, et les entrées expirées sont purgées.
- **Disjoncteur** (`util/Backoff.kt`). Après une erreur réseau, un délai dépassé, un HTTP 429/5xx ou les codes Last.fm
  11, 16 ou 29 : pause exponentielle de 5 s à 10 min, au moins 60 s pour un rate-limit. Hors ligne, l'enrichissement en
  arrière-plan n'attend plus un délai de connexion de 8 s par artiste ; les appels sont refusés immédiatement et les
  scrobbles restent en file. La connexion au compte (action de l'utilisateur) passe outre.
- **Délais d'attente réels** (`util/AbortableIo.kt`). Les `withTimeoutOrNull(6 s)` du pipeline ne coupaient pas un
  `HttpURLConnection` bloqué. L'attente durait en réalité jusqu'à 20 s (8 s de connexion + 12 s de lecture). La
  connexion est maintenant fermée à l'annulation, et l'appelant reçoit bien l'annulation, pas une `IOException`
  (défaut trouvé par le test). Une réponse non-JSON (page de proxy, 5xx) est traitée comme une panne temporaire.
- Deezer, MusicBrainz et ReccoBeats avaient déjà un espacement des requêtes, la gestion du 429 et un cache mémoire. Les
  paroles avaient déjà un cache des absences. Pas de changement.

## 4. Service audio (Media3)

- **`setWakeMode(C.WAKE_MODE_NETWORK)`** sur les deux `ExoPlayer`, avec la permission `WAKE_LOCK`. Écran éteint, le CPU
  et le Wi-Fi pouvaient s'endormir pendant qu'un titre en streaming se chargeait. Le verrou n'est pris que pendant la
  lecture ou le chargement : le lecteur de réserve, inactif, n'en tient aucun.
- **Persistance de la file** (`QueueSnapshotFiles`). La file complète (des centaines de Ko pour une file de la taille
  de la bibliothèque) était réencodée et réécrite **toutes les 10 s** dans le DataStore des réglages, qui réécrit tout
  son fichier, réglages compris. Elle a maintenant deux fichiers à elle : les titres, réécrits seulement quand la file
  change, et un état de quelques octets (index, position, modes) réécrit à chaque tick. Les écritures sont atomiques
  (fichier temporaire puis renommage) et sérialisées par un `Mutex`. Avant, deux écritures pouvaient se croiser. Un
  hash des identifiants empêche d'appliquer une position à une autre file. L'ancienne valeur du DataStore est encore
  lue, puis supprimée. Ordre de grandeur : de ~300 Ko toutes les 10 s à ~100 octets.
- **Résolution de la file** (`onAddMediaItems`, `onPlaybackResumption`) : 8 résolutions à la fois au lieu d'une par
  une. Une file de plusieurs centaines de titres retardait le début de la lecture. Si le service s'arrête en cours, le
  `ListenableFuture` est quand même complété, alors qu'avant il restait en attente indéfiniment.
- **Récupération d'erreur** (`PlaybackRecovery`) :
  - une décision prise pour un lecteur qui n'a plus la session (fondu enchaîné passé entre-temps, service libéré)
    n'est plus appliquée ;
  - la table des tentatives par titre est bornée ;
  - un fichier tronqué (`ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE`) est sauté comme un fichier corrompu, au lieu
    d'arrêter la lecture.
- **Vérifié sans changement** :
  - un seul des deux lecteurs gère le focus audio et le débranchement du casque ou du Bluetooth, et le passage de l'un
    à l'autre est correct ;
  - les listeners sont retirés (`attach`/`release`), le scope est annulé dans `onDestroy`, et les lecteurs comme la
    session sont libérés ;
  - les transitions sont gérées par le moteur de fondu à deux lecteurs.

## Limites et pistes non traitées

- `PlayerController` ne se reconnecte pas si le `MediaController` est déconnecté. En pratique, cela n'arrive que si le
  processus meurt, et tout redémarre alors. Une reconnexion obligerait à réabonner `PlayerViewModel`, que d'autres
  branches modifient.
- `playlistDetail` observe encore toute la table `catalog_tracks` pour nommer les titres indisponibles.
- Pas de test d'instrumentation Room (`MigrationTestHelper`) : il faudrait un émulateur. La migration est vérifiée
  contre les schémas exportés.

## Fichiers `reco/` touchés (hors formules)

- `RecoDb.kt` : index sur deux entités.
- `SmartDb.kt` : index redondant retiré, transaction `replaceArtistGraph`.
- `LastFmEnricher.kt` : appel de cette transaction.
- `TrackMetaDb.kt` : `TagScanVersion` 1 → 2.
- `TagMap.kt` et `TrackTagScanner.kt` : lecture des tags.
