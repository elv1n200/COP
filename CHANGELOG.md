# Changelog

All notable changes to this project are documented here.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.4] — 2026-05-29

### Fixed
- **Secret Routes Auto-advance**: die aktive Route ist beim näher-Kommen sofort verschwunden, weil der pro-Frame Block-Check angenommen hat, dass jedes INTERACT-Secret ein `PLAYER_HEAD` ist — Levers, Buttons, Chests etc. wurden dann instant als "collected" markiert sobald man in deren Render-Distanz kam. Jetzt wird beim Room-Enter geprüft, ob das Block tatsächlich ein Player-Head ist, und nur dann gepollt (`pollableAsHead`-Flag pro Group). Lever / Chest / Button INTERACT-Secrets werden nur noch via Event oder den neuen Manual-Skip-Keybind als done markiert.

### Added
- **Secret Routes — "Skip current secret" Keybind**: per-Tastendruck das gerade angezeigte Secret als done markieren um zur nächsten Route zu springen. Fallback wenn die Event-basierte Auto-Advance ein Secret verfehlt (Chest-Secrets, ungewöhnliche Lever-Positionen, Party-Mate-Klicks die wir nicht mitbekommen haben). Default unbinded — selbst im Module-Menü setzen.

## [1.3.3] — 2026-05-29

### Added
- **Secret Routes**: drei Erweiterungen aus dem ursprünglichen Feature-Backlog.
  - **Auto-advance**: einmal-eingesammelte Secrets werden ausgeblendet und die aktive Route springt automatisch zum nächstgelegenen verbliebenen Secret. Hooks auf `DungeonEvent.Secret.{Interact,Item,Bat}` plus pro-Frame Block-Check (fängt INTERACT-Secrets ab die jemand anderes im Party schon abgeräumt hat). Schalter "Auto-advance" — default an.
  - **Beacon beam**: dünne translucent vertikale Säule über jedem Secret-Target, in der Secret-Type-Farbe — von weit weg durch Wände sichtbar. (Reimplementiert als hohes filled box weil MCs `BeaconRenderer` auf 1.21.10 jetzt über `SubmitNodeCollector` läuft und nicht aus `WorldRenderContext` heraus aufrufbar ist.) Schalter "Beacon beam" — default an.
  - **Pearl trajectories** (Tier 2): bündelt die `pearlroutes.json`-Routen aus. An jeder Pearl-Throw-Position wird ein kleiner Marker gezeichnet plus eine 10-Block-Linie entlang `(pitch, yaw)` damit man weiß wohin man werfen muss. Schalter "Pearl trajectories" — default an. Pearl-Schema in `RouteData` von `List<BlockPos>` auf `List<Vec3>` + `List<PitchYaw>` umgestellt um die Fractional-Precision zu bewahren (vorher hat die Int-Konversion die Throw-Positionen kaputtgerundet).

## [1.3.2] — 2026-05-29

### Added
- **Secret Routes** (Dungeons / Worldrender) — neues Modul, rendert pro Dungeon-Raum die bekannten Secret-Routen als Welt-Linie + farbige Boxen pro Waypoint-Typ. Routen-Daten von [yourboykyle's Secret Routes Mod](https://github.com/yourboykyle/SecretRoutes) (GPL-3.0) als `assets/cop/secretroutes/{routes,pearlroutes}.json` mitgebündelt; deren 17 MB Skeleton-Room-Detection-Bundle ist nicht nötig weil COP Räume bereits über Odins Bedrock-Core-Hash identifiziert. Display-only — kein Playback, kein Auto-Walk. (Tier 2: Pearl-Launch-Angle-Linien folgen.)
  - **Default UX**: nur die Route zum *nächstgelegenen* Secret im Raum bekommt Linie + Waypoints; alle anderen Secrets in dem Raum bekommen trotzdem einen kleinen Target-Cube als Übersicht. Schalter "Show all secrets" für die volle Übersicht.
  - **Start-Marker**: Wireframe-Box mit "Start"-Label am ersten Waypoint, plus 1/2/3-Nummern über jedem Walking-Waypoint damit die Reihenfolge klar ist.
  - **Per-Secret-Type Farben**: Interact / Bat / Item / Chest / Exit haben unterschiedliche Target-Colours statt einem generischen Rot.
  - **Room-Name-Normalisierung**: Die Routen-DB verwendet Kebab-Case (`Super-Tall`, `Arrow-Trap`), `odon_rooms.json` verwendet Spaces oder Concatenation (`Supertall`, `Arrow Trap`). Lookup vergleicht jetzt lowercase-alphanumeric — bringt ~40 Räume wieder zurück die vorher silently leer waren.
- **CREDITS**: neuer Eintrag für yourboykyle / R-aMcC / itplays / zzyyrraa, Hunchclient-Eintrag entfernt nachdem Custom Mage Beam clean-room neu geschrieben wurde.

### Fixed
- **Dungeon.dungeonTeammates** ist jetzt durchgehend ein `CopyOnWriteArrayList` — die punktuellen `.toList()`-Snapshots in `MapRenderer` hatten andere Iterations-Sites in `Dungeon.kt` und `DungeonEnums.kt` nicht abgedeckt. Crash trat hauptsächlich auf langsameren Clients in Dungeons auf.
- **AutoUpdater**: Update-Popup wurde auch dann gezeigt wenn die lokale Version *neuer* ist als das letzte GitHub-Release (z.B. "Update verfügbar: 1.3.2 → 1.3.1" — also Downgrade-Prompt). `libautoupdate.isUpdateAvailable` ist nur eine Tag-String-Inequality; jetzt wird zusätzlich Komponenten-weise Semver verglichen und das Popup nur bei echtem Remote > Local geöffnet.

## [1.3.1] — 2026-05-26

### Fixed
- **AutoUpdater**: zwei Bugs nach dem 1.3.0-Release.
  - "Update available" Popup feuerte auch wenn lokale Version = neueste Version, weil das GitHub-Tag `v1.3.0` mit `v`-Prefix war, der lokale `mod_version` aber `1.3.0` ohne. Defensive `v`-Stripping auf beiden Seiten der Vergleichs.
  - Popup-Buttons waren unsichtbar (anklickbar aber unsichtbar) — `super.render()` wurde vor dem Panel-Hintergrund aufgerufen, der dann die Buttons übermalt hat. Reihenfolge umgedreht.

## [1.3.0] — 2026-05-25

### Added
- **Auto Croesus** — vollständiger Auto-Claim-Driver für den Croesus-NPC.
  - Phase 1: PriceClient (Bazaar + LBIN + smart enchant-book lookup).
  - Phase 2: Profit-Overlay (per-chest cost / value / profit, ★ best-chest).
  - Phase 3a/b/c: Single-claim → Chain (mit Dungeon Chest Key) → Multi-Run mit NPC-Re-Interact zwischen Cycles.
  - Phase 4: Kismet Reroll Layer auf marginalen Chests, mit speculative-enter wenn Profit unter Min profit aber Reroll-Threshold erreicht.
  - Phase 5: Loot-Log (`config/cop/croesus-loot.jsonl`) + `/cop loot [today|week|all|reset]` Summary mit Per-Tier / Top-Items Breakdown.
  - Phase 6: Always-buy / Worthless Skyblock-ID Listen (`/cop alwaysbuy`, `/cop worthless`) zur Steuerung der Buy-Entscheidung.
  - Auto-Page-Advance durch die Croesus-Listen-Seiten.
  - User-tunable Multi-Run Pacing Slider für langsamere Connections.
- **Dungeon Sub-Kategorien** im ClickGUI — 42 Module in `worldrender / huds / solvers / qol / cheats` aufgeteilt, klappbare Sub-Header pro Sektion, Per-Sektion Collapse-State persistiert.
- Volle Doku in `docs/auto-croesus.md`.

### Changed
- **AutoUpdater**: feuert nur noch einmal pro Minecraft-Session und nur beim Join auf Hypixel — kein "already on latest version" Spam mehr bei jedem Lobby-Hop.

### Fixed
- **CroesusParser**: unbekannte Items (z.B. Power Dragon Shard in M7) brechen nicht mehr den ganzen Chest, Profit wird leicht zu niedrig geschätzt aber Chest bleibt claimbar.
- **CroesusParser**: already-bought Chests werden als solche erkannt (statt verwirrendes "no Cost marker" Failure).
- **MapRenderer**: ConcurrentModificationException auf `Dungeon.dungeonTeammates` zwischen Render- und Network-Thread — gecrasht hauptsächlich auf langsameren Clients in Dungeons.
- **ClickGui**: null-safe Lade-Logik für sub-category Collapse-State (Gson-Default-Value-Bug bei alten Configs).

## [1.2.0] — 2025-05-24

### Added
- AutoSoulcry (End-Katana Helper).
- VisualWords (Chat Find/Replace).
- AutoTerms (NUMBERS / PANES / NAME / COLORS / Rubix / Melody Solver).
- TerminalWaypoints.
- LobbyMarker.
- ItemQuality, CameraHelper.
- Inventory module (outline highlight + dump / withdraw keybinds), replaces older InventorySearch.

### Fixed
- Render pipelines auf 1.21.11 (line vertex format, NVG text sampler conflict).
- ChatComponent.render mixin signature für 1.21.11.
- renderSlot mixin signature für 1.21.11.

## [1.1.0] — 2025-04-26

### Added
- AutoUpdater module mit Popup-Screen.
- Default Module Sort.

## [1.0.0] — 2025-04-20

### Added
- Initial public release.

[1.3.1]: https://github.com/elv1n200/COP/releases/tag/1.3.1
[1.3.0]: https://github.com/elv1n200/COP/releases/tag/v1.3.0
[1.2.0]: https://github.com/elv1n200/COP/releases/tag/1.2.0
[1.1.0]: https://github.com/elv1n200/COP/releases/tag/1.1.0
[1.0.0]: https://github.com/elv1n200/COP/releases/tag/1.0.0
