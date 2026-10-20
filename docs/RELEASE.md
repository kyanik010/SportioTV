# docs/RELEASE.md — Signing, Versionierung, Updates (Stand: 2026-10-20)

> Zweck: alles für signierte Releases und In-App-Updates an einem Ort.

## Signing

- Keystore: `keystore/sportio-release.jks` (Alias `sportio`), nie committen
  (`.gitignore`: `*.jks`/`*.keystore`). Backup extern halten.
- Lokale Credentials nur in `C:\Users\<user>\.gradle\gradle.properties`
  oder per Env (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
- Build: `.\gradlew.bat :app:assembleRelease` (arm ~53,9 MB / x86_64 ~37,7 MB,
  signiert CN=Sportio).
- Repo-Secrets (App): KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD. Core: CONSUMER_BUMP_TOKEN (Contents + Pull requests auf beiden Repos).

## Versionierung

- CI injiziert `VERSION_CODE`/`VERSION_NAME` aus dem Git-Tag
  (`.github/workflows/android.yml`).
- Lokale Fallbacks: versionCode 99999 / versionName 99.99.99 — Debug-APKs sind
  damit immer „neuer" als Releases (kein Downgrade-Fehler, kein Update-Angebot).
  Kehrseite (§Installation in `BUILD.md`): ein Release installiert NICHT über
  einen Debug-Build — erst deinstallieren (Daten vorher als `.own` sichern).
- Core-Pin: `gradle/libs.versions.toml` → `owntvCore` (Stand: 1.0.71; App v1.0.8.
  Lokal ignoriert bei gesetztem `owntv.corePath`). Core-Änderung (z. B. `EpgDao`)
  braucht Core-Bump + App-Pin-Mitziehen.
- App-Releases: `trenntl/SportioTV` (aktuell Tag `v1.0.8`), Core-Releases:
  `trenntl/SportioTV_Core` (aktuell Tag `core-1.0.71`).

## Release-Ablauf (gesichert 2026-10-13, Kette v1.0.2 → v1.0.3)

Reihenfolge ist Pflicht — die App baut gegen das gepinnte Core-Artefakt:

1. Core: `coreVersion` in `build.gradle.kts` bumpen + `## core-X.Y.Z`-Abschnitt
   in `CHANGELOG.md` + Code. Tag `core-X.Y.Z` setzen → `publish.yml` gatet
   (Tests, Lint, i18n), publisht nach Maven, legt den GitHub-Release an.
2. Warten, bis `.../maven/tv/own/owntv/core/maven-metadata.xml` die Version listet.
3. App: Pin in `gradle/libs.versions.toml` mitziehen + `## vX.Y.Z`-Abschnitt in
   `CHANGELOG.md` (CI extrahiert ihn als Release-Notes). Tag `vX.Y.Z` setzen →
   CI baut signiert und publisht `Sportio-vX.Y.Z.apk`, `Sportio.apk`, x86_64.
4. Die Tag-CI ist der erzwingende Gate — lokale Gates vorher grün halten
   (`AGENTS.md` §5), aber erst CI entscheidet über den Release.

## Fork-Dateien schützen (Lesson 2026-10-13)

Ein Working-State-Push aus diesem Ordner hat Fork-Anpassungen mit Upstream-Stand
überschrieben (u. a. Maven-Repos, `releaseRepo`, Publish-Workflows) und zwei
Core-Releases rot gemacht. Vor jedem Push diese Dateien gegen Fork-`main` diffen
und Fork-Seiten behalten:

- App: `settings.gradle.kts` (Sportio-Maven!), `.github/workflows/android.yml`
  (Sportio-Naming!), `automerge-core-bump.yml` / `pr-checklist.yml` (Bot-Login
  `trenntl`), `CoreBuildInfo.releaseRepo` (Core, Updater-Ziel!).
- Core: `publish.yml` / `bump-consumers.yml` / `tools/publish_pages.py`
  (publisht sonst ins Upstream-Repo), `community.json` + i18n-Skripte
  (Generator-Output!), `SupportedLocales.kt` + README-Marker (Freshness-Gate).
- Reine Kommentar-Diffs (`I18N_RUNTIME.md`- vs `internationalization.md`-Refs)
  sind harmlos; `docs/` beeinflusst CI nicht.

## API-Push-Fallen (Lesson 2026-10-13)

- Kein UTF-8-BOM in gepushten Dateien — der JSON-Parser (`community.json`)
  scheitert daran. Bytes vor dem Push prüfen (erstes Trio `EF BB BF`).
- Contents-API braucht pro Datei den aktuellen Blob-SHA; ein Tag per
  `POST /git/refs` löst CI aus (PAT, nie `GITHUB_TOKEN` — dessen Events starten
  keine Workflows). Retag = Tag-Ref löschen + neu setzen.
- `CONSUMER_BUMP_TOKEN` (Core-Repo-Secret) braucht Contents **und** Pull-requests
  read/write auf `SportioTV`, sonst scheitert der Bot-Pin-Bump beim PR-Anlegen.
- Token nie auf Platte, nie in URLs/Logs (`AGENTS.md` §6).

## In-App-Updates

- `CoreBuildInfo.releaseRepo = "trenntl/SportioTV"` (`OwnTVApp.kt`),
  `updateCheckOnStart = true` (Core `SettingsRepository.kt`).
- Release-Artefakt: `Sportio.apk` (arm). `Sportio.apk` im Repo-Root ist nur ein
  lokales Build-Produkt und wird nicht committet (siehe `.gitignore`).
- Upstream-PRs (`ahXN00/OwnTV#254`, `ahXN00/OwnTV_Core#30`, Account
  `trenntl`) laufen aus separaten Klonen unter
  `C:\Users\<user>\OwnTV-upstream\`, nicht aus diesem Ordner. PR-Template-Boxen
  exakt einhalten (Needles `checked the app's **Settings**`,
  `searched existing issues/PRs`, `[x]` mit Apostroph).

## Repo-Umzug (2026-10-13)

- Historie per Squash (1 Noreply-Commit) ins neue Repo übernommen.
- Das Vorgänger-Repo trug nur eine Bridge v1.0.4 (reiner `releaseRepo`-Flip) für alte Installationen;
  das neue Repo startet bei v1.0.5 (Code monoton!). Gleiches Keystore -> nahtlose Updates.
- Neues Repo braucht: Secrets (siehe Signing), gh-pages-Branch (sonst scheitert der Maven-Push!),
  Fork-Actions-Banner im Actions-Tab (sonst 0 Runs).
- Alte Repos: archivieren, später löschen (erst das entfernt die alte Historie).
