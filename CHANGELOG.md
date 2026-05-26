# Changelog

All notable changes to this project are documented here.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
