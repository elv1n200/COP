# COP

Eine zusammengeführte Hypixel Skyblock Endlvl Mod für Fabric 1.21.10, gebaut aus **fünf** bestehenden Mods:

- **Hunchclient** ([github.com/.../Hunchclient1.21](./)) — Java-basiert, Meteor Orbit Event Bus, Skeet-GUI
- **NoammAddons** ([github.com/Noamm9/NoammAddons](./)) — Kotlin, preprocessor cheat/legit variants
- **Quoi** ([github.com/.../quoi](./)) — Kotlin, umfangreichste Codebase (~34k LOC), NVG-Renderer
- **Athen** ([github.com/skies-starred/Athen](./)) — Kotlin QoL-Mod (~23k LOC), eigenes reaktives Config-System
- **Nebulune** ([github.com/skies-starred/Nebulune](./)) — Kotlin Cheat-Addon für Athen (~2.7k LOC)
- **CritsAddons** ([github.com/noamm9/critsaddons](./)) — Kotlin Addon mit Secret-Route-Playback und QoL-Combat-Modulen

COP selbst ist **kein** eigenständiger Original-Code. Es ist eine Zusammenführung dieser fünf Mods.

## Merge-Strategie

Die drei Mods überschneiden sich stark (alle drei decken F7/M7, Terminal Solver, Secret-Tracking, Puzzle Solver ab). Statt jedes Modul dreimal zu portieren und dann einen Best-of-3 Vergleich anzustellen, habe ich mich auf folgende Strategie festgelegt:

1. **Basis = Quoi**, weil:
   - Größte, modernste Codebase (Kotlin, saubere Module/Event/HUD/Settings DSL)
   - Abgeleitet von Odin/OdinFabric (BSD 3-Clause) — saubere Lizenz-Attribution
   - Deckt bereits 90%+ der Features der anderen beiden ab (65 Module, inklusive aller Puzzle Solver, Dungeon Map, Secret System, Terminal Aura, etc.)
2. **FuckDiorite = Quoi** (wie ausdrücklich vom User gewünscht), siehe [src/main/kotlin/cop/module/impl/dungeon/FuckDiorite.kt](src/main/kotlin/cop/module/impl/dungeon/FuckDiorite.kt)
3. **Package-Rename** `quoi` → `cop` im gesamten Source Tree (338 Dateien gescannt, 329 umbenannt, Mixin-Prefix `quoi$` → `cop$`)
4. **Mod-Metadaten** auf COP geändert: `mod_id=cop`, `mod_name=COP`, Befehl `/cop` statt `/quoi`, Config-Pfad `config/cop/`
5. **Unique Features** aus Hunch/Noamm, die Quoi *nicht* hat, werden als neue Module hinzugefügt und sind über die Attribution klar als Ableitung markiert — z.B. [F7BossTitles.kt](src/main/kotlin/cop/module/impl/dungeon/F7BossTitles.kt) (inspiriert von NoammAddons `F7Titles`)

## Wo überschneiden sich Features und welche "bessere" Version wurde gewählt?

| Feature | Hunch | Noamm | Quoi | → Gewählt |
|---|---|---|---|---|
| **FuckDiorite** | Java, hardcoded + Chat-Trigger | Cheat-only, JSON-geladene Koordinaten | BSD-lizensiert von Odin, Tick-basiert, 2 Color Modes | **Quoi** (User-Wunsch) |
| Terminal Solver (Melody/Numbers/Panes/Rubix/SelectAll/StartsWith) | Umfassendste Impl. (10+ Terminal-Typen) | Grundlegend | Grundlegend + TerminalAura | **Quoi** (Basis), Hunch-spezifische Details könnten noch portiert werden |
| Puzzle Solver (Blaze/Boulder/Beams/IceFill/IcePath/Quiz/TTT/Water/Weirdos/Maze) | — | Vorhanden | Alle 10 + RiftSolvers | **Quoi** |
| Dungeon Map | SkeetDungeonMap | DungeonMap | DungeonMap + Odon-Scanning | **Quoi** (Odon-Scanning ist am robustesten) |
| Secret System | SecretRoutes + DungeonManager | Secrets + SecretHitboxes | Secrets + SecretAura + SecretTriggerBot + AutoRoutes | **Quoi** |
| ESP/Entity Highlighting | StarredMobs + BreakerHelper | StarMobESP + BloodESP + TeammateESP + WitherESP | DungeonESP + PlayerESP | **Quoi** |
| Leap Menu | — | LeapMenu | LeapMenu | **Quoi** |
| Etherwarp | EtherwarpHelper + EtherwarpModule + LeftClickEtherwarp | — | EtherwarpOverlay | **Quoi** (saubere Single-Module-Impl.) |
| Auto Leap / Mask / CloseChest | AutoLeap + AutoMaskSwap + CloseDungeonChests | — | AutoLeap + AutoMask + AutoCloseChest | **Quoi** |
| Chat / CustomTriggers | ChatUtils + Kaomoji + MeowMessages | TextReplacer | Chat + ChatReplacements + CustomTriggers | **Quoi** |
| ClickGUI | SkeetScreen2 | ClickGUI | ClickGui + abobaui + NVG-Renderer | **Quoi** |

## Neue Module hinzugefügt (unique aus Hunch/Noamm, nicht in Quoi)

- [F7BossTitles](src/main/kotlin/cop/module/impl/dungeon/F7BossTitles.kt) — On-Screen-Callouts für Maxor stunned / Storm crushed / Necron started. Konzept von NoammAddons `F7Titles`, reimplementiert gegen Quois `ChatEvent`/`Module` API.
- [DoorKeys](src/main/kotlin/cop/module/impl/dungeon/DoorKeys.kt) — Tracker für Wither/Blood Keys an Armor-Stands, Box + Tracer + Outline. Port von NoammAddons `DoorKeys`.
- [HiddenMobs](src/main/kotlin/cop/module/impl/dungeon/HiddenMobs.kt) — Macht unsichtbare Fels, Shadow Assassins, Watcher-Mobs und gerüstete Giants sichtbar. Port von NoammAddons `HiddenMobs` (ohne JSON-Download — eingebaute Allowlist).
- [Ragnarock](src/main/kotlin/cop/module/impl/dungeon/Ragnarock.kt) — Strength-Gain-Message, Cancel-Alert und M7 Dragon-Rag Pling-Melodie. Port von NoammAddons `Ragnarock`.
- [MaxorsCrystals](src/main/kotlin/cop/module/impl/dungeon/MaxorsCrystals.kt) — Spawn Timer, Place Timer und Place Alert für Maxor Energy Crystals. Port von NoammAddons `MaxorsCrystals`.
- [M7Relics](src/main/kotlin/cop/module/impl/dungeon/M7Relics.kt) — Vollständiger Port von NoammAddons `M7Relics` mit allen Features:
  - **Relic Box**: Highlight + Tracer auf den passenden Cauldron
  - **Spawn Timer / Place Timer** mit **PersonalBest** pro Farbe (Chat zeigt `(PB)` an)
  - **Relic Look**: Auto-Rotation zum Cauldron bei Rot/Orange (einstellbare Geschwindigkeit + Easing-Style)
  - **Block Wrong Relic**: Cancelt Right-Click-Placements an falschen Cauldronen
  - **Relic Aura**: Auto-Interact mit Relic-Armor-Stand im 3-Block-Radius (via AuraManager)
- [InventorySearch](src/main/kotlin/cop/module/impl/misc/InventorySearch.kt) — Tippen während eines Container-GUI filtert und highlightet passende Slots. Port von NoammAddons `InventorySearch` (ohne Math-Eval).
- [BonzoStaffHelper](src/main/kotlin/cop/module/impl/dungeon/BonzoStaffHelper.kt) — Vollständiger Port von Hunchs `BonzoStaffHelperModule`:
  - **Auto S-Tap** über `KeyEvent.Input` (schreibt `input.backward = true` im Tap-Fenster, gleicher Pfad wie COPs AutoClear)
  - **Experimental Mode**: setzt horizontale Geschwindigkeit auf 0 solange man am Boden ist — für maximalen Boost ohne Vorlauf-Drift
  - **Adaptive Timing**: loggt `+gain (peak t=X, rate Y%, avg Z)` und schlägt bessere Delay/Dauer-Werte im Chat vor
  - **Sound Cue**: Pling am Start des Tap-Fensters, HUD zeigt `TAP S` / `VEL-CANCEL` / `{N}t` / `done`
- [CustomMageBeam](src/main/kotlin/cop/module/impl/render/CustomMageBeam.kt) — Ersetzt die Firework-Partikel-Spur von Mage-Beams durch eine saubere, färbbare Linie (Solid / Rainbow). Port von Hunchs `CustomMageBeamModule` (ohne Helix/Wave/Dashed).
- [PersonalBest](src/main/kotlin/cop/config/PersonalBest.kt) — Globaler PB-Store, persistiert unter `config/cop/personal_bests`. Genutzt von M7Relics; wiederverwendbar für künftige Timer-Module via `PersonalBest.checkAndSetPB(key, value, lowerIsBetter)`.

### Aus Athen (QoL)
- [ArrowHitboxes](src/main/kotlin/cop/module/impl/render/ArrowHitboxes.kt) — 3D-Wireframe-Box um jeden fliegenden Pfeil (inkl. Tipped/Spectral). Port von Athens `ArrowHitboxes`.
- [GameTint](src/main/kotlin/cop/module/impl/render/GameTint.kt) — Färbt den HUD-Screen und/oder offene GUIs in einer Farbe (volle Alpha-Kontrolle). Port von Athens `GameTint` mit getrennten HUD/GUI-Switches.
- [LagDetector](src/main/kotlin/cop/module/impl/player/LagDetector.kt) — HUD zeigt "Lag: Xms" wenn seit dem letzten Server-Tick mehr als N ms vergangen sind. Port von Athens `LagDetector`.
- [SnapTap](src/main/kotlin/cop/module/impl/player/SnapTap.kt) — Counter-Strafe-Input: beim Tippen der Gegenrichtungstaste wird die gehaltene automatisch losgelassen. Port von Athens `SnapTap` über Quois `KeyEvent.Press/Release`.

### Aus CritsAddons
- [SecretRoutes](src/main/kotlin/cop/module/impl/dungeon/SecretRoutes.kt) — Spielt vorab aufgenommene Secret-Routen in Dungeon-Räumen ab. Lädt ~150 vorgefertigte Routen aus [`assets/cop/secretRoutes.json`](src/main/resources/assets/cop/secretRoutes.json) (395 KB, aus dem NoammAddons/CritsAddons-DB); optionaler User-Override unter `config/cop/secretRoutes.json`. Unterstützt 7 Step-Typen (ETHERWARP, PLACE_TNT, BREAK_BLOCK, USE_HYPERION, RIGHT_CLICK_SECRET, WAIT_FOR_SECRET_PROGRESS, WAIT_FOR_BAT_SPAWN) mit Auto-Start bei zentriertem Stehen auf dem Start-Block, Mana-Gating, Rotations-Smoothing und Tastatur-Toggle.
- [PersistentSecretHeads](src/main/kotlin/cop/module/impl/dungeon/PersistentSecretHeads.kt) — Zeigt geklickte Redstone-Key / Wither-Essence Secret-Heads als gefärbte Geister-Boxen weiter an, damit sie im Auge bleiben. Exposed `findGhostHeadTargetForRoute()` und `hasSpawnedBatInCurrentRoom()` für SecretRoutes.
- [AutoRCM](src/main/kotlin/cop/module/impl/dungeon/AutoRCM.kt) — Rechtsklick auf das Trigger-Item wechselt auf das Swap-Item, rechtsklickt, und wechselt zurück. Konfigurierbar über Item-UUID oder SkyBlock-ID, mit optionalem CD-Gating über `CooldownDisplay`.
- [AutoLCM](src/main/kotlin/cop/module/impl/dungeon/AutoLCM.kt) — Dasselbe Prinzip für Linksklick (Left-Click Mage). Port von CritsAddons `AutoLCM`.
- [M3AutoFF](src/main/kotlin/cop/module/impl/dungeon/M3AutoFF.kt) — In M3 Boss: auf der Professor-Fire-Freeze-Triggerzeile wechselt auf Fire Freeze Staff, wartet 5 Sekunden, castet Freeze, wechselt zurück.
- [M3FFDisplay](src/main/kotlin/cop/module/impl/dungeon/M3FFDisplay.kt) — HUD-Anzeige für den M3 Fire-Freeze-CD-Counter mit farbcodiertem Status.
- [CooldownDisplay](src/main/kotlin/cop/module/impl/dungeon/CooldownDisplay.kt) — Generische CD-Tracker- + HUD-Anzeige für Right-Click/Left-Click Items mit eigener Item-Registry. Exposed `isOnCooldown(stack)` und `startRightClickCooldown(stack)` für AutoRCM / M3AutoFF.

### Aus Nebulune (Cheats)
- [EtherwarpHelper](src/main/kotlin/cop/module/impl/player/EtherwarpHelper.kt) — Linksklick triggert Etherwarp bei Items mit `ETHERWARP_CONDUIT` oder `ethermerge` Attribut. Optional auto-Shift für 2-6 Ticks vor dem Warp. Port von Nebulunes `EtherwarpHelper`.
- [FishingHelper](src/main/kotlin/cop/module/impl/player/FishingHelper.kt) — Auto-Pull wenn "!!!" ArmorStand am Bobber erscheint + Auto-Recast mit randomisierter Varianz. Port von Nebulunes `FishingHelper` (Nametag-Polling statt Athen-Events).
- [AutoSuperboom](src/main/kotlin/cop/module/impl/dungeon/AutoSuperboom.kt) — LMB auf Breakable-Wände (Cracked Bricks / Barrier / Terracotta / Nether Bricks) swappt zu Superboom TNT, zündet, swapt zurück. Port von Nebulunes `AutoSuperboom` mit switchbaren Block-Typen statt Command-basiertem Set.

### Skipped / Ersatz vorhanden
- **CustomScale** (Athen) — braucht `AvatarRenderState` Hook den COP nicht hat; erfordert einen neuen Mixin.
- **ClickGUI/HUDEditor-Transplant** (Athen → COP) — technisch unmöglich ohne alle 80+ Module neu zu verdrahten (Athen nutzt eigenen NVG-Wrapper + reaktives Observable-Config-System, inkompatibel mit Quois `switch`/`slider` Delegate-DSL).
- **PestESP / RatESP / HideonESP / BossESP** (Nebulune) — COP hat bereits DungeonESP + PlayerESP.
- **AutoTerms / HoverTerms / QueueTerms / BreakerHelper / ChestCloser / WardrobeHelper** (Nebulune) — Quoi hat entsprechende Module (TerminalAura, SecretAura, DungeonBreaker, AutoCloseChest, WardrobeKeybinds).

## Module, die bei Interesse noch portiert werden könnten (unique aus Hunch/Noamm)

Aus **Hunch**: NowPlaying/MediaPlayer, Pokedex, ReplayBuffer, Blink, FakeLag, Kaomoji, ImageHUD, DeviceSimulator, PlayerTrap

Aus **Noamm**: ModHider, ChestProfit, SalvageOverlay, BossBarHealth, DebuffHelper, BloodCamp, CpsDisplay, WarpShortcuts, BlessingDisplay

Jedes dieser Module ist eigenständig und kann durch eine `object X : Module(...)` Klasse unter `src/main/kotlin/cop/module/impl/<category>/` hinzugefügt werden — der `ModuleManager` erkennt sie automatisch über ClassGraph Package-Scanning.

## Projekt Setup

```
Minecraft:   1.21.10
Loader:      Fabric 0.18.2
Language:    Kotlin 2.2.21 (JVM 21)
Fabric API:  0.138.4
Kotlin API:  1.13.7+kotlin.2.2.21
Build:       Gradle + fabric-loom 1.14-SNAPSHOT
```

## Build

```bash
./gradlew build
```

Output jar: `build/libs/cop-1.0.0.jar`

Für Development-Auth (beim Testen in Dev-Client):

```bash
./gradlew runClient
```

DevAuth ist in der `build.gradle.kts` schon konfiguriert.

## Commands

- `/cop` — Öffnet die ClickGui
- `/copdev` — Dev-Befehle (copy, simulate, currentroom, area, featurelist, centre, rotate)
- `/clearchat` — Leert den Chat
- `/f0`, `/f1` … `/m7` — Joint die jeweilige Catacombs-Instanz

## FuckDiorite-Konfiguration

Unter ClickGui → Dungeon → "Fuck Diorite":

- **Aktivierung**: Modul togglen, dann in F7/M7 Boss (P2 Storm Fight)
- **One colour**: Alle 4 Säulen kriegen die gleiche Farbe
- **Colour**: Farbe auswählen (NONE = Plain Glass, sonst 16 Minecraft Stained Glass Farben)
- Default (One colour aus): jede Säule kriegt ihre eigene Farbe (Lime / Yellow / Purple / Red)

Die Block-Replacement-Logik läuft auf `TickEvent.End` und ist scoped auf `Island.Dungeon(7, inBoss = true)` — es passiert *nichts* außerhalb von F7 Boss.

## Lizenz & Attribution

- Basis aus Quoi, mit Odin/OdinFabric Ursprung (BSD 3-Clause, © odtheking 2025-2026) — siehe individuelle File-Header
- `CatMode.kt`, `Location.kt`, `Dungeon.kt` u.a. enthalten Odin-Attribution im Source
- [FuckDiorite.kt](src/main/kotlin/cop/module/impl/dungeon/FuckDiorite.kt) trägt den BSD 3-Clause Vermerk mit Link auf das Odin-Original
- [F7BossTitles.kt](src/main/kotlin/cop/module/impl/dungeon/F7BossTitles.kt) vermerkt die Inspiration durch NoammAddons
- Root-LICENSE = Quoi's Original-Lizenz
