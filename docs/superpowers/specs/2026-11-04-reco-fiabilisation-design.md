# Design — Recommandations fiables, tuiles d'albums fidèles, ambiance mesurée

**Date** : 2026-11-04
**Branche** : `f-sans-accueil-immersif`
**Statut** : à approuver

## Intent (le pourquoi)

Deux symptômes poussent aujourd'hui à verrouiller les recommandations :

1. Sur l'Accueil, des **tuiles d'albums mentent** : la pochette + le titre promettent un album, et la page ouverte est une autre édition (Deluxe/Remastered/Live) ou un album homonyme qui y ressemble.
2. Des **erreurs de reco** (homonymes, fausses attributions, compilations déguisées) forcent à se limiter aux artistes validés — ce qui tue la découverte, qui est pourtant le principe même des recommandations.

Le but : rendre la découverte **sûre** (zéro erreur) sans la supprimer, et utiliser des signaux déjà mesurés (BPM/énergie) ainsi que les leçons des clients locaux (Flow / InterTune) pour améliorer le classement.

## Goals

- **A** — une tuile d'Accueil = le vrai album : pochette, titre et page concordent.
- **C** — discographie officielle complète et persistée ; rejet des candidats dont l'artiste n'est pas identifiable (homonymes).
- **BPM** — playlists d'ambiance (Ça bouge ! / Chill / Nuit) classées sur le vrai tempo/énergie, pas sur des mots-clés.
- **D** — diversité anti-effondrement, filtre par ratio d'engagement, transparence (« pourquoi ? »), semaine vs week-end.

## Non-goals / anti-goals

- Ne **pas** supprimer la découverte (ne pas réduire aux seuls artistes validés).
- Ne **pas** modifier l'Automix (crossfade) ni l'architecture de `SmartRanker`.
- Ne **pas** copier de code GPLv3 de Flow/InterTune : uniquement des concepts (idées génériques).
- **Anti-goal** : une reco « précise » qui ne propose plus rien de nouveau.

## Contraintes

- Le cœur de reco reste **pur** (sans Android), testable en JVM comme aujourd'hui.
- MusicBrainz ~1 req/s ; Deezer/Last.fm gratuits et bornés.
- Les règles sont versionnées par `RULES_VERSION` (bump à prévoir à chaque changement de sélection).
- La discographie doit **survivre au redémarrage** (Room, TTL), pas un cache mémoire.

## Approches (choisies et pourquoi)

- **A** : correctif ciblé sur la résolution + la pochette canonique (plutôt que B « vérifier que la page contient les morceaux », gardé en option stricte).
- **C** : persistance + fail-closed (plutôt que D « filtrer à la source »).
- **BPM** : réutiliser le signal local déjà calculé par l'Automix (plutôt qu'une nouvelle analyse).
- **D** : transposer les leçons Flow/InterTune aux structures existantes (`DayPart`, `clusterCap`, `Reason`).

## Détail par chantier

### A — Tuile = album (pas la chanson)

Fichiers : `source/ytm/AlbumRefResolver.kt`, `data/home/HomeSections.kt`, `reco/ExternalSources.kt`, `viewmodel/HomeWidgetsViewModel.kt`, `ui/screens/home/HomeScreen.kt`.

- `AlbumRefResolver.find` : **supprimer le fallback `contains`** ; exiger l'égalité du titre normalisé + même artiste ; rejeter les suffixes d'édition (Deluxe/Remastered/Live/…) sauf s'ils sont explicitement demandés.
- `HomeSections` : la `SectionAlbum` porte la **référence + la pochette d'album** (via `YtmClient.album(ref)`), plus la vignette vidéo de la chanson ; ne plus fusionner deux éditions par simple nom.
- `HomeScreen` : la tuile affiche la pochette d'album ; le clic ouvre la référence vérifiée.

Tests : `AlbumRefResolverTest` (éditions rejetées, homonymes), `HomeSectionsTest` (pas de fusion d'éditions).

### C — Discographie complète + fail-closed

Fichiers : `reco/authority/DiscographyVerifier.kt`, `reco/RecoDb.kt` (+migration), `reco/RecoPipeline.kt`, `reco/authority/ArtistAuthority.kt`.

- Nouvelle table `verified_discography` (artistKey → morceaux + albums officiels, `fetchedAt`) avec TTL 30 j.
- Couverture étendue : **tous** les albums Deezer (paginé) + leurs pistes, et les **enregistrements** MusicBrainz (pas seulement les release-groups).
- `RecoPipeline.acceptable` : rejeter un candidat « découverte » dont l'artiste n'a **ni mbid ni deezerId** ; garder les artistes déjà connus de l'auditeur.

Tests : `DiscographyVerifierTest`, `RecoPipelineTest` (fail-closed).

### BPM — Ambiance mesurée

Fichiers : `reco/Mood.kt`, lecture de `player/smart/AnalysisStore.kt`/`TrackAnalysis.kt`, `data/deezer/DeezerClient.kt`.

- `MoodModel`/`Ranker` : lire `bpm`, `energyCurve`, `vocalProbability` pour les pistes locales (déjà calculés par l'Automix) ; `bpm` Deezer en fallback pour les streamées ; mots-clés en dernier recours.

Tests : `MoodModelTest` (BPM prime sur les mots-clés).

### D — Leçons Flow/InterTune

Fichiers : `reco/SmartRanker.kt`, `reco/AffinityScorer.kt`, `reco/SmartDb.kt`/`TrackStatsEntity`, `reco/RecommendationConfig.kt`, + nouvel écran « Ton profil ».

1. **Diversité** : budget explicite anti-effondrement (au plus X % du même genre/décade/cluster, sinon pioche hors sujet).
2. **Qualité** : filtre par ratio d'engagement (`completion` + likes vs `earlySkipCount`/`consecutiveSkips`).
3. **Transparence** : écran « Ton profil » (top artistes/genres/décades + pourquoi chaque reco).
4. **Semaine** : `playsWeekday`/`playsWeekend` + pondération dans `SmartRanker`.

Tests : `SmartRankerTest` (diversité, qualité), `AffinityScorerTest` (semaine).

## Phases (ordre de livraison)

1. **A → C → BPM** (les deux bugs + le plus gros gain), livrées et validées une par une.
2. **D** (1 → 2 → 4 → 3).
3. **Crédits producteurs** (relations MusicBrainz) — hors de ce chantier, noté ici comme phase 3.

## Ce qui est laissé au builder

- Nom exact des colonnes de `verified_discography`.
- Seuils précis des ratios d'engagement et du budget de diversité (à proposer puis valider).
- Hiérarchie/placement de l'écran « Ton profil » (à proposer).
