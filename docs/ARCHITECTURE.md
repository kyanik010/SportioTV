# docs/ARCHITECTURE.md — Module und Entscheidungen (Stand: 2026-10-19)

> Zweck: Kurzüberblick, wo was liegt und welche Fork-Entscheidungen gelten.
> Details: Sport-Hub in `SPORT-HUB.md`, Branding in `BRANDING.md`, i18n in `I18N.md`.

## Repos und Ordner

- Repo-Root = diese App (Sportio-Fork), eigenes Repo
  `https://github.com/trenntl/SportioTV`. Lokale Sicherungen
  `backup/pre-migration`, `backup/handoff-fa451b2b`.
- `OwnTV_Core-main/` — Core-Fork, eigenes Repo
  `https://github.com/trenntl/SportioTV_Core`, Stand Tag `core-1.0.70` —
  identisch zum App-Pin `owntvCore` in `gradle/libs.versions.toml:82`.
  Composite-Build via `owntv.corePath`, daher kein Publish nötig.
- Upstream-Referenz: `ahXN00/OwnTV` (App), `ahXN00/OwnTV_Core` (Core),
  `ahXN00/OwnTV_Mobile` (Phone-App auf demselben Core).

## App-Module (`app/src/main/java/tv/own/owntv/`)

- `player/` — TV-Player-HUD, Surfaces, Mini-Player (treibt `:player-core`).
- `ui/` — Theme + wiederverwendbare Komponenten (`OwnTV*`-Namen historisch, Freeze).
- `features/` — setup, shell, live, movies/series (toter VOD-Code, bewusst belassen),
  search, downloads (aus Navigation entfernt), epg, profiles, settings,
  `sport/` (Sports-Hub: `SportsModels.kt`, `SportsLeagues.kt`, `EspnApi.kt`,
  `SportsMatcher.kt`, `SportNameMatch.kt`, `SportsVocab.kt`, `SportLanguages.kt`,
  `SportsPrefs.kt`, `SportsViewModel.kt`, `SportsScreen.kt`, `SportsAiModels.kt`,
  `DeepSeekClient.kt`, `AiChannelMatcher.kt`).
- `di/AppModule.kt` — Koin (`singleOf EspnApi/SportsMatcher/SportsPrefs/DeepSeekClient/
  AiChannelMatcher`, `viewModelOf SportsViewModel`, `SportsVocab.load`,
  `LiveSourceIdsProvider`-Naht für Tests statt `SettingsRepository`/`SourceRepository`
  direkt im Matcher).

## Core-Fork-Änderungen (`OwnTV_Core-main/`)

1. `core/…/core/nav/MainSection.kt` — `SPORT`, `browseOrder` + `dynamicVisible`.
2. `core/src/main/res/values(+de)/strings.xml` — `common_nav_sport`.
3. `core/…/core/sync/M3uSyncer.kt` — `contentTypes`-Gate (VOD/Serien-Skip + Cleanup).
4. `core/…/core/sync/SyncManager.kt` — übergibt `effective` an M3uSyncer.
5. `core/…/core/launcher/LauncherDeepLink.kt` — Schema `owntv://` → `sportio://`
   (Recording-/Reminder-URIs unverändert).
6. `SettingsRepository.kt` — `updateCheckOnStart` wieder `true` (eigenes Release-Repo).
7. compileSdk 37.2 wie App. 8. `CompanionMode.SELECT_ITEMS` — generischer
   Pick-Listen-Modus für Handy-Verwaltung (`/select`-Route, `companion_select_*`),
   seit 2026-10-13 dreistufig (`CompanionSelectItem.parentId` +
   `CompanionSelectHtml`: Start → Zeile mit Tabs → „Alle verwalten", ein Submit).
9. `EpgDao.searchGuide` — `title LIKE OR description LIKE` im Zeitfenster
   (nur mehrwortige Teamnamen in Beschreibung, `EpgMinTokenLength=4`).
10. `CompanionMode.AI_KEY` — Ein-Feld-Seite für den DeepSeek-Schlüssel des Sport-Hubs
    (`/apikey`, `companion_ai_key_*`), damit der lange Key am Handy statt per
    Fernbedienung eingefügt wird; die Seite füllt nur, gespeichert wird am TV.

## TV-only-Entscheidungen (schnellere Syncs)

- Navigation/Quellen-Dialog ohne Filme/Serien/Downloads; neue Xtream-/Stalker-Quellen
  importieren nur Live-TV (`syncMovies`/`syncSeries = Off`), M3U per Core-Patch Live-only.
- Settings-Cleanup: Film/Serien-Settings entfernt (VOD-Layout, Metadata-Tab,
  Trending-Block, VOD-Engine/Pins/Buffer/Timeouts, Streaming-Sektion, externer Player
  nur Live). Repo-Keys/ViewModels unangetastet (Backup-Kompat).
- Toter VOD-`when`-Code in `OwnTVShell.kt` bewusst belassen.

## Bekannte Fallen

- **Player-Dispose:** `SportsScreen` darf beim Fullscreen-Gehen kein `stopPreview()`
  rufen — die Shell disposed die Browse-UI bei `playerMode == FULLSCREEN`
  (`OwnTVShell.kt`), das killt den gerade gestarteten Sender. Nur bei echtem
  Spiel-Wechsel stoppen (`LaunchedEffect(selectedFixture?.id)` mit Null-Sentinel).
- **Guide-Discovery:** EPG-Pfad (B) läuft immer, unabhängig vom Namens-Cap;
  kein Guard, der B bei vollem Namens-Cap überspringt.
- **Box Component-States:** manche Hersteller behalten pro-Benutzer disabled-States über
  Updates/Reboots (`AppIconSwitcher`) — bei `Error type 3`: deinstallieren +
  frisch installieren, `pm enable` blockt.
- **ESPN-Datum:** `Instant.parse("2026-10-11T13:30Z")` wirft (Sekunden fehlen) —
  `normalizeEspnDate` fügt `:ss` ein.
