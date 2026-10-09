# Session nocturne perf — 9 octobre 2026

- **Build testée :** Aura QA (`com.ikko.aura.qa`). C'est la variante `qa`, c'est-à-dire la release R8, non débogable, profilable depuis le shell.
- **Code :** worktree `copilot-worktrees/Aura/dooorians-shiny-giggle`, branche `qa-liquid-glass-pills`, commit `55e5661` plus ton travail non commité.
- **Appareil :** iKKO MindOne, Android 15, 1080×1240, sur secteur. Le rafraîchissement est fixe à **96 Hz** : un seul mode d'affichage, vérifié dans `dumpsys display`, soit 10,42 ms par image.
- **Ton app du quotidien :** `com.ikko.aura` n'a été ni ouverte, ni installée, ni modifiée.

## 1. Résumé

**Verdict : amélioration mesurée, objectifs partiellement atteints.**

Ce qui est acquis et mesuré en A/B (passes A-B-B-A, mêmes parcours, §8) :

1. **Fin du rendu permanent.** La build de départ redessinait toute la fenêtre à 96 Hz même à l'arrêt, verre compris. En cause : le widget GIF de l'Accueil, qui demandait une image à chaque vsync, même hors écran ou sur la page Bibliothèque.
   - Bibliothèque au repos, musique en cours : **~1 600 images en 10 s et ~188 % d'un cœur CPU → 0 image et ~9 %** (le décodage audio).
   - Accueil au repos : **~1 520 images et ~182 % CPU → ~345 images et ~54 %**. Il ne reste que le rythme propre des GIF visibles, ~17 à 21 images/s.
   - Retour depuis l'arrière-plan : **1 800 images → 1 image par retour**.
2. **CPU divisé par 2 à 5 sur tous les parcours animés** (§8).
3. **Boucle d'écriture des réglages pendant la lecture supprimée.** D'après le code, l'instantané de la file était réécrit en boucle (une écriture toutes les ~0,6 s, plus le temps d'écriture) tant que la musique jouait. Le pool `DefaultDispatcher` au repos avec lecture passe de **~6,7 % d'un cœur à ~0,1 %**.
4. **Analyse Automix en arrière-plan.** Elle ne prend plus un cœur entier en concurrence avec l'interface. Recherche et changement de piste sont plus réguliers (§8).
5. **Défaut corrigé au passage :** les GIF pouvaient se figer définitivement (arrondi d'Android, §5.2). La première version GIF de cette nuit le montrait au démarrage, une fois sur deux à deux fois sur trois.

Ce qui n'est **pas** atteint :

- **Défilement de l'Accueil : 76,7 images/s en moyenne** (deux passes : 84,7 et 68,6), sous le seuil de 80. La build de départ affichait 90,6, mais seulement parce qu'elle dessinait sans arrêt. Cela gardait CPU et GPU à haute fréquence, au prix de ~1,6 cœur en permanence, ce que la mission interdit. L'Accueil reste limité par le RenderThread et le GPU (~8 ms par image pour un budget de 10,4). Le verre de la mini-pilule en est la plus grosse part mesurée (§5.4).
- **Ouverture du lecteur :** un premier frame de ~32 ms à chaque ouverture, dont ~21 ms d'enregistrement du dessin sur le thread principal. Il existait déjà avant ; il ressort davantage sans le rendu permanent (p99 de l'intervalle 23,7 → 39,7 ms). Une piste a été testée et rejetée cette nuit (§7). La cause reste ouverte.
- **Paroles :** non mesurées. Voir §9 et §11.

## 2. État initial et fichiers modifiés

- **Au départ :** 54 fichiers modifiés ou non suivis dans le worktree (ton travail et celui des sessions précédentes), dont certains déjà indexés (« MM »). Je n'ai rien indexé, rien commité, rien poussé.
- **Tests unitaires de départ :** `./gradlew :app:testDebugUnitTest` → 127 tests, 0 échec. Il n'existe pas de tâche de test pour la variante QA.
- **APK QA installé au départ :** sauvegardé dans `nuit-2026-10-09/apk/qa_installed_before_session.apk` (md5 `ece972a7…`).

Fichiers que j'ai modifiés cette nuit. Les patchs, séparés de ton travail, sont dans `nuit-2026-10-09/patch/` et les originaux dans `nuit-2026-10-09/raw/*.A` :

| Fichier | Changement |
|---|---|
| `ui/screens/home/HomeWidgetCard.kt` | GIF rendus image par image au rythme du GIF, sur un thread dédié et en double tampon ; seulement si visibles et app au premier plan |
| `data/SettingsStore.kt` | chaque réglage n'émet que si sa propre valeur change |
| `source/SourceOrder.kt` | idem pour l'ordre des sources |
| `ui/components/TabCapsule.kt` | capsule non dessinée quand elle est entièrement cachée (Accueil) |
| `MainLayout.kt` | idem quand le lecteur, les paroles ou la file la recouvrent ; règle de visibilité factorisée (`capsuleAlpha()`) |
| `ui/components/ProfileAvatar.kt` | photo de profil décodée à 256 px maximum, au lieu de la taille de la photo |
| `player/smart/TrackAnalyzer.kt` | analyse Automix en priorité d'arrière-plan |
| `data/LocalMusicRepository.kt` | regroupement des albums et choix des pochettes hors du thread principal, après le scan |
| `scripts/perf/night/*` | outillage de mesure (nouveau) |

Code temporaire de diagnostic : des interrupteurs `PerfFlags` ont servi à retirer un élément à la fois. Ils ont été **retirés**, et `grep PerfFlags` ne renvoie plus rien.

## 3. Plugins et skills utilisés

- **chrisbanes-skills / android-benchmark-comparison** : protocole A/B, avec ordre équilibré A-B-B-A, mêmes parcours, conditions notées et données brutes gardées. Les moyennes sont des moyennes de passes, présentées comme telles.
- **chrisbanes-skills / compose-performance** : lecture d'état dans la phase de dessin, couche dédiée pour ce qui change souvent, pas d'annotation de stabilité.
- **superpowers / systematic-debugging** : symptôme → preuve → hypothèse → test minimal. Quatre hypothèses ont été rejetées sur mesure (§7). **superpowers / verification-before-completion** : chaque correctif est rebuildé, testé sur l'appareil et mesuré avant d'être gardé.
- **android-profiler** (skill locale) : méthode Perfetto (frame timeline SurfaceFlinger, slices HWUI), `gfxinfo`, CPU par thread via `/proc`.
- **Sous-agent d'audit statique** (lecture seule, données / images / concurrence) : il a trouvé la boucle des réglages (§5.3) et l'avatar décodé en plein format (§5.5). Les deux ont été vérifiés dans le code, puis corrigés.
- **Ponytail** : correctifs minimaux, sans nouvelle abstraction.
- **UI design, Design, frontend-design, design-dna** : non utilisés. La mission n'était pas un redesign et aucun correctif ne change l'apparence. Les serveurs MCP du plugin Design (Figma, Notion…) demandent une authentification que je ne peux pas faire.
- **Allium** : non pertinent (spécifications comportementales pour un autre workflow).
- **Haze** : n'est plus dans le projet. Le verre est la bibliothèque `backdrop` (Kyant0) intégrée aux sources (`ui/components/backdrop`).
- **Macrobenchmark** : non utilisé. Il aurait fallu un module de benchmark et une app de test installée en plus, alors que `gfxinfo` et Perfetto suffisent sur la vraie app.

## 4. Conditions de mesure

- **Appareil :** 96 Hz fixe, sur secteur (batterie 98 → 100 %), statut thermique 0 (CPU 41 à 57 °C), économiseur système désactivé, Wi-Fi ADB. Le son média était déjà coupé (volume 0) : je n'ai rien changé, et la lecture pendant les tests était silencieuse.
- **Réglages QA, inchangés :** fond AMOLED activé, paroles en ligne activées, Automix activé. L'« économiseur de batterie » de l'app n'existe plus dans cette build : le verre est toujours actif.
- **Avant chaque passe :** installation, `cmd package compile -m speed -f` (comme une installation Play préchauffée), même parcours de préchauffage, et même titre lancé (« -FINAL SIGMA- »). La lecture est active pendant chaque parcours.
- **Outils et ce qu'ils mesurent :**
  - **Perfetto frame timeline :** ce que SurfaceFlinger a réellement affiché pour la fenêtre de l'app.
    - *fps* = images affichées par seconde dans les fenêtres d'animation (images à moins de 60 ms l'une de l'autre).
    - *long%* = part des intervalles entre images de plus de 1,5 vsync, soit un à-coup visible.
    - *p95 / p99* = percentiles de ces intervalles.
  - **`dumpsys gfxinfo` :** comptabilité HWUI. *gfxJank%* = images qui ont manqué leur échéance.
  - **Slices HWUI :** temps par image du RenderThread (*rt50*) et du thread principal (*ui95*).
  - **`/proc/<pid>/task/*/stat` :** CPU par thread, en % d'un cœur sur la durée du parcours.
  - **`am start -W` :** démarrages à froid et à chaud.
- **Limite importante :** les *fps* de la build A sont gonflés. Elle dessinait sans arrêt, donc tout parcours forme une seule « fenêtre » à ~90 images/s, moments immobiles compris. Ses images étaient toutes classées « Buffer Stuffing » : cadence régulière, mais une image de latence en plus. Les colonnes CPU, *gfxJank%*, *rt50* et *ui95* sont plus comparables.

## 5. Problèmes reproduits, preuves

### 5.1 Rendu permanent à 96 Hz, même à l'arrêt

- **Symptôme :** sur la build de départ (référence A1, 02:39), l'app dessine en continu sur l'Accueil au repos (1 497 images en ~16 s, ~90/s). Même chose sur la Bibliothèque au repos (1 561 images).
- **CPU à l'arrêt :** 189 % sur l'Accueil et 207 % sur la Bibliothèque, dont RenderThread 87 % et pilote GPU `mali-cmar-backe` 60 %.
- **Preuve :** le widget GIF demandait une image à chaque vsync (`withFrameNanos` en boucle). Comme la page Accueil reste composée à côté de la Bibliothèque dans le pager, il le faisait aussi hors écran. Chaque image redessinait la fenêtre entière, dont la mini-pilule en verre qui échantillonne la page.
- **Après correction (G3, 02:57) :** Bibliothèque au repos à 0 image et 0 % CPU ; Accueil au repos à 168 images en 10 s et 48 % CPU.

### 5.2 GIF : deux tentatives écartées, puis un défaut d'Android

- **Version 1 (G1) :** laisser `AnimatedImageDrawable` s'animer seul sur le RenderThread. Le repos s'améliore, mais le RenderThread produit alors ses propres images (`dispatchFrameCallbacks`, 391 × ~6 ms) en décalage avec celles de l'interface. Résultat : 17,7 % d'images ratées au défilement de l'Accueil, contre 12,2 % avant. **Retirée.**
- **Version 2 :** rendu logiciel sur le thread principal. Il coûte 1,75 ms de thread principal par image de GIF, et le défilement monte à 10 % d'images ratées (contre 2 % sans GIF, attribution §5.4). **Remplacée.**
- **Version finale :** rendu sur un thread dédié (`aura-gif`), en double tampon, synchronisé avec le thread principal.
- **Bug trouvé en route :** `AnimatedImageDrawable.draw()` renvoie 0 ms quand on le dessine moins d'une milliseconde avant l'échéance. Dans ce cas il ne programme plus rien, et le GIF se fige pour de bon. Constaté dans 2 démarrages sur 3, puis 4 sur 6, avec des journaux temporaires. Correction : relancer le rendu 4 ms plus tard. Après correction, **6 démarrages sur 6** animent les GIF. Ce défaut existait aussi dans la version de départ.
- **Autres pièges corrigés :**
  - `Drawable.setCallback` ne garde qu'une référence faible : le callback anonyme était ramassé et le GIF ne s'animait jamais.
  - Le GIF continuait en arrière-plan : il est maintenant lié au cycle de vie, et le thread passe à 0 tick en 10 s en arrière-plan.

### 5.3 Boucle d'écriture des réglages pendant la lecture

- **Lecture du code (trouvé par l'audit, vérifié) :**
  - `QueueSnapshotWriter` réagit à `settingsStore.shuffleEnabled` en programmant une écriture de l'instantané de la file 600 ms plus tard.
  - Cet instantané est écrit dans le **même** DataStore que les réglages.
  - `shuffleEnabled` était un simple `data.map {}`, sans `distinctUntilChanged`. Chaque écriture le faisait donc réémettre, ce qui reprogrammait une écriture.
  - Tant que la musique joue, la position change : c'est une boucle sans fin (encodage JSON de toute la file, `fsync`). Elle réveille au passage la bibliothèque (re-tri) et les widgets de l'Accueil.
- **Mesure (V3, Bibliothèque au repos, lecture active) :** le pool `DefaultDispatcher` passe de **6,7 % d'un cœur (A1 et A2) à 0,1 % (B1 et B2)**.

### 5.4 Attribution du coût du défilement de l'Accueil

Mesure X4 : un élément retiré à la fois par interrupteur temporaire, deux passes par configuration en ordre aller-retour.

| Configuration | fps | gfxJank% | RenderThread p50 |
|---|---|---|---|
| tout actif | 84,1 | 10,1 | 9,0 ms |
| sans verre | 83,2 | 5,3 | 6,3 ms |
| sans GIF | 86,8 | 2,2 | 8,8 ms |
| sans pochettes | 75,5 | 8,6 | 8,6 ms |
| sans dégradés ni contours | 71,8 | 12,9 | 8,5 ms |
| tout retiré | 88,6 | 0,2 | 5,0 ms |

Les deux postes mesurables sont **le verre** et **les GIF**. Dégradés, contours et pochettes ne pèsent pas de façon mesurable.

Une trace détaillée montre aussi que **la capsule, cachée, rendait encore ses calques de verre à chaque image**. C'était le cas sur l'Accueil, et aussi sous le lecteur, les paroles et la file (1 648 images sur 1 648 en « lecteur au repos »). HWUI rend les calques à effet même sous un parent à alpha 0.

### 5.5 Autres constats corrigés

- **Avatar de profil :** décodé à la taille de la photo pour un rond de 36 dp (audit). Sur une photo d'appareil, cela fait des dizaines de Mo de bitmap et autant de texture. Correctif sans risque visuel ; gain non mesuré séparément.
- **Analyse Automix (`bitchord-smart-analysis`) :** un cœur entier (96 %) pendant la recherche en A1, en concurrence avec l'interface. Elle passe en priorité d'arrière-plan.
- **Scan de la bibliothèque :** le regroupement des albums et le choix des pochettes tournaient sur le thread principal juste après le démarrage. Déplacés sur `Dispatchers.Default`. Gain non mesurable sur `am start -W` (le scan se termine après le premier affichage).

### 5.6 Premier frame à l'ouverture du lecteur

- **Mesure :** à chaque ouverture, A et B présentent un frame de ~32 ms (médiane sur 6 ouvertures). On y trouve ~21 ms de « Record View#draw » sur le thread principal, et presque pas d'attente du RenderThread (`postAndWait` ~0,7 ms).
- **Hypothèse testée :** chaque panneau de verre neuf (six dans le lecteur) recompilait ses shaders AGSL (lentille, reflet) au premier dessin, car le cache était propre à chaque panneau. Hypothèse rejetée par la mesure (§7).

## 6. Correctifs conservés

1. GIF rendus au rythme du GIF, hors du thread principal, seulement visibles et au premier plan (§5.1 et §5.2).
2. Réglages qui n'émettent que sur changement, plus l'ordre des sources (§5.3).
3. Capsule non dessinée quand elle est entièrement cachée : sur l'Accueil (`TabCapsule.kt`), et sous le lecteur, les paroles et la file (`MainLayout.kt`, `capsuleAlpha()`).
4. Avatar décodé à 256 px maximum.
5. Analyse Automix en priorité d'arrière-plan.
6. Scan de la bibliothèque : regroupement hors du thread principal.

## 7. Hypothèses rejetées et patches retirés

- **GIF animé par le RenderThread (G1) :** le repos s'améliore mais le défilement se dégrade (§5.2). Retiré.
- **Page hors écran du pager encore rendue :** testé en ne dessinant pas les pages hors écran. Aucun gain (84,3 / 84,6 sans le changement, 81,5 / 75,2 avec). Retiré, `PageMotion.kt` est revenu à l'identique.
- **Dégradés, contours et pochettes coûteux sur l'Accueil :** rejeté par l'attribution (§5.4).
- **Shaders du verre compilés à chaque ouverture du lecteur :** testé avec un cache de shaders commun à toute l'app (B3), en A/B B2-B3-B3-B2 sur l'ouverture et la fermeture du lecteur. Le premier frame reste à 34–35 ms, dont 22,5 à 23,4 ms d'enregistrement, contre 31–35 ms et 20,2 à 21,2 ms sans le cache, soit dans le bruit. Le patch touchait la bibliothèque du verre sans gain démontré : **retiré**. La build finale est identique octet pour octet à B2 (md5 `e800554e…`).
- **Première attribution (X1 à X3) invalide :** les interrupteurs, passés par un fichier, n'étaient pas lisibles par l'app. Ces passes comparaient donc la même configuration. Elles ont servi de mesure du bruit : **72 à 86 images/s pour la même build** sur le défilement de l'Accueil.
- **Deux validations A/B interrompues (V1 et V2) :** l'état de lecture n'était pas maîtrisé (un tap destiné à la capsule cachée lançait un titre). Un écran d'album ouvert était pris pour une page racine, et son aurore animée faussait la mesure. Les scripts ont été corrigés : tap par libellé, état de lecture imposé, repère de page racine fiable. V3 est la validation retenue.

## 8. Mesures avant / après (validation V3)

- **Builds comparées :** A = build de départ (md5 `ece972a7…`), B = build finale (md5 `e800554e…`).
- **Protocole :** ordre A1, B1, B2, A2. Pour chaque passe : installation, compilation `speed`, même préchauffage, même titre, lecture active, son coupé.
- **Valeurs :** moyennes des deux passes de chaque côté. Les passes individuelles sont dans `docs/perf/2026-10-09/V3_compare.txt` et le CSV dans `V3_compare.csv`.

| Parcours | Conditions | Avant (A) | Après (B) | P95/P99 intervalle A → B (ms) | gfxJank% A → B | Régression éventuelle | Verdict |
|---|---|---|---|---|---|---|---|
| Accueil au repos (GIF visibles) | 10 s immobile | 1 517 img, CPU 182 % | 345 img (rythme des GIF), CPU 54 % | — | 0,29 → 10,9 ¹ | non | **amélioré** |
| Bibliothèque au repos | 10 s immobile | 1 596 img, CPU 188 % | **0 img**, CPU 9 % | — | 0,75 → 0 | non | **conforme** |
| Arrière-plan → premier plan ×4 | | 1 808 img, CPU 100 % | 4 img (1 par retour), CPU 8 % | — | 0,45 → 100 ² | non | **amélioré** |
| Défilement Accueil | 2×(3 haut, 2 bas) | 90,6 img/s ³, long 2,8 %, CPU 162 % | 76,7 img/s (84,7 / 68,6), long 9,6 %, CPU 72 % | 14,6/17,5 → 30,7/49,0 | 0,03 → 4,7 | **oui** | **sous le seuil (80)** |
| Défilement Titres | 5 haut, 2 bas | 90,3 ³, long 4,5 %, CPU 162 % | 88,3, long 2,9 %, CPU 61 % | 15,4/19,3 → 14,3/26,6 | 0,14 → 0,16 | p99 plus haut | **conforme (≥ 85)** |
| Défilement Albums | 5 haut, 2 bas | 90,5 ³, long 3,1 %, CPU 161 % | 88,2, long 4,2 %, CPU 63 % | 14,4/18,3 → 15,0/27,1 | 3,21 → 1,33 | p99 plus haut | **conforme (≥ 85)** |
| Lecteur ouvrir/fermer ×6 | depuis la Bibliothèque | 90,0 ³, long 4,5 %, CPU 110 % | 88,2, long 5,2 %, CPU 59 % | 15,2/23,7 → 15,8/39,7 | 0,81 → 1,68 | **1er frame plus visible** | **conforme en moyenne, à-coup d'ouverture restant** |
| File « À suivre » ×4 | depuis le lecteur | 89,9, long 5,9 %, CPU 56 % | 88,7, long 4,9 %, CPU 34 % | 15,9/21,8 → 15,4/25,1 | 2,68 → 2,32 | non | **conforme** |
| Changement de piste ×6 | lecteur ouvert | 85,0, long 13,7 %, CPU 142 % | 84,8, long 10,3 %, CPU 42 % | 19,9/39,3 → 19,8/39,5 | 4,86 → 3,09 | non | **amélioré, ~85** |
| Recherche, saisie rapide | depuis la Bibliothèque | 86,2, long 12,6 %, CPU 164 % | 89,0, long 8,9 %, CPU 34 % | 18,7/27,4 → 17,7/29,9 | 4,21 → 1,92 | non | **amélioré** |
| Lecteur ouvert au repos | 10 s, lecture | 948 img, CPU 68 % | 210 img, CPU 26 % | 16,4/20,9 → 16,0/29,9 | 1,44 → 1,90 | non | **amélioré** |
| Paroles | | — | — | — | — | — | **non mesuré** ⁴ |

¹ Les images de GIF arrivent isolées (~17/s), CPU et GPU au repos : HWUI les compte « en retard » sans à-coup visible, puisque l'image du GIF change à son propre rythme.
² Une seule image par retour au premier plan, forcément « en retard » au réveil. A en dessinait ~450 par retour.
³ Les « img/s » de A sont gonflés par le rendu permanent (§4).
⁴ Le titre de test commence par une intro instrumentale ; le bandeau « • • • » ne s'ouvre pas au toucher (voir §11).

**Démarrage** (`am start -W`, 12 mesures par côté) :
- à froid : médiane **438 → 434 ms** ;
- à chaud : **59 → 56 ms**.

Inchangé dans le bruit.

## 9. Tests fonctionnels, visuels et audio

- **Tests unitaires :** 127/127 avant et après.
- **GIF :**
  - 6 démarrages sur 6 animés ;
  - pause hors écran, sur la Bibliothèque et lecteur ouvert (0 image) ;
  - reprise au retour ;
  - arrêt complet en arrière-plan (thread `aura-gif` à 0 tick en 10 s).
  - Même recadrage et même filtrage qu'avant (captures `shots/g*`, `shots/d5b_*`).
- **Captures de la build finale** (`nuit-2026-10-09/shots/final_*`) : lecteur, paroles, lecteur au retour des paroles (pas de flash opaque, fond et verre normaux), feuille « À suivre », recherche avec page floutée. Aucune différence visible avec la build de départ.
- **Audio :** lecture active pendant tous les parcours (`media_session` en PLAYING), **0 underrun** dans les journaux pendant la boucle complète.
- **Téléchargements :** les scripts n'utilisent aucune action de téléchargement. La recherche interroge les sources en ligne activées, sans rien télécharger. Je n'ai pas revérifié la liste « Titres téléchargés ».
- **Navigation :**
  - Pendant les parcours mesurés de V3 et S1, aucune sortie de l'app.
  - Pendant le préchauffage, le script est revenu au lanceur à chaque passe : un tap sur les paroles avait raté, puis le retour a fermé le lecteur et l'app. Il a ensuite rouvert Aura QA par `am start`.
  - Aucune autre app n'a été ouverte, aucun réglage système n'a été modifié.
- **Mémoire** (PSS, Mo ; deux boucles identiques : lecteur ×10, défilements Accueil, paroles ×3, file, recherche) :

  | | départ | après boucle 1 | après boucle 2 | dont « Graphics » (départ → fin) |
  |---|---|---|---|---|
  | A (départ) | 285 | 444 | 406 | 110 → 172 |
  | B (finale) | 290 | 433 | 388 | 106 → 160 |

  Même profil des deux côtés (caches qui se remplissent puis GC), B légèrement plus bas. Pas de signe de fuite.

## 10. État de l'objectif 80–85 images/s (96 Hz)

| Statut | Parcours |
|---|---|
| **Conformes (≥ 85 img/s affichées dans les animations)** | défilement Titres (88,3), défilement Albums (88,2), lecteur ouvrir/fermer (88,2 en moyenne), file d'attente (88,7), recherche (89,0) ; et lecteur ouvert au repos (88,8) |
| **À la limite (80–85)** | changement de piste (84,8) |
| **Sous le seuil** | défilement de l'Accueil (76,7 : 84,7 et 68,6 sur deux passes) |
| **Non mesurés** | paroles (§11), démarrage en images/s (seuls les temps `am start` sont mesurés) |
| **Écrans statiques** | plus aucune image produite sans raison (0 sur la Bibliothèque, uniquement le rythme des GIF sur l'Accueil) |

- **Moyennes et à-coups :** même quand la moyenne dépasse 85, il reste des à-coups ponctuels (p99 de 25 à 40 ms). Les principaux sont le premier frame de chaque ouverture du lecteur (~32 ms) et les débuts de geste.
- **Rapprochement des 96 Hz :** le plafond pratique se situe vers 88–90 img/s. Le RenderThread prend 5 à 8 ms par image sur ce SoC (2 × A76 à 2,2 GHz, 6 × A55, GPU Mali) : la marge sous les 10,4 ms est faible.

## 11. Blocages et problèmes restants

1. **Défilement de l'Accueil sous 80 img/s.**
   - **Cause mesurée :** le verre de la mini-pilule et les GIF (§5.4). Le reste du coût est fixe. Sans le rendu permanent de la build de départ, CPU et GPU ne restent plus à haute fréquence : les débuts de geste coûtent plus cher (26 % des cinq premiers intervalles longs contre ~8 % ensuite).
   - **Pistes non faites, car elles changent le rendu ou demandent ton accord :**
     - verre de la pilule à plus basse résolution ;
     - GIF mis en pause pendant le défilement ;
     - Accueil passé en liste paresseuse (`LazyColumn`, refonte).
   - Je n'ai rien activé de tout cela.
2. **Premier frame à l'ouverture du lecteur (~32 ms, dont ~21 ms d'enregistrement du dessin).**
   - **Cause probable :** le coût de la première composition et du premier dessin de tout l'écran lecteur.
   - **Pistes rejetées :** les shaders du verre (§7). Les sessions précédentes avaient déjà écarté plusieurs pistes.
   - **Piste restante :** pré-composer le lecteur sous la mini-pilule. C'est un changement d'architecture, non fait sans ton accord.
3. **Recherche :** la page floutée derrière le panneau est re-floutée en plein écran à chaque image (un calque 1080×1240 avec `BlurEffect`). Pour mettre ce flou en cache, il faudrait figer une capture de la page, avec un risque de contenu périmé. Non fait.
4. **Paroles non mesurées.**
   - Le parcours a besoin d'une vraie ligne de paroles sous le doigt.
   - Le titre de test commence par une intro instrumentale, et son bandeau « • • • » ne s'ouvre pas au toucher.
   - Le script `scripts/perf/night/lyrics_ab.sh`, qui attend une vraie ligne avant de mesurer, est prêt mais n'a pas été lancé.
   - La vérification visuelle des paroles et du retour au lecteur a bien été faite (§9).
5. **Instrumentation :** le préchauffage des scripts sort encore de l'app une fois par passe (§9). Sans effet sur les mesures, mais à corriger si les scripts doivent resservir.
6. **Variabilité :** pour une même build, le défilement de l'Accueil varie de 68 à 86 img/s d'une passe à l'autre. Les conclusions sur ce parcours demandent plus de répétitions que les deux de V3.

**Effets de bord sur les données QA** (pas sur ton app du quotidien) :
- Le titre en cours et la file QA ont changé (« -FINAL SIGMA- » lancé depuis la liste des titres).
- Des compteurs d'écoute et les « Récents » de QA ont augmenté pendant les parcours « changement de piste ».
- Les réglages QA (AMOLED, paroles en ligne, Automix) n'ont pas été touchés.
- La lecture est remise en pause.

## 12. Commandes pour reconstruire et reproduire

```bash
cd ~/AndroidStudioProjects/copilot-worktrees/Aura/dooorians-shiny-giggle
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleQa            # build finale : app/build/outputs/apk/qa/app-qa.apk (md5 e800554e…)
./gradlew :app:testDebugUnitTest     # 127 tests
adb -s 192.168.50.130:5555 install -r app/build/outputs/apk/qa/app-qa.apk
adb -s 192.168.50.130:5555 shell cmd package compile -m speed -f com.ikko.aura.qa
```

- **Une passe complète** sur la build installée : 13 parcours, traces Perfetto, `gfxinfo`, CPU par thread.
  ```bash
  bash scripts/perf/night/suite.sh LABEL
  ```
- **Validation A/B équilibrée :**
  ```bash
  bash scripts/perf/night/abba.sh TAG apkA.apk apkB.apk
  python3 scripts/perf/night/compare.py <dossier raw> TAG
  python3 scripts/perf/night/starts.py <dossier raw> TAG
  ```
- **Premier frame des ouvertures du lecteur :** `python3 scripts/perf/night/firstframe.py traces/*_player_oc.pftrace`
- **Mémoire :** `bash scripts/perf/night/memloop.sh LABEL`
- **Paroles seules :** `bash scripts/perf/night/lyrics_ab.sh TAG apkA.apk apkB.apk`

**Où sont les fichiers :**
- Dans le dépôt : `docs/perf/2026-10-09/` (CSV et tableau V3).
- Données brutes, traces et APK : `~/Documents/Claude/Projects/app/nuit-2026-10-09/`.
  - `apk/qa_installed_before_session.apk` : build de départ, pour revenir en arrière.
  - `apk/B2.apk` : build finale, installée sur l'appareil.
  - `patch/*.diff` : mes changements seuls.
  - `traces/` : traces Perfetto, 5,7 Go.

---

**Verdict : amélioration mesurée, objectifs partiellement atteints.**
- **Atteint :** fin du rendu permanent, CPU divisé par 2 à 5, boucle d'écriture supprimée, et 5 parcours animés sur 7 mesurés au-dessus de 85 img/s (le changement de piste est à 84,8).
- **Pas encore :** le défilement de l'Accueil (sous 80) et l'à-coup d'ouverture du lecteur.
- **Non mesuré :** les paroles.

## 13. Complément : ouverture et fermeture du lecteur (après-midi du 9 octobre)

Demande : fluidité de l'ouverture et de la fermeture du lecteur, objectif 85–90 images/s. Build installée : `apk/K_final.apk` (md5 `7ce73455…`), compilée en `speed`.

### Ce qui a été mesuré
- **L'animation elle-même est déjà à ~89 images/s** sur 6 allers-retours : ouverture/fermeture de 4 builds différentes entre 88,8 et 89,6 img/s, 3 à 4 % d'intervalles longs (14–15 ms), sans image de plus de 20 ms pendant le mouvement.
- **Les deux seuls vrais à-coups sont avant ou après le mouvement :**
  1. **Ouverture :** une image de ~37 ms (composition du lecteur, 10 ms, puis première mise en page, 19 ms) et ~110 ms d'images à 7–8 ms de rendu, pendant que la carte est encore invisible (1 % d'opacité). Le lecteur est préparé 40 ms après le début de l'appui. Ça tombe sur l'animation d'appui de la mini-pilule, pas sur la carte.
  2. **Fermeture :** à chaque fermeture, une image de **28 à 37 ms**, ~20 ms après le relâchement du doigt. La page sous le lecteur est reconstruite (elle est détruite 800 ms après l'ouverture, `SHEET_HOLD_MS`). Profil CPU : 41 % composition, 32 % mise en page (la grille Titres et ses textes), 21 % dessin. La carte ne démarre qu'après.
- **Fréquences CPU :** pendant l'animation, les gros cœurs passent de 725 à 2 200 MHz d'une image à l'autre. Le seul réglage du système qui s'y rapporterait (ADPF) n'existe pas sur cet appareil. Pas de levier côté app.
- **Compilation :** un `adb install` ou « Run » depuis Android Studio laisse l'app en état `verify` (pas compilée) jusqu'à la tâche de nuit du système. Sur 2 démarrages à froid, la première ouverture est un peu pire (à-coups de 52–54 ms contre 40–45 ms). `scripts/install-qa.sh` installe puis compile en `speed`.

### Correctifs gardés
1. **Fermeture déclenchée dès que le geste vers le bas a parcouru 56 dp, pas au relâchement** (`NowPlayingScreen.kt`). Le même geste ferme le lecteur comme avant (balayage latéral, ouverture de la file et lecture sans changement). Mesuré avec un balayage de 350 ms, 6 fermetures par build :

   | | E1 (avant) | J1 (après) |
   |---|---|---|
   | Image lourde (28–37 ms) | +21 ms **après** le relâchement, dans la fermeture | **230 ms avant** le relâchement, pendant que le doigt glisse et que l'écran est immobile |
   | Fin des images de la fermeture, après le relâchement | +1 260 ms | **+1 017 ms** (−245 ms) |

   Ouverture/fermeture standard, avant → après : 88,9 → 89,0 img/s (lecteur) et 90,3 → 90,2 (file) : pas de régression.
2. **Marge basse des listes de la Bibliothèque** (`LibraryScreen.kt`) : elle ne dépend plus de l'état d'animation de la capsule, mais seulement de son masquage par le défilement. Aucune différence mesurée sur l'ouverture, mais ça évite de relancer la mise en page de toutes les listes à chaque ouverture et fermeture du lecteur (`capsule.shown` change à ce moment-là).
3. **`scripts/install-qa.sh`** : installation + compilation `speed`.

### Pistes testées et rejetées
- **Ne jamais figer la page sous la carte** (supprimer l'instantané `pageSnapshot`) : 89,2 → 89,4 img/s, premier frame 29,2 → 28,3 ms. Aucun gain.
- **Attente de la page sous la carte plus longue** (`SHEET_HOLD_MS` = 60 s, pour ne plus reconstruire la page) : l'image lourde disparaît bien, mais la mesure passe à 82,6 img/s et 9 % d'intervalles longs, et le lecteur fermé reste en mémoire aussi longtemps qu'il a été ouvert (voir la note `navhost_hold_reversal`). Non gardé.
- **Cache de shaders du verre** : déjà décrit (§7).

### Ce qui reste
- L'image de ~37 ms à l'ouverture (composition + première mise en page du lecteur, au moment de l'appui). Pour la supprimer il faut sortir le lecteur du NavHost ou le composer d'avance, ce qui change l'architecture.
- Les intervalles de 14–15 ms isolés pendant le mouvement (3–4 % des images) viennent des variations de fréquence du processeur et de la composition de SurfaceFlinger. Je n'ai pas de levier côté app.
