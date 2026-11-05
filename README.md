# Aura

Lecteur de musique pour Android (Kotlin, Jetpack Compose, Media3) : ta bibliothèque locale, des sources en ligne, et des recommandations calculées sur l'appareil à partir de ce que tu écoutes.

## Sources de données

Les recommandations, les rangées de l'accueil et les ambiances (Chill, Nuit, Ça bouge !) s'appuient sur des services tiers. Merci à eux :

- [GetSongBPM](https://getsongbpm.com) — tempo (BPM) et caractéristiques des morceaux
- [MusicBrainz](https://musicbrainz.org) — identité des artistes et des albums
- [Deezer](https://developers.deezer.com) — pochettes, discographies, classements par genre, extraits de 30 secondes
- [Last.fm](https://www.last.fm) — tags et artistes proches

## Confidentialité

Le profil de goût, l'historique d'écoute et les mesures restent sur l'appareil. Seuls des noms d'artistes et de titres sont envoyés aux services ci-dessus, et seulement si les recommandations personnalisées sont activées dans les réglages.
