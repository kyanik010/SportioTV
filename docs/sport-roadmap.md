# Sport-Roadmap: Reddit-Feedback + User-Punkte (Sport-Hub, Live-TV Sprachen)

> Zweck: Verbindlicher Umsetzungsplan für Sport-Hub-Matching, Sprachsteuerung, In-Player-Wechsel und Live-TV-Sprachen aus Reddit-Feedback plus User-Vorgaben.
> Stand: 2026-10-14. Status: Punkte 1–12 umgesetzt, alle Gates grün, bereit zum Push (Release morgen).

## Herkunft und Scope

- Quelle 1: Reddit-Kommentare zum Sportio-Post (`r/AndroidTVApps`, Stand 2026-10-14, als Copy-Paste vom User übernommen).
- Quelle 2: User-Vorgaben aus diesem Chat (Sprachsteuerung, Youth-Filter, In-Player-Liste, Live-TV Sprachen).
- Scope: nur Sport-Hub (`app/src/main/java/tv/own/owntv/features/sport/`) plus Live-TV-Sprachausblendung. Ausgeklammert: Install-/Parsing-Fehler FireTV, Xtream-/M3U-Importfehler, HTTP-403-Playerproblem, TheSportsDB-Evaluierung, Replays, Discovery-QR, F-Droid, Phone-Zoom. Diese Punkte sind OwnTV-Basis oder separat und werden hier nicht umgesetzt.
- Referenzen: `SPORT-HUB.md` (Einstieg), `sport-hub-plan.md` (Phasen-Spec), `sport-channel-matching.md` (Matching-Details). Diese Datei dupliziert deren Specs nicht, sie priorisiert nur die neue Arbeit.

## Ist-Stand (verifiziert 2026-10-14)

- Matcher: `SportsMatcher.kt:83` (`match(fixture, languages, limit)`), Filter vor Cap in `SportsMatcher.kt:129` (`SportLanguages.filter(scored, languages).take(limit)`), `DefaultLimit = 20` in `SportsMatcher.kt:587`.
- Sprachfilter: `SportLanguages.kt:76` (`filter`), strikt (Kanäle ohne/ohne bekanntes Tag fallen raus, `isExact` überlebt immer), Fallback volle Liste wenn Filter leer wäre (`SportLanguages.kt:79`). Map Country-Tag zu Sprache in `SportLanguages.kt:117`. Sprachen: DE/EN/ES/PT/FR/NL/IT/PL/TR/AR (`SportLanguages.kt:15`), Default DE (`SportLanguages.kt:48`, `SportsPrefs.kt:110`).
- Prefs: `SportsPrefs.kt:38` (`preferredLanguages`), `SportsPrefs.kt:86` (`setPreferredLanguages`). Kein Limit-Key, keine Exclude-Liste, keine Mindestquote.
- UI Sport: Kanalliste in `SportsScreen.kt` (`FixtureDetail`, ab ca. `SportsScreen.kt:839`), Play via `onPlayMatch(match, list)` (`SportsScreen.kt:425`), Preview-Fokus via `focusedChannelId` (`SportsScreen.kt:719`). Fullscreen-Wechsel darf kein `stopPreview` im Dispose rufen (Falle, siehe `SPORT-HUB.md`).
- Aliase: `app/src/main/res/raw/sports_matching.json` (Team-Aliase, Kanal-Hints).

## Reihenfolge

Empfohlen: 4 + 5 zuerst (Hauptnutzen User), dann 6 + 8, dann 1–3, dann 7. Begründung: 4 entkoppelt alle Sprachthemen vom Hardcode, 5 ist kleiner Recall-Fix mit Tests, 6 + 8 sind reine TV-UX, 1–3 sind inhaltsseitig, 7 fasst Live-TV an (grösster Regression-Radius).

## 1. US-Matching: Region-Priorität + Aliase

- Problem (Reddit `Zenister_`): College Football listet CA/AU FOX/ABC über US-Feeds (bzw. AM). Matcher ist für Soccer/Tennis/F1 optimiert, US-College nicht angefasst.
- Ziel: US-Feeds gewinnen bei US-Fixtures, CA/AU/UK-Feeds fallen dahinter, ohne sie zu verstecken wenn kein US-Feed existiert.
- Umsetzung:
  - Region-Gewicht im Scoring (`SportsMatcher.kt`, Pass 2): US-Tag Bonus bei Fixtures mit US-Liga/Teams, CA/AU Malus bzw. kein Bonus. Kein harter Ausschluss.
  - Alias-Pflege `sports_matching.json`: College-Teams, Abbreviations (z. B. AM-Konvention aus User-Playlist prüfen), Broadcast-Phrasen.
  - Sprachfilter-Interaktion beachten: Filter läuft vor Cap (`SportsMatcher.kt:129`), Region-Sortierung innerhalb der gefilterten Menge.
- Tests: `SportsMatcherTest` + `SportNameMatchTest` mit US/CA/AU-Kanälen gleichen Spiels, Reihenfolge-Assertion.
- Abnahme: College-Fixture zeigt US-Feed oben, CA/AU nur darunter; ohne US-Feed bleibt Liste nicht leer.

## 2. Australien-Coverage

- Problem (Reddit `PaleontologistOwn469`): `Nothing for Australia`.
- Ziel: klären ob Lücke in ESPN-Daten, Default-Set oder Matching.
- Umsetzung:
  - Prüfen: AFL/NRL (und ggf. A-League) in `SportsLeagues.kt` vorhanden, ESPN-Scoreboard erreichbar, als aktiv setzbar.
  - Falls Liga fehlt: in Katalog aufnehmen (Label als Proper Noun, Gate beachten). Falls vorhanden aber inaktiv: Default-Set-Entscheidung dokumentieren, kein Zwang.
  - Falls Matching leer trotz Fixture: Kanäle der User-Playlist auf AU-Tags prüfen (`AU` mappt bereits auf EN, `SportLanguages.kt:108`).
- Abnahme: AU-Liga lädt Boards, oder dokumentiert warum ESPN nichts liefert.

## 3. Arabisch als Sport-Sprache

- Problem (Reddit `Mysterious-Cap8732`): arabische Sport-Sender gewünscht.
- Ziel: AR als wählbare Sport-Sprache.
- Umsetzung:
  - `SportLanguage.ARABIC` + Label in `res/values/strings.xml` + `values-de/` (`sport_*`, kein `OwnTV`-Name neu).
  - `CountryLanguages`-Map erweitern (z. B. SA/AE/QA/EG/MA … nach IPTV-Konvention der Playlist, nicht ISO-puristisch).
  - Picker-Reihenfolge (`SportLanguages.kt:37`), Tests (`SportLanguagesTest`), i18n-Gate (`verify --bootstrap`).
- Abnahme: AR wählbar, AR-getaggte Kanäle erscheinen, Gate grün.

## 4. Sport-Sprachsteuerung: Limit, Hart-Filter, Mindestquote, Exclude

- Problem (User): Limit 20 ist hardcoded, kein harter Filter, keine Mindestmenge eigener Sprache, keine Ausschlüsse.
- Ziel: User steuert Menge und Strenge selbst.
- Umsetzung:
  - `SportsPrefs`: neue Keys `channel_limit` (Default 20, Range z. B. 5–50), `excluded_languages` (Set), `language_filter_mode` (z. B. `strict` Default neu vs. `legacy` Fallback). `preferredLanguages` bleibt Positiv-Auswahl.
  - `SportsMatcher.match`: Signatur um `minPreferred` (Default 5) und Exclude-Set erweitern oder als Param-Objekt; Filter vor Cap behalten. Ablauf: Exclude raus → Preferred filtern (hart, `isExact` überlebt) → wenn Preferred-Treffer < Minimum, nur mit Preferred auffüllen soweit vorhanden, sonst kürzere Liste (kein Fremd-Feed-Auffüllen im Strict-Modus). Danach Cap auf `channel_limit`.
  - `SportLanguages.filter`: Strict-Variante ohne Never-Empty-Fallback; Legacy-Verhalten nur wenn Modus es verlangt.
  - UI: Sport-Settings (neben `SportLanguageDialog` in `SportsScreen.kt:460`): Limit-Stepper, Mindestquote-Anzeige (fix 5 v1, kein Extra-Regler), Exclude-Multi-Select. Alle neuen Labels `sport_*` in `strings.xml` + `values-de/`, `TextOverflow.Ellipsis` bei `maxLines` (Overflow-Gate).
- Tests: Matcher-Tests für Limit/Cap-Reihenfolge, Mindestquote (weniger als 5 Preferred → keine Fremd-Feeds), Exclude (ausgeschlossene Sprache nie in Liste, auch nicht als `isExact`? Entscheidung: `isExact` überlebt Exclude nicht — dokumentieren).
- Abnahme: Limitänderung wirkt ohne Neustart, Strict zeigt nur gewählte Sprachen, Mindestquote 5 wird eingehalten oder Liste ist ehrlich kürzer.

## 5. Youth-Filter (Champions League vs. Youth/Jugend)

- Problem (User): Champions-League-Suche zieht UEFA-Youth (`U…`, Jugendliga) mit rein.
- Ziel: Herren-/Profispiel trifft nie Jugendspiel und umgekehrt.
- Umsetzung:
  - Negativ-Regel in Discovery + Scoring: Patterns `youth`, `u19`, `u-19`, `u19`, `jugend`, ggf. `u21/u23` nur wenn Fixture-Liga Profi ist. EPG-Titel/Beschreibung und Kanalname prüfen (normalisierte Tokens, `SportNameMatch`).
  - Profi-Fixture (z. B. `uefa.champions`) schliesst Programme mit Youth-Markern aus; Youth-Fixture schliesst Profi-Marker aus (symmetrisch, kein einseitiger Hack).
  - Alias-Datei: keine Youth-Aliase an Profi-Teams hängen.
- Tests: Fixture Champions vs. Programme `Youth League`, `U19`, `Jugendliga` → Score 0; Youth-Fixture trifft Youth-Programme weiterhin.
- Abnahme: CL-Liste enthält keine Youth-Einträge mehr, Recall für echte CL-Feeds unverändert.

## 6. In-Player Stream-Wechsel (Links/Rechts)

- Problem (User): nach Öffnen eines Streams muss man mit Zurück raus und neu hinscrollen für einen anderen Stream desselben Spiels.
- Ziel: im geöffneten Stream per Links/Rechts alle gematchten Streams sehen und durchscrollen/zappen, TV-remote-first.
- Umsetzung:
  - Match-Liste (`onPlayMatch(match, list)` in `SportsScreen.kt:425`) als Zap-Kontext an Player übergeben (IDs in Reihenfolge, aktuelle Position).
  - D-Pad-Links/Rechts im Player: Overlay-Liste (Kanäle des Fixtures, Fokus fangend, Preview weiter), OK zappt, Zurück schliesst Overlay (nicht Player).
  - `stopPreview`-Falle beachten: kein Dispose-Stop beim Fullscreen-Gehen (`SPORT-HUB.md` Nicht-wieder-einbauen Nr. 1), nur bei echtem Spiel-Wechsel (`selectedFixture?.id`-Effekt).
  - Fokus-Regeln: Overlay fängt Links/Rechts, Player-HUD sonst unverändert; leerer Fall (1 Kanal) zeigt Overlay nicht.
- Tests: manuell auf TV/Emulator mit D-Pad (Links/Rechts/OK/Zurück), kein Abbruch des laufenden Streams beim Öffnen.
- Abnahme: Wechsel ohne Zurück-zum-Hub, Fokus kehrt nach Schliessen zum Player zurück.

## 7. Live-TV Sprachen (bestätigt: Live-TV, nicht nur Sport)

- Problem (User): Sprachen sollen aus der hinterlegten Playlist abgeleitet und pro Sprache für die TV-Funktion deaktivierbar sein.
- Ziel: Live-TV zeigt nur Sprachen, die der User an hat; Liste der Sprachen kommt aus seiner Playlist, nicht aus Hardcode.
- Umsetzung:
  - Scan: alle Kanalnamen der Live-Quellen über `countryCodeOf` (`SportLanguages.kt:52`) laufen lassen, auf `SportLanguage` mappen, Unbekannte/Taglose als `Sonstige` sammeln. Ergebnis cachen (Sync-/Startzeit, nicht pro Frame).
  - Settings: deaktivierbare Sprachliste (Default alles an), persistiert in DataStore (eigene Datei `sports` oder Core-Settings je nach Live-TV-Besitz — Entscheidung bei Umsetzung dokumentieren, kein Duplikat-State).
  - Filterort: Live-TV-Liste/Guide vor Cap/Sort anwenden (analog Matcher-Prinzip Filter-vor-Cap), `Sonstige`-Schalter separat. Sport-Matching bleibt unberührt (eigene Preferred-/Exclude-Logik aus Punkt 4).
  - Offene Bestätigung aus Chat: Scope ist Live-TV Liste/Guide-Ausblendung. Serien-/Film-Bereiche sind TV-only entfernt und fallen nicht darunter.
- Tests: Unit-Test Tag-Ableitung (DE:/UK|/FR/AMISCH?/taglos), UI-Test Ausblendung, Regression Sync (Scan darf Sync nicht verlangsamen).
- Abnahme: neue Sprache in Playlist erscheint nach Scan in Settings, Deaktivieren blendet ihre Sender in Live-TV aus, Reaktivieren bringt sie zurück.
- Stand 2026-10-14 (Fix, verifiziert auf Emulator mit echter Playlist): echte Playlists taggen die PROVIDERGRUPPEN (`NL| …`), nicht die Kanalnamen — `LiveLanguages` löst die Sprache deshalb zuerst über den Gruppentag (`categoryId` → Gruppenname → `ProviderTags.channelCountry` → `SportLanguages`, Kanalnamen-Tag nur Fallback; `LiveLanguages.kt`). Der Pager-Filter (`LiveViewModel.kt`) bekommt die Gruppennamen als Resolver-Closure, der Scan zählt Gruppensprachen mit. Sonstige-Logik unverändert (weder Gruppen- noch Kanal-Tag gemappt → Sonstige).
- Stand 2026-10-14 (Fix): Kategorien-Rail ist mitgefiltert — Gruppen mit 0 sichtbaren Kanälen werden ausgeblendet, Zähler zeigen sichtbare Kanäle (`liveVisibleCounts` aus den Scan-Daten, null = Filter aus; `liveCategoryEntries(…, visibleCounts)` in `LiveStage.kt`, Index-konsistent über `visibleRailItems` in `LiveScreen.kt`). Kein Sync-Slowdown (Scan wiederverwendet, bounded), kein Core-DAO-Umbau.
- Stand 2026-10-20 (Fix, Tag-Formen und Sprachvokabular eines zweiten Providers): dieser Provider schreibt seinen Tag **zwischen zwei schweren Balken** (`┃DE┃ DAZN LA LIGA`, U+2503 — nicht `|`) und benutzt ein **gemischtes Vokabular**: Länder (2 und 3 Buchstaben: `DE`, `USA`, `ARG`, `CAR`), Sprachen (`HINDI`, `PUNJABI`, `KURD`, `TAMIL`), Regionen (`AFR`, `ASIA`) und Marken (`CANAL+`, `OSN`, `PPV`, `UCL`, `F1 TV`), teils zweierlei (`CA EN`, `KURD DVB-T`), teils zwei Tags in einer Reihe (`┃ENGLISH┃HINDI┃ …`). Die alten Parser verlangten den Code ganz vorne — das Tag traf also nie, jeder der 24 228 Kanäle war „Sonstige" und der Sprach-Dialog zeigte nur „Sonstige" (keine Sprache wählbar). Neu: **eine** Stelle liest die Formen (`CountryTags`, `features/sport/CountryTags.kt`): `SEPARATOR` („DE:“), `BAR` („DE|“, „USA|“) und `BLOCK` („┃DE┃“, „| DE |“). Die **Bedeutung** entscheidet `SportLanguages`: ein Land über `CountryLanguages` (Land → Sprache, jetzt auch alpha-3 und die fehlenden Länder), ein Sprachname über `TagLanguages` (`HINDI`, `PUNJABI`, `KURD-SAT`, …), eine Marke oder Region über **keine** Sprache — der App rät nie („CANAL+“, „PPV“, „AFR“ → Sonstige). Zweiwort-Tags liest das Sprachwort zuerst, wenn das erste Wort ein bekanntes Land ist („CA EN“ → Englisch, „CA FR“ → Französisch). Dafür **37 neue Sprachen** im Enum (Hindi, Urdu, Punjabi, Kurdisch, Griechisch, Schwedisch, …) mit EN/DE-Labels; der Badges zeigt jetzt den **Tag des Providers** (Land, Sprache oder Region), nicht nur ein Land.

## 8. Live-TV Kategorien: Länderkennzeichen pro Sender/Kategorie

- Problem (User): beim Scrollen durch Kategorien ist nicht sichtbar, aus welchem Land die Sender sind.
- Ziel: jede Kategoriezeile und jede Senderzeile zeigt das Land (aus Namens-Tag), TV-remote-lesbar ohne Öffnen.
- Umsetzung:
  - Quelle: vorhandenes Tag-Parsing wiederverwenden — `ProviderTags.parse` (`features/live/ProviderTags.kt:17`, Muster `DE|`, `UK |`) für Gruppennamen, `SportLanguages.countryCodeOf` (`features/sport/SportLanguages.kt:52`, Muster `DE:`, `UK|`, `FR/`) für Sendernamen. Keine neue Regex erfinden.
  - Kanalzeile (`LiveStage.kt`, `.chrow`): Länderkürzel-Badge (z. B. `DE`, `UK`) vor/nach Logoplatte, aus Kanalnamen-Tag; ohne Tag kein Badge (kein Raten).
  - Kategorieliste (`LiveStage.kt:450` `LiveCategories`, `LiveScreen.kt:557`): pro Gruppen-Eintrag Land aus Gruppennamen zeigen; bestehender `GROUPS · DE`-Heading (`ProviderTags.sharedCountry`, `LiveStage.kt:433`) bleibt, zusätzlich Badge pro Zeile wenn Gruppen gemischte Länder haben.
  - Darstellung: Text-Badge (Ländercode), keine Emoji-Flaggen (TV-Fonts, i18n-Gate). Neue Labels `live_*`/`sport_*` in `strings.xml` + `values-de/`, `TextOverflow.Ellipsis`-Gate beachten.
- Tests: `ProviderTags`-Parsing für Badge-Fälle, Screenshot/Emulator mit D-Pad durch Kategorien.
- Abnahme: Land pro Sender/Kategorie sichtbar, gemischte Gruppen zeigen je Zeile eigenes Land, taglose Sender ohne Badge.
- Stand 2026-10-14: neue Sport-Sprachen NL/IT/PL/TR (User-Entscheidung alle vier) in `SportLanguage`-Enum + `CountryLanguages`-Map (`NL`→DUTCH, `IT`→ITALIAN, `PL`→POLISH, `TR`→TURKISH) + Picker-Order + `sport_language_*`-Labels in `values/strings.xml` + `values-de/` (nur diese zwei, Rest fällt auf EN zurück). `AR` bleibt Spanisch (Argentinien). Badges bleiben Ländercodes (User-Entscheidung), keine Emoji-Flaggen — keine Änderung nötig (`StageTag` in `LiveStage.kt`).
- Stand 2026-10-20 (Fix): die Tag-Formen liegen jetzt zentral in `CountryTags` (`features/sport/CountryTags.kt`) statt in zwei eigenen Regexen. Dadurch kennt das Land-Badge auch die gewrappte Form (`┃DE┃ DAZN` → Badge „DE“, Name „DAZN“) und die Gruppenüberschrift („GROUPS · DE“) gilt auch für solche Gruppen. Der Badge zeigt den **Tag des Providers** (Land, Sprache oder Region — „HINDI“, „AFR“), nicht nur einen Ländercode. Ohne Tag weiterhin **kein** Badge (kein Raten).
- Stand 2026-10-20 (User-Wunsch): die Zeile **„All languages“** ist ein **Schalter**, kein Knopf. Ein Druck schaltet **alles ab** (jede Sprache der Playlist wird ausgeschlossen, „Sonstige“ auch → die Live-Liste ist leer, der Reiter zeigt „0 of 47“), der nächste Druck schaltet **alles wieder an**. Vorher konnte man nur alles einschalten (`LiveLanguages.toggleAll` / `allEnabled`, `LiveViewModel.toggleAllLiveLanguages`, `LiveScreen.LiveLanguageDialog`).

## Nicht-Ziele (bewusst draussen)

- FireTV-Install/Parsing, Xtream-Boolean, M3U-Erkennung, 403-Player, TheSportsDB, Replays, QR-Discovery, F-Droid, Phone-Zoom. Bei Bedarf eigene Pläne, keine Vermischung in diese Datei.

## 9. Kanalliste pro Spiel neu laden (Refresh-Button)

- Problem (User): Matches werden pro Fixture gecacht (`matchesCache` in `SportsViewModel.kt`), Re-Resolve nur bei Fixture-/Sprach-/Limit-/Exclude-Wechsel — kein manuelles Neu-Laden der gematchten Kanäle.
- Ziel: pro Spiel ein Aktualisieren-Button in der Kanalliste.
- Umsetzung (2026-10-14):
  - `SportsViewModel.refreshMatches()` (public): verwirft den Cache und löst das selektierte Fixture über das vorhandene `reResolveSelected()` neu auf (Loading-State inklusive).
  - Button in der Channels-Sektion von `FixtureDetail` (`SportsScreen.kt`, Titel-Zeile): `StageSurface` im Retry-Stil (REFRESH-Icon + `sport_refresh_channels`), während Loading deaktiviert. Ruft `sportsVm.refreshMatches()` direkt (keine Signaturänderung nötig).
  - Label `sport_refresh_channels` in `res/values/strings.xml` (`Refresh channels`) + `values-de/` (`Kanäle aktualisieren`); alle `Text` mit `maxLines` haben `TextOverflow.Ellipsis` (Overflow-Gate).
- Tests: Matcher-Frischlese-Test (`SportsMatcherTest` (w)) — der Matcher liest die Quellen pro Aufruf frisch (IDs-Wechsel zwischen zwei Calls flippt das Ergebnis); nur der ViewModel-Cache war stale, und genau den wirft der Button weg.
- Abnahme: Button drücken → kurz `Loading…` → frische Liste; ohne selektiertes Fixture kein Effekt.

## 10. Quellenwechsel erreicht den Sport-Hub

- Problem (User): der Quellen-Umschalter oben rechts (`defaultSourceId`) erreicht den Sport-Hub nicht — nach dem Wechsel stehen alte Matches da (Cache + keine Beobachtung).
- Ziel: bei Quellenwechsel Cache verwerfen + selektiertes Fixture neu auflösen, ohne Full-Board-Reload.
- Umsetzung (2026-10-14):
  - `SportsViewModel` beobachtet `settings.activeProfileId` → `flatMapLatest` auf `combine(sourceRepository.observeSources(pid), settings.defaultSourceId)`, gemappt über die reine Funktion `scopeLiveSourceIds` (`SportsSources.kt`, aus `ProfileLiveSourceIdsProvider` extrahiert — Provider und Observer teilen exakt diese Logik).
  - `distinctUntilChanged().drop(1)`: nur echte Wechsel lösen aus (kein Start-Reload); bei Auslösung `reResolveSelected()` — `matchesCache.clear()` + Re-Match nur wenn ein Fixture selektiert ist, kein Board-Reload (Fixtures sind quellenunabhängig).
  - Kein Polling, kein Core-Umbau (nur Koin-autoverdrahtete `SettingsRepository`/`SourceRepository`-Params am ViewModel), kein Preview-Eingriff (`stopPreview`-Falle aus `SPORT-HUB.md` unberührt).
- Tests: `SportsSourcesTest` (5 Fälle: Default-Einengung, kein Default, `syncLive=false`, unbekanntes Default, leere Liste). Der Observer-Pfad selbst braucht Android-Repos (kein Mock-Framework im Projekt) und ist nicht JVM-testbar — die geteilte Scope-Logik ist es.
- Abnahme: Quelle oben rechts wechseln → selektiertes Spiel zeigt die Kanäle der neuen Quelle, Boards und Preview laufen unberührt weiter.

## 11. Sport-Settings Redesign (Tabs + Slider)

- Problem (User): Endlos-Scroll-Liste (Handy, Sprache, Fewer/More, Quota, Always-hide, alle Ligen aller Regionen).
- Umsetzung (2026-10-14, reines Redesign, keine Funktionsaenderung): 3 Tabs via StageTab mit Zaehleren (aktive Ligen / Limit / ausgeblendete Sprachen). Tab Ligen: Manage-on-phone + Regions-Cycler (eine Region pro Seite) + Checkliste dieser Region. Tab Kanaele: Slider-Zeile (Links/Rechts 5-50, OK steppt hoch, Fortschrittsbalken) + Hinweis/Quota. Tab Sprachen: Language-Dialog-Item + Always-hide-Checkliste. Fewer/More-Strings entfernt. Neue Keys: sport_settings_tab_{leagues,channels,languages}, sport_channel_limit_hint (values + values-de).
- Abnahme: kein Endlos-Scroll, Fokus bleibt beim Tab-Wechsel, alle Gates gruen.

## 12. Ebenen-UX (Pfeile + Sektions-Titel)

- Problem (User): Top-Level vs. Sublevel nicht erkennbar, keine Pfeile.
- Umsetzung (2026-10-14, nur Darstellung): StageGroupLabel REGION + Regions-Cycler mit Globe-Icon (NETWORK) und Wert ‹ n ›, Hinweistext Left/Right, Regionsname als Checklist-Titel; Manage-on-phone und Language-Item mit ›-Chevron; Sprachen-Tab mit BEVORZUGTE SPRACHEN / IMMER AUSBLENDEN-Gruppenlabels. Neue Keys: sport_region_title, sport_region_switch_hint, sport_region_cycler_value, sport_sublevel_chevron, sport_preferred_title (values + values-de).
- Abnahme: Umschalter vs. Checkliste visuell getrennt, Sublevel-Items mit Chevron.
