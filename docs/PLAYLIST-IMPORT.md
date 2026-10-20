# docs/PLAYLIST-IMPORT.md — Playlist aus Provider-Nachricht importieren (Stand: 2026-10-20)

> Zweck: Eine eingefügte Provider-Nachricht (URL, Nutzer, Passwort) wird **KI-zuerst** ausgelesen,
> am Handy über die Companion-Seite bestätigt und danach als ganz normale Quelle angelegt.

## Status

- Plan beschlossen 2026-10-19 (Entscheidungen unten). Umsetzung phasenweise.
- **P0 fertig:** lokaler Parser `SourceMessageParser` + Unit-Tests (App).
- **P1 fertig:** Analyse-Hook im Core (`CompanionController.sourceAnalyzer`), Endpoint
  `POST /source/analyze`, DeepSeek-Aufruf + JSON-Parser + Merge im App, Tests in beiden Repos.
- **P2 fertig:** Companion-Seite mit Reiter „Aus Nachricht“, Textfeld, Einwilligung, Bestätigungs-
  seite, URL-Auswahl, Typ-Umschalter, Core-Strings (EN + de) + Tests im Core.
- **P3 fertig:** Kein-Schlüssel-Hinweis (der KI-Zustand im Ergebnis + vier Hinweise auf der Seite),
  `docs/SPORT-AI.md`-Verweis, Gates.

## Entscheidungen (User, 2026-10-19)

1. **Ort: Companion-Seite am Handy.** Die Nachricht liegt dort (Chat) und Paste ist am TV nicht
   verlässlich. Der TV-Pfad danach ist unverändert.
2. **KI zuerst.** DeepSeek liest die Nachricht primär aus. Ein lokaler Regelscan läuft als
   Vorwärmung und als Notpfad, wenn Schlüssel, Netz oder Guthaben fehlen.
3. **Eine Playlist pro Vorgang.** Mehrere URLs in einer Nachricht → Auswahlliste, genau eine wird
   angelegt.

## Ablauf

1. Einstellungen → Playlists → *Add a source* → *From your phone* (bestehender Companion-Server,
   PIN + QR).
2. Neuer Reiter **Aus Nachricht einfügen**: Textfeld, Nachricht einfügen, **Analysieren**.
3. Die Analyse läuft am TV (dort liegt der DeepSeek-Schlüssel, siehe unten).
4. Ergebnis-Seite am Handy: Typ, Server/Portal, Nutzer, Passwort vorbelegt und **änderbar**; bei
   mehreren URLs eine **Auswahlliste**; MAC-Feld, wenn die Nachricht eine MAC enthielt.
5. „Übernehmen" postet den bestehenden `CompanionPayload` an den bestehenden `/source`-Endpoint.
   Der TV füllt wie heute das Add-Source-Formular vor; der Nutzer drückt **Start Import**.
   „Test connection" steht dort schon zur Verfügung.

## Parsing-Regeln (P0, lokal)

`SourceMessageParser.parse(message, preferredUrl = null)` ist rein (kein Android, kein Netz) und
liefert `SourceMessageDraft`:

| Feld | Inhalt |
|---|---|
| `urls` | alle gefundenen Kandidaten-URLs, Reihenfolge des Auftretens, dedupliziert |
| `url` | die URL, auf die sich die Felder beziehen (oder null) |
| `type` | `STALKER` (MAC gefunden), `M3U` (`.m3u`/`.m3u8` ohne Zugangsdaten), sonst `XTREAM` |
| `server` | Xtream-Basis, M3U-URL oder Portal-URL |
| `username`, `password`, `mac` | gelesene Zugangsdaten, MAC kanonisch (`AA:BB:CC:DD:EE:FF`) |
| `confidence` | `HIGH` (Query-Zugangsdaten, vollständige Label oder MAC+URL), `GUESS` (Position), `NONE` |

Regeln in Anwendungsreihenfolge:

1. **Normalisieren:** geschützte Leerzeichen → Leerzeichen, CRLF/CR → LF.
2. **URL-Kandidaten:** `http(s)://…` bis zum nächsten Trennzeichen; abschließende
   Satzzeichen werden entfernt. Zusätzlich reine `host:port` (Domain mit Punkt oder IPv4) ohne
   Schema — daraus wird `http://host:port`.
3. **Zugangsdaten aus der Query** (`username`/`user`, `password`/`pass`, case-insensitiv) schlagen
   alles andere für **diese** URL. Basis = Schema + Host + Port, **bekannte Xtream-Endpunkte**
   (`get.php`, `player_api.php`, `panel_api.php`, `xmltv.php`, `index.html`) werden entfernt, ein
   anderer Pfadpräfix bleibt stehen (`…/iptv/get.php?…` → `…/iptv`).
   Grund: `XtreamClient.base()` hängt `player_api.php` direkt an `url` — eine eingefügte
   `get.php`-URL als „Server" wäre sonst kaputt.
   **Ausnahme Playlist-URL:** endet der Pfad auf `.m3u`/`.m3u8`, bleibt die URL komplett (inkl.
   Query, dort stecken die Zugangsdaten), Typ ist `M3U`, Nutzer/Passwort bleiben leer — das
   M3U-Formular hat keine Zugangsfelder.
4. **Label-Felder** (en + de), Trennzeichen `:`/`=`/`-`:
   `user|username|benutzer|benutzername|login`, `pass|password|passwort|pw`,
   `url|server|host|adresse|address|portal`, `mac`. Wert endet an Leerzeichen, `&`, `,`, `;`,
   `|`, Anführungszeichen. Sie füllen nur, was Query/andere Quelle nicht geliefert hat.
5. **MAC:** gruppierte 12-Zeichen-Form mit `:`/`-`/`.`; kanonisiert über
   `StalkerClient.canonicalizeMac` (eine Wahrheit für das MAC-Format). MAC ⇒ `STALKER`, Portal =
   gewählte URL bzw. Label-Wert.
6. **Position (letzte Instanz):** keine Zugangsdaten aus 2./4., genau zwei plausible Rest-Tokens
   neben der URL (Länge 2–64, kein `@`, kein Satzzeichen-Kauderwelsch) → erster = Nutzer, zweiter =
   Passwort, `GUESS`. Das ist die klassische Telegram-Form „URL / Nutzer / Passwort".
7. Sonst nichts raten: `NONE`, leere Felder. Ein falsch geratener Server kostet Minuten Sync.

Der Parser läuft **immer** — er kostet nichts und liefert der KI die Kandidatenliste mit. Bei
`GUESS`/`NONE` ist die Bestätigungsseite (und die KI) die eigentliche Quelle der Wahrheit.

## Architektur

- **Wer ruft DeepSeek?** Der Client liegt im App-Modul (`features/sport/DeepSeekClient.kt`), die
  Companion-Seite im Core. Core darf nichts aus der App importieren. Deshalb bekommt
  `CompanionController` einen optionalen Analyse-Callback
  (`suspend (message, preferredUrl) -> CompanionSourceAnalysis?`), den die App mit dem
  DeepSeek-Aufruf belegt (Muster wie `CoreBuildInfo`-Hooks). Core bleibt DeepSeek-frei.
- **Ein neuer Endpoint, ein alter Payload.** Der Analyse-POST rendert die Bestätigungsseite; diese
  postet an den bestehenden `/source`-Endpoint. `CompanionHttpProtocol.parsePayload` und der ganze
  TV-Pfad (`AddSourceScreen` + `SourceImporter.xtream`) bleiben unverändert.
- **Zugangsdaten nur im POST-Body**, nie in einer URL, nie in einem Log. Die Auswahlliste zeigt die
  Kandidaten-URLs wie eingefügt; in einer **TV**-Anzeige wäre das Passwort zu maskieren.

## P1 — was gebaut wurde

### Core: der Hook

| Datei | Inhalt |
|---|---|
| `core/companion/CompanionSourceAnalysis.kt` | das Ergebnis: `type`, `server`, `username`, `password`, `mac`, `urls`, `url`, `confidence`, `fromAi`; dazu der Typ `CompanionSourceAnalyzer` (`suspend (message, preferredUrl) -> CompanionSourceAnalysis?`) |
| `core/model/Enums.kt` | `SourceMessageConfidence` (HIGH/GUESS/NONE) — **aus der App hierher verschoben**, damit beide Leser dieselbe Vokabel benutzen (der App-Parser importiert sie jetzt) |
| `core/companion/CompanionController.kt` | `var sourceAnalyzer: CompanionSourceAnalyzer?` — der Hook, den die App setzt |
| `core/companion/CompanionHttpServer.kt` | `POST /source/analyze`: PIN nötig, nur im Modus `ADD_SOURCE`; Body `message=…&url=…&consent=1`; antwortet mit der HTML-Seite (P2) |
| `core/companion/CompanionHttpProtocol.kt` | `parseAnalysisRequest` (Body → Anfrage inkl. Einwilligung) |

**Antwort:** die HTML-Seite (P2) — der Analyse-Endpoint rendert direkt die Bestätigungsseite, es
gibt keine JSON-Antwort mehr (die gab es nur in P1, solange es die Seite noch nicht gab).
Das Ergebnis-Modell `CompanionSourceAnalysis` trägt die Felder der Seite: `type`, `server`,
`username`, `password`, `mac`, `urls`, `url`, `confidence`, `fromAi`.

### App: DeepSeek, JSON-Parser, Merge

| Datei | Inhalt |
|---|---|
| `features/sport/DeepSeekClient.kt` | `analyzeSourceMessage(message, apiKey)` — derselbe Endpunkt, dasselbe Modell, derselbe Schlüssel-Handling; Prompt aus `res/raw/source_import_prompt.txt` |
| `features/setup/SourceMessageAiParser.kt` | JSON → Felder, mit Prüfung (siehe unten) |
| `features/setup/SourceMessageMerger.kt` | die Merge-Regeln (reine Funktion, ohne Android) |
| `features/setup/SourceMessageAnalyzer.kt` | Orchestrierung: lokale Regeln + KI, Cache über einen Eintrag, Schlüssel aus `SportsPrefs` |
| `OwnTVApp.kt`, `di/AppModule.kt` | der Hook wird beim Start gesetzt, der Analyzer als Koin-`single` |

**Was der JSON-Parser verwirft** (das Modell darf erfinden, die Nachricht nicht):

- kein `http(s)`-URL → verworfen; zusätzlich muss der **Host in der Nachricht vorkommen**,
- eine MAC nur, wenn die Nachricht ihre zwölf Symbole trägt,
- ein JSON `null` ist `null` und nicht der String `"null"`,
- der Typ nur `XTREAM` / `M3U` / `STALKER`, sonst `XTREAM`.

**Die Merge-Regeln** (`SourceMessageMerger`):

1. **Ziel-URL:** die vom User gewählte Kandidaten-URL, sonst der Kandidat, dessen URL der
   KI-Server trifft, sonst — wenn die KI einen eigenen Server nennt, der kein Kandidat ist — der
   KI-Server, sonst der erste Kandidat.
2. **Server:** immer die lokale Normalisierung des Ziels (`SourceMessageParser.normalizeServer`).
   Eine Quelle, eine Wahrheit: `get.php` fliegt raus, ein `.m3u` behält seine Query.
3. **Zugangsdaten:** die der KI, aber nur wenn die KI genau diese URL gelesen hat; sonst die
   lokalen. Fremde Logins werden nie gemischt.
4. **MAC:** nur die, die die Nachricht selbst trägt.
5. **Typ:** MAC ⇒ `STALKER`, dann `.m3u` ⇒ `M3U`, dann die KI ⇒ `M3U`, sonst `XTREAM`.
6. **Confidence:** KI-Server trifft einen Kandidaten ⇒ `HIGH`, KI-Server ohne Kandidaten ⇒ `GUESS`,
   sonst das der lokalen Regeln.

**Cache:** die KI liest jede Nachricht höchstens einmal; ein Fehlschlag wird nicht gecacht (ein
Wiederholen nach einem Netzausfall versucht es erneut). Wählt der User eine andere URL, wird die
gespeicherte Antwort benutzt — kein zweiter Aufruf, kein zweiter Preis. Wird eine URL verlangt, die
noch nicht analysiert wurde, zahlt die KI nicht: dann zählen nur die lokalen Regeln.

**Was nicht geht (bewusst):** ohne Schlüssel, ohne Netz oder ohne Guthaben liefert die Seite das
Ergebnis der lokalen Regeln (`fromAi = false`) — sie bleibt benutzbar und zeigt den Hinweis
„Die Werte wurden von den eigenen Regeln des TV gelesen."

## P2 — die Seite am Handy

### Der Ablauf

1. **Reiter „Aus Nachricht"** auf der Companion-Seite (nur wenn die Host-App einen Leser hat —
   ohne geht der Reiter gar nicht erst auf). Der Reiter ist der vierte neben Xtream / M3U / Stalker.
2. **Textfeld** für die eingefügte Nachricht, darunter die **Einwilligung** (Checkbox) und der
   Knopf **Analysieren**. Ohne Nachricht oder ohne Häkchen ist der Knopf ausgegraut.
3. **Analysieren** postet `message` + `consent` an `POST /source/analyze`. Der TV liest die Nachricht
   (KI zuerst, lokale Regeln als Auffangnetz) und rendert die **Bestätigungsseite**.
4. **Bestätigungsseite:** Typ, Server/Portal, Nutzer, Passwort, MAC — alles vorbelegt und
   änderbar. Dazu der Hinweis, wer gelesen hat (KI / eigene Regeln / geraten) und, wenn die
   Nachricht mehrere Adressen enthält, eine **Auswahlliste**.
5. **An TV senden** postet die Werte an den bestehenden Endpunkt (`/xtream`, `/m3u`, `/stalker`).
   Der TV füllt sein Add-Source-Formular vor, der Nutzer drückt dort **Importieren starten**.

### Die Einwilligung (vor P3 vorgezogen)

Die Nachricht geht an DeepSeek — mit Server, Nutzer und Passwort. Das kann die Seite nicht tun,
ohne dass der Nutzer es bestätigt hat. Deshalb:

- Die Checkbox **„Ich bin einverstanden …"** ist Pflicht. Sie ist nur ein Häkchen: ist es nicht
  gesetzt, kommt es nicht mit im POST.
- Der Server prüft das: **ohne Einwilligung geht die Nachricht nirgendwo hin** — nicht an die KI,
  nicht aus dem Haus. Die Seite kommt mit dem Fehler und dem **eingefügten Text** zurück, damit
  nichts verloren geht.
- Die Einwilligung gilt für diese Nachricht. Ein erneutes Umlesen derselben Nachricht (andere
  Adresse wählen) nutzt sie mit.
- Ohne Einwilligung läuft **keine** Analyse — auch die lokalen Regeln nicht (die Analyse ist ein
  Aufruf, der beides kombiniert). Wer die KI nicht nutzt, trägt die Werte stattdessen von Hand ein
  (die Reiter Xtream / M3U / Stalker). Der Einwilligungstext sagt beides.

> **Abweichung vom Plan:** die Einwilligung stand in P3, ist aber in P2 gelandet — eine Seite, die
> Zugangsdaten nach außen schickt, kann nicht vorher ohne Einwilligung gebaut werden.

### Die Bestätigungsseite im Detail

- **Typ** (`XTREAM` / `M3U` / `STALKER`): ein Auswahlfeld. Der Typ steuert den Endpunkt, den Namen
  des URL-Feldes (`server` bzw. `portalUrl`) und die sichtbaren Felder (Nutzer/Passwort nur bei
  Xtream, MAC nur bei Stalker). Ohne JavaScript bleibt der ursprüngliche Typ erhalten.
- **URL-Auswahl:** nur wenn die Nachricht mehr als eine Adresse enthält. Die Liste zeigt die
  Adressen **wie eingefügt** (die können Zugangsdaten in der Query tragen — deshalb stehen sie im
  POST-Body, nie in einer URL). Die Wahl geht an denselben Analyse-Endpoint zurück; thanks dem
  Cache kostet das **keinen zweiten KI-Aufruf**.
- **Vollständigkeitsprüfung:** fehlt der Server, der Nutzer oder das Passwort, sagt die Seite das
  vorher, statt den Nutzer in eine Ein-Zeilen-Fehlerseite zu schicken (der TV lehnt eine
  unvollständige Quelle ab).

### Dateien (P2)

- Core: `CompanionHtml.kt` (Reiter, Panel, Bestätigungsseite, `ImportLabels`), `CompanionHttpServer.kt`
  (Endpoint rendert die Seite, Einwilligungs-Prüfung), `CompanionHttpProtocol.kt` (`consented`),
  `CompanionSourceAnalysis.kt` (`CompanionMessagePanel`), `strings_setup.xml` + `values-de/`
  (19 neue Strings), `strings_settings.xml` + `values-de/` (1 neuer String).
- App: `AddSourceChooserScreen.kt` — der Hilfetext „Vom Handy" nennt den neuen Reiter.
- Test: `core/src/test/.../CompanionImportHtmlTest.kt` (14 Fälle: Panel, Einwilligung, Escaping,
  Endpoint je Typ, vorbelegte Werte, Hinweise, URL-Auswahl, KI-Zustand).

## P3 — der Kein-Schlüssel-Hinweis

### Was der TV meldet

Das Ergebnis trägt jetzt den **KI-Zustand** (`SourceAnalysisAiState` im Core). Die Seite macht
daraus den Hinweis, der dem Nutzer sagt, was passiert ist — und was er tun kann:

| Zustand | Wann | Hinweis auf der Seite |
|---|---|---|
| `USED` | die KI hat die Nachricht gelesen | „Die Werte wurden von der KI gelesen.“ |
| `NO_KEY` | kein Schlüssel hinterlegt | „Es ist kein KI-Schlüssel hinterlegt … **Sport-Einstellungen → KI**“ |
| `INVALID_KEY` | der Schlüssel wurde abgelehnt | „Der KI-Dienst hat den Schlüssel abgelehnt … prüfen“ |
| `NO_CREDIT` | kein Guthaben beim Anbieter | „Beim KI-Dienst ist kein Guthaben mehr …“ |
| `FAILED` | nicht erreichbar / keine Antwort | „Die KI war nicht erreichbar oder hat nicht geantwortet …“ |
| `NOT_ASKED` | die KI wurde nicht gefragt | „Die Werte wurden von den eigenen Regeln des TV gelesen.“ |
| `DISABLED` | die KI-Funktionen sind am TV ausgeschaltet | „Die KI-Funktionen sind am TV ausgeschaltet … **Einstellungen → App**“ |

Der Weg zum Schlüssel steht im Hinweis: **Sport-Einstellungen → KI** (EN: Sport settings → AI).
Dort wird der DeepSeek-Schlüssel eingetragen — derselbe wie im Sport-Hub, kein zweiter.

### Der KI-Schalter (App-Einstellungen → App → KI-Funktionen)

Ist der KI-Schalter aus, fragt der TV gar nicht erst: `SourceMessageAnalyzer` liefert dann
`DISABLED` (Core 1.0.71) und die Seite meldet „Die KI-Funktionen sind am TV ausgeschaltet …“,
nennt den Weg zum Schalter und zeigt die lokal gelesenen Werte. Ein gespeicherter Schlüssel
bleibt gespeichert, wird aber nicht benutzt. Das gilt auch für den Cache: eine bereits gelesene
Nachricht wird bei ausgeschalteter KI nicht erneut als KI-Ergebnis ausgegeben.

### Wiederholen statt aufgeben

- Eine **erfolgreiche** Auswertung wird behalten: dieselbe Nachricht kostet so nie zweimal.
- Ein **Fehlschlag** wird *nicht* behalten: der nächste Versuch fragt die KI erneut — der
  Schlüssel kann inzwischen gesetzt, das Guthaben aufgefüllt oder das Netz zurück sein.
- Die URL-Auswahl nutzt die behaltene Auswertung (kein zweiter Aufruf, kein zweiter Preis).

### Dateien (P3)

- Core: `core/model/Enums.kt` (`SourceAnalysisAiState`), `CompanionSourceAnalysis.kt`
  (`aiState`), `CompanionHtml.kt` (Hinweis je Zustand), `strings_setup.xml` + `values-de/`
  (4 neue Strings).
- App: `SourceMessageAnalyzer.kt` (der Zustand aus dem typisierten Fehler `AiFailure`),
  `SourceMessageAnalyzerTest.kt` (die Zuordnung Fehler → Zustand).
- Doku: `docs/SPORT-AI.md` (Abschnitt „Ausnahme: Playlist-Import“), diese Datei.

## Datenschutz (Abweichung zu SPORT-AI)

`docs/SPORT-AI.md` hält fest, dass an DeepSeek **keine** URLs, Playlist-Namen oder Zugangsdaten
gehen. Dieser Import sendet genau das: Server, Nutzer, Passwort. Deshalb:

- **Erst-Einwilligung** auf der Import-Seite, im Klartext benannt.
- Nur bei explizit gestartetem Import, nie automatisch.
- Schlüssel weiterhin nur als `Authorization: Bearer …`, nie in URL oder Log.
- Ohne Schlüssel/Netz/Guthaben greift der lokale Parser (Regeln oben), damit die Seite nicht tot ist.
- **P1/P2:** ohne Einwilligung geht die Nachricht nicht raus — der Endpoint prüft das serverseitig
  (die Checkbox auf der Seite ist die Bedienoberfläche dafür). Der PIN bleibt die zweite Hürde.

## Schlüssel

Der Schlüssel liegt in `SportsPrefs` (App-DataStore `sports`, Sport-Einstellungen → Tab **KI**).
Es ist **derselbe** Schlüssel wie im Sport-Hub — kein zweiter, keine Core-Setting-Migration.
Ist er leer, sagt die Seite das und nennt den Ort (P3).

## Risiken

- KI-Halluzination bei Nutzer/Passwort → Bestätigungsseite + „Test connection" vor dem Sync.
- Prompt-Injection aus der Nachricht → nur vier validierte Felder werden akzeptiert
  (`http(s)://`, MAC-Form); alles andere fällt weg. seit P1 zusätzlich: der Host und die MAC der
  KI-Antwort müssen in der Nachricht **vorkommen**, sonst werden sie verworfen.
- Former-POST zeigt während der Analyse nur den Browser-Wartezustand; Timeouts des DeepSeek-Clients
  (15/60/75 s) begrenzen ihn.
- Positionale Regel kann bei Fließtext danebenliegen; deshalb `GUESS` und Bestätigung.

## Phasen

| Phase | Inhalt | Status |
|---|---|---|
| P0 | `SourceMessageParser` + Unit-Tests (App) | fertig |
| P1 | Analyse-Callback in Core + App-DeepSeek-Aufruf + JSON-Parser + Tests | fertig |
| P2 | Companion-Seite: Textfeld, Bestätigung, URL-Auswahl, Typ, Core-Strings (EN + de) | fertig |
| P3 | Kein-Schlüssel-Hinweis, `docs/SPORT-AI.md`-Verweis, Gates | fertig |

## Dateien

- App: `features/setup/SourceMessageParser.kt` (P0), `features/setup/SourceMessageParserTest.kt` (P0),
  `features/setup/SourceMessageAiParser.kt` + `SourceMessageMerger.kt` + `SourceMessageAnalyzer.kt`
  (P1), `features/sport/DeepSeekClient.kt` (Ergänzung) + `res/raw/source_import_prompt.txt` (P1),
  `OwnTVApp.kt` + `di/AppModule.kt` (Hook, P1), Einstieg in `AddSourceChooserScreen.kt` bzw. Wizard (P2).
- Core: `CompanionSourceAnalysis.kt` (P1), `CompanionController` (Callback), `CompanionHttpServer`
  (Endpoint), `CompanionHttpProtocol` (Request/JSON, P1), `CompanionHtml` (Reiter + Bestätigung),
  Strings in `values/strings*.xml` + `values-de/` (P2).
- Doku: diese Datei, `docs/INDEX.md`-Eintrag.

## Tests / Gates

- P0: `:app:testStandardDebugUnitTest` (Parser-Fälle: Query-Zugangsdaten, Basis-Ableitung mit
  Pfadpräfix, Label-Formen en/de, MAC/Portal, Mehrfach-URLs + Auswahl, `.m3u` ohne Zugangsdaten,
  `host:port` ohne Schema, Fließtext ohne Treffer, Positional-Fallback, `NONE`).
- P1: `:app:testStandardDebugUnitTest` (`SourceMessageAiParserTest`: dokumentierte Form, Fences,
  Query-Zugangsdaten, erfundener Host, erfundene MAC, JSON `null`, `.m3u`; `SourceMessageMergerTest`:
  KI ohne Kandidaten, KI-Kandidat, User-Auswahl, kein Credential-Mix, MAC ⇒ Stalker, KI ⇒ M3U),
  `:core:testDebugUnitTest` (`CompanionHttpServerTest`: Anfrage-Body, Einwilligung).
- P2: `:core:testDebugUnitTest` (`CompanionImportHtmlTest`: Panel mit Einwilligung, Escaping,
  Endpoint je Typ, vorbelegte Werte, Hinweise, URL-Auswahl nur ab zwei Adressen).
- P3: `:core:testDebugUnitTest` (`CompanionImportHtmlTest`: der Hinweis je KI-Zustand),
  `:app:testStandardDebugUnitTest` (`SourceMessageAnalyzerTest`: Fehler → Zustand).
- i18n: neue Kotlin-Literale des Parsers sind technisch (Regex/URI/Label) und stehen klassifiziert in
  `tools/i18n/safe_literals.txt`; `verify --bootstrap` im App- und im Core-Repo grün.
- Gates 2026-10-19: `:app:assembleStandardDebug` + `:app:assembleX86_64Debug`,
  `:app:testStandardDebugUnitTest`, `lintStandardDebug`, `:core:testDebugUnitTest`, Core-`lint`,
  i18n `verify --bootstrap` in beiden Repos, `check_text_overflow.py` in beiden Repos.
- Bekannt: `TimeshiftManagerTest` (Zeitmessung, 800 ms) ist unter Last flaky — nicht Teil dieser
  Änderung, tritt sporadisch auch ohne sie auf.

