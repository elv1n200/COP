# Changelog

All notable changes to this project are documented here.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.5.0] — 2026-06-05

### Added
- **Addon-API** — COP kann jetzt Module aus *separaten* Fabric-Mods laden, ohne dass die im COP-Source-Tree leben müssen. Ein Addon deklariert einen `cop`-Entrypoint (`fabric.mod.json`) der auf eine `CopAddon`-Implementierung zeigt; COP ruft die beim Client-Init auf (nach den eigenen Modulen, vor Config-Load) und der Addon registriert seine Module via `CopAddonRegistrar.register(...)`. Addon-Module kriegen identische Behandlung: ClickGUI, Keybinds, Config-Persistenz.
  - Neue **`ADDON`-Category** (eigene ClickGUI-Spalte) — Addon-Module landen dort per Default (alles außerhalb des `cop.`-Package-Trees), können aber via `category = ...` / `subCategory = ...` Constructor-Override woanders hin.
  - `Module` nimmt jetzt optionale `category` / `subCategory` Overrides (vorher nur aus dem Package-Namen abgeleitet — für Fremd-Packages unmöglich).
  - Ein Addon das beim Init crasht wird geloggt + übersprungen, reißt COP nicht mit runter.
  - Doku: [`docs/addons.md`](docs/addons.md) mit Schritt-für-Schritt + Beispiel-Modul.

### Fixed
- **ClickGUI** crashte nicht mehr (`Exception("no good")`) wenn eine Category in einer alten Config fehlt — jetzt wird lazy ein Default-Layout gesetzt. Trat mit der neuen `ADDON`-Category auf (MapSetting.read clear()t die Map bevor es die gespeicherten Einträge putAll()t, wodurch neue Categories rausfielen).

## [1.4.4] — 2026-06-04

### Fixed
- **Auto Croesus — Galatea-Shards bekommen jetzt ihren Bazaar-Preis**: User-Report "auto croesus didnt reconized shard right and we put it always to 0". Die neuen Hunting-Box-Shards (z.B. `SHARD_POWER_DRAGON` = ~207k auf dem Bazaar) waren in der Profit-Berechnung als 0 geführt. Ursache: die Hypixel-Items-Registry kennt diese Shards nicht (stoppt vor der Galatea-Update-Ära), und der Parser-Fallback hat den display name `"Power Dragon Shard"` zu `POWER_DRAGON_SHARD` synthesisiert — Suffix statt Prefix, falsche Form. Auf dem Bazaar heißen sie `SHARD_<MONSTER>`. Neuer `PriceClient.resolveShardId()`-Helper erkennt die `"X Shard"` / `"X Shards"` Endung, schlägt `SHARD_X` vor und confirmt es gegen die Bazaar-Liste. Wird in `CroesusParser.tryParseLine` zwischen Registry-Lookup und der generischen Snake-Case-Synthese eingehängt. Funktioniert für alle ~189 SHARD_-IDs auf dem Bazaar.

## [1.4.3] — 2026-06-04

### Fixed
- **Secret Routes — Schema komplett falsch gelesen, jetzt richtig**: User-Report "wenn ich ein secret auf der route gemacht habe... die whole route disappears nicht der part zu dem ersten secret so i cant continue the rest of the secrets in the room".

  Ursache: jeder JSON-Eintrag unter `"RoomName-N"` ist NICHT eine Alternative zum gleichen Secret (wie bisher angenommen), sondern ein **sequentieller Schritt** in einer Route. `Waterfall-8` hat z.B. 8 Steps die nacheinander 8 Secrets im Raum abklappern. Mehrere `"RoomName-N"` Keys für den gleichen Raum = mehrere Route-VARIANTEN die der Spieler auswählen kann (z.B. Waterfall hat eine 1-Step-Kurzversion `Waterfall-2` und eine 8-Step-Vollversion `Waterfall-8`).
  
  Bisheriger Code hat jeden Eintrag als Alternative behandelt und nur `entry[0]` gerendert → beim Einsammeln des ersten Secrets wurde der gesamte "Group" als done markiert und alle 7 weiteren Steps der Variant waren unsichtbar.

  Komplettes Refactor:
  - `RouteData`: neue Typen `Step` (statt `Route`) und `RouteVariant`. Variant-ID ist jetzt ein String (vorher nur Int) — fängt non-numerische Suffixes wie `Withermancers-4:1` und `Blaze-Room-1-High` ab die vorher silent gedropped wurden. Mehrere Varianten pro Raum werden sortiert nach Step-Count → default-pick ist die längste (covers most secrets).
  - `SecretRoutes`-Modul: walks pro Variant durch die Steps via "first uncollected = active step". Default-Render zeigt nur die aktive Step's volle Route + kleine Target-Dots auf den upcoming Secrets der Variant zur Awareness. Schalter "Show whole route" rendert alle uncollected Steps voll.
  - Auto-Advance: Event-Hooks markieren jetzt den passenden Step in der Variant (statt ein Group); out-of-order-Completion wird sauber gehandled (Step N collected → alle Steps 0..N werden ebenfalls als done markiert damit "active = first uncollected" stimmt).
  - Per-Run-Persistenz: Set keyed by `(roomName, variantId, stepIndex)` statt `(roomName, secretIndex)`.
- Neue Schalter: "Show whole route", "Show upcoming secrets", "Show all variants".

## [1.4.2] — 2026-06-04

### Fixed
- **Secret Routes — fixer Route-Start statt position-abhängig**: wenn ein Secret mehrere Alternativ-Routen in der DB hat, wurde bisher pro Frame die Alternative gewählt deren erster Waypoint am nächsten zum Spieler war. Beim Laufen flippte die gerenderte Route hin und her und die grüne "Start"-Box sprang mit. Jetzt wird deterministisch `alternates[0]` gepickt — eine feste Route + festes Start, ändert sich nicht beim Bewegen. "Show alternates" zeigt weiterhin alle wenn gewünscht.

## [1.4.1] — 2026-06-02

### Added
- **Secret Routes — Per-Run-Persistenz für completed Secrets**: einmal abgeräumte Secrets bleiben für den Rest des Runs versteckt, auch wenn du den Raum verlässt und wieder reinkommst. Implementiert als `Set<(roomName, secretIndex)>` der bei `WorldEvent.Change` geleert wird (= entering/leaving Dungeon ist ein World-Swap → neuer Run startet sauber). Wird *nur* gefüllt wenn das Secret durch ein echtes Completion-Signal markiert wurde (`DungeonEvent.Secret.{Interact,Item,Bat}` Events, der INTERACT-Head Block-Poll, oder der BAT/ITEM Proximity-Check). Manueller "Skip current secret"-Keybind ist absichtlich *nicht* persistent — der ist nur Show-mir-jetzt-die-nächste, du könntest später zurück wollen.

## [1.4.0] — 2026-06-02

### Changed
- **World-render Lines & Wireframes — Shader-Pack-Kompatibilität (Iris/Sodium)**: `drawLine`, `drawWireFrameBox` und `drawCylinder` rendern nicht mehr via GL_LINES sondern als kamerafacing Billboard-Quads. GL Line Primitives werden von Iris/Sodium aussortiert/gar nicht gerendert, daher waren mit Shadern bisher *alle* unsere Welt-Linien und Wireframe-Boxen unsichtbar — Secret-Routes-Linien, DungeonESP-Wireframes, PuzzleSolver-Linien, NameTag-Tracers, Etherwarp-Outline, ArrowAlign-Hitboxes, NecronPlatformHighlight, FullBlockHitboxes, M7Relics, DoorKeys, MaxorsCrystals, AutoCroesus-Highlight, FuckDiorite, TerminalWaypoints uvm. Die ~20 Module die diese Utils nutzen funktionieren jetzt mit Shader-Packs.
  - Implementierung: Pro Liniensegment wird der Cross-Product aus `lineDir × (segmentStart - camera)` als "Width"-Vektor genommen → 4-Vertex-Quad das immer zur Kamera schaut. Wireframes sind 12 solcher Quads (ein Quad pro Würfelkante). Filled Boxes blieben unverändert (waren schon shader-friendly via `TRIANGLE_STRIP`).
  - Neue Render-Layer `BILLBOARD_LINE_QUAD` (+ `_ESP` für depth-off) ersetzen die alten `LINE_LIST`-Layer. Vertex-Format `POSITION_COLOR` mit `QUADS` Draw-Mode.
  - Inspiriert von yourboykyle's Secret Routes Mod beta3 (`AnotherRenderingUtil`, GPL-3).

## [1.3.5] — 2026-06-02

### Added
- **Secret Routes — Proximity-Auto-Advance für BAT / ITEM Secrets**: zusätzlich zu den Packet-Events (Bat-Damage-Sound, Item-Pickup-Packet) wird pro Frame geprüft ob der Spieler innerhalb von 3 Blöcken eines BAT-Secrets oder 2 Blöcken eines ITEM-Secrets steht — fängt die Fälle ab wo das Packet aus irgendeinem Grund nicht ankommt (Bat-Sound gedropt, Item-Velocity schiebt es weg bevor Pickup, etc.). Matched yourboykyle beta3's Fallback-Verhalten.

### Notes (vs upstream beta3)
- **Shader-Kompatibilität**: Upstream beta3 rendert Lines + Wireframe-Boxen als billboarded Quads damit sie mit Iris/Sodium funktionieren. COPs `drawLine` / `drawWireFrameBox` nutzen noch GL Line Primitives die von Shader-Packs nicht gerendert werden — als Folge sind unsere Secret-Route-Linien, DungeonESP-Wireframes, Solver-Linien etc. mit Shadern unsichtbar. Fix benötigt ein Refactor des `WorldRenderContextUtils` (project-wide, nicht nur SecretRoutes). Als separate Task gespawnt.
- **Updated pearl routes / fixed text rendering** aus dem beta3-Changelog: bei uns nicht relevant — pearlroutes.json ist byte-identisch zum upstream main, und unser `drawText` nutzt schon `Font.DisplayMode.SEE_THROUGH` vs `NORMAL` analog zu deren Fix.

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
