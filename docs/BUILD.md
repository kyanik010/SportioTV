# docs/BUILD.md — Bauen, Installieren, Emulator (Stand: 2026-10-13)

> Zweck: alle Befehle für Build, Installation und Emulator an einem Ort.
> Quelle: destilliert aus den Build-/Emulator-Kapiteln der internen Notizen.

## Voraussetzungen (einmalig)

- JDK 17 portable: `C:\Users\<user>\OwnTV\tools-setup\jdk17-portable\jdk-17.0.11+9`
- Android SDK: `C:\Users\<user>\Android\Sdk` (platforms 36 + 37.2, build-tools 36/37, platform-tools, Emulator, TV-Image API 36 x86_64)
- `C:\Users\<user>\.gradle\gradle.properties` enthält:
  `owntv.corePath=C:/Users/<user>/OwnTV/OwnTV_Core-main` (Composite-Build, kein Publish nötig)
- Upstream nutzt `compileSdk 37`; hier DSL-Form `release(37) { minorApiLevel = 2 }`
  (37.2-Plattform aus dem Canary-Channel), weil 2026er-Deps compileSdk ≥ 37 fordern.

## Env (jede PowerShell-Session)

```powershell
$env:JAVA_HOME="C:\Users\<user>\OwnTV\tools-setup\jdk17-portable\jdk-17.0.11+9"
$env:ANDROID_HOME="C:\Users\<user>\Android\Sdk"; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
```

## Repos (Stand: 2026-10-13, verifiziert per GitHub-API gegen `main`)

- Ordner-Root (Repo-Klon) ↔ `https://github.com/trenntl/SportioTV`
  (öffentlicher Fork, Branch `main`). Alle Remote-Top-Level-Einträge sind lokal vorhanden;
  lokal zusätzlich nur Git-ignoriertes (`OwnTV_Core-main/`, `tools-setup/`, `*.apk`, `*.zip`,
  `build/`, `local.properties`, `keystore/`) plus lokale Doku (`HANDOFF.md`).
- `OwnTV_Core-main/` ↔ `https://github.com/trenntl/SportioTV_Core` (eigener Repo,
  Branch `main`). Lokal ist ein reiner Source-Stand (ohne `.github/`, `docs/`, `AGENTS.md`);
  per `.gitignore` bewusst **nicht** Teil des App-Repos (Composite-Build über `owntv.corePath`).
- Git liegt portable unter `tools-setup/git-portable/` (MinGit, gitignored).
  Init/Remote/Push laufen damit in PowerShell; Auth nur per
  `-c http.extraHeader="Authorization: Basic <base64(x-access-token:TOKEN)>"`, nie Token auf
  Platte oder in URLs (Verbot in `../AGENTS.md` §6). Repo-lokale Identity ist
  Noreply (`git config user.email ...@users.noreply.github.com`).

## Builds

```powershell
.\gradlew.bat :app:assembleStandardDebug   # ARM: echter Fire TV / Xiaomi / Android TV
.\gradlew.bat :app:assembleX86_64Debug     # Emulator (PC)
.\gradlew.bat :app:assembleStandardRelease # signiert, falls Keystore konfiguriert
```

- APKs: `app/build/outputs/apk/{standard,x86_64}/debug/*.apk`.
- Debug = versionCode 99999 / versionName 99.99.99 (installiert über alles drüber).
- Release-Signing: Keystore `keystore/sportio-release.jks` (Alias `sportio`), nie committen.
  Details in `RELEASE.md`.
- Gates vor jedem Build grün halten, siehe `../AGENTS.md` §5.
- Tests: `.\gradlew.bat :app:testStandardDebugUnitTest` (App-Repo) sowie
  `.\gradlew.bat :core:testDebugUnitTest :player-core:testDebugUnitTest` (Core-Repo).
  Bekannt (2026-10-13): `TimeshiftManagerTest` („saves a live stream into keyframe
  pieces…") schlägt im **Gesamtlauf** fehl und ist **isoliert** grün
  (`--tests "tv.own.owntv.core.timeshift.TimeshiftManagerTest"`) — Timing-Test
  (800-ms-Fenster erwartet ≥2 Keyframe-Pieces); auf sauberem HEAD (eigener Worktree)
  reproduziert, also vorbestehend und nicht release-blockierend.
- Emulator-APK-Grenze: das Test-AVD `SportioTV` trägt ein **release-signiertes**
  `tv.sportio.app` (v1.0.2, mit Daten). Ein `adb install -r` des Debug-APKs scheitert
  dort an der **Signatur** (nicht am versionCode), Debug-Tests brauchen also erst eine
  Deinstallation inkl. App-Datenverlust. Ein release-signiertes Gegen-APK ist lokal
  nicht baubar — die Keystore-Zugangsdaten (`owntv.keystore*`) liegen nicht auf der
  Maschine.

## Installation

- Realer TV: `adb install -r <apk>` oder Downloader/USB-Sideload.
- Downgrade-Falle: läuft ein Debug-Build (99.99.99/99999) auf der Box, verweigert
  Android das Release (`INSTALL_FAILED_VERSION_DOWNGRADE`, TV meldet generisch
  „Paket ungültig"). Erst `.own`-Backup ziehen, dann deinstallieren, dann Release
  installieren. Details siehe `RELEASE.md` §Versionierung.
- Start: `adb shell am start -n tv.sportio.app/tv.own.owntv.MainActivity`
  (geht nur bei enablter Activity; nach Icon-Farb-Update ggf. frisch installieren,
  da manche Hersteller pro-Benutzer Component-States über Updates behalten — siehe `ARCHITECTURE.md`).
- Offizielle OwnTV (`tv.own.owntv`) kollidiert NICHT mit Sportio (`tv.sportio.app`),
  aber Upstream-PR-Builds nutzen `tv.own.owntv` und kollidieren mit offiziellem OwnTV
  auf echter Box (Signatur) — dort nicht installieren.

## Emulator (AVD `SportioTV`, Android TV 1080p, API 36, x86_64)

```powershell
C:\Users\<user>\Android\Sdk\emulator\emulator.exe -avd SportioTV -no-snapshot -no-audio -memory 2048
C:\Users\<user>\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\x86_64\debug\app-x86_64-debug.apk
adb shell monkey -p tv.sportio.app -c android.intent.category.LEANBACK_LAUNCHER 1
```

- Boot-Wait: `adb wait-for-device` + `sys.boot_completed`-Poll.
- WHPX aktiv (VT-x im BIOS + HypervisorPlatform-Feature); Check `emulator -accel-check`.
- Screenshots: `screencap -p /sdcard/x.png` + `pull`. D-Pad: `input keyevent`
  (19–23 hoch/runter/links/rechts/OK, 4 = Back, 3 = Home).
- AVD-`config.ini`: `hw.keyboard=yes` (sonst schluckt der Emulator Host-Tasten).
- **Companion-Seite vom PC:** der Emulator hängt hinter NAT, vom Handy ist er nicht zu
  erreichen. Per Port-Forward kommt der PC-Browser ran:
  ```powershell
  adb forward tcp:8089 tcp:8089
  # http://localhost:8089/  → PIN-Gate; der PIN steht auf der TV-Seite „Add a source › Remote"
  ```
  `adb forward --remove tcp:8089` macht es rückgängig. Der PIN ist pro Sitzung neu („New PIN"
  auf der TV-Seite startet den Server mit einem neuen). Ohne den Forward bleibt die Seite auf
  `http://10.0.2.15:8089/` (nur aus dem Emulator heraus erreichbar).

## TV-Box (Android TV)

- Box-IP als `<box-ip>` einsetzen; Ports wechseln bei jedem Reboot — nie cachen,
  immer neu per `adb mdns services` suchen (`_adb-tls-pairing._tcp`,
  `_adb-tls-connect._tcp`, `_adb._tcp` auf 5555).
- Koppeln: `adb pair <box-ip>:<pairing-port> <code>`, danach `adb devices`.
- Kopplungs-Port ≠ Verbindungs-Port: Der Kopplungs-Dialog zeigt Code +
  Kopplungs-Port; der Verbindungs-Port steht separat auf der
  Drahtlos-Debugging-Übersicht (`adb connect <box-ip>:<verbindungs-port>`).
- mDNS kann leer bleiben (Netz-Isolation), obwohl Koppeln per IP+Port geht —
  dann Ports vom TV ablesen statt suchen.
- Drahtlos-Debugging schaltet sich beim Box-Reboot ggf. ab → User schaltet es
  am TV wieder ein, Pairing neu.
