<div align="center">

<img src="src/main/resources/assets/cop/icon.png" width="96" alt="COP logo">

# COP

**Hypixel Skyblock Endgame-Mod für Fabric.**
Dungeons, Mining, Render-, Player- und QoL-Module aus einem gemeinsamen Source-Tree für mehrere MC-Versionen.

[![Build](https://github.com/elv1n200/COP/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/elv1n200/COP/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/elv1n200/COP?label=release)](https://github.com/elv1n200/COP/releases/latest)
[![License](https://img.shields.io/github/license/elv1n200/COP)](LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-blue)

[Installation](#installation) · [Features](#features) · [Commands](#commands) · [Build](#build) · [Changelog](CHANGELOG.md)

</div>

---

## Installation

1. **Fabric Loader** installieren — [fabricmc.net/use](https://fabricmc.net/use). Wähle MC 26.1.2.
2. **Fabric API** runterladen — [modrinth.com/mod/fabric-api](https://modrinth.com/mod/fabric-api). Passende Version für dein MC.
3. **Latest COP Release** holen — [releases](https://github.com/elv1n200/COP/releases/latest). Die richtige Jar je nach MC-Version:
   - `cop-X.Y.Z+mc1.21.10.jar`
   - `cop-X.Y.Z+mc1.21.11.jar`
   - `cop-X.Y.Z+mc26.1.2.jar`
4. Beide Jars in den `mods/`-Ordner deines MC-Profils legen.
5. Spiel starten. Drücke **Right Shift** für die ClickGUI oder tippe `/cop`.

Updates ziehen sich beim ersten Hypixel-Join automatisch (Auto Updater Modul, default an).

## Features

<details open>
<summary><b>Auto Croesus</b> — Full-Auto Claim-Driver mit Kismet Rerolls und Loot-Log</summary>

<br>

Profit-Overlay pro Chest, ein-Tasten Multi-Run-Cycle inkl. NPC-Re-Interact, Kismet-Reroll auf marginalen Chests, append-only JSONL Loot-Log, Always-buy/Worthless Item-Listen. Full doc: [`docs/auto-croesus.md`](docs/auto-croesus.md).

<!--
Screenshot-Slot. Empfehlung: AutoCroesus-Overlay über einem Run-Sub-Screen
(Cost / Value / Profit pro Chest, ★ auf dem besten).

Ablage: docs/images/autocroesus-overlay.png — dann hier einbinden:

  ![AutoCroesus profit overlay](docs/images/autocroesus-overlay.png)
-->

</details>

<details>
<summary><b>Dungeons</b> — Map, ESP, Solver, Macros</summary>

<br>

42 Module aufgeteilt in 5 klappbare Sub-Sektionen in der ClickGUI:

- **Worldrender** — DungeonMap, DungeonESP, HiddenMobs, NecronPlatformHighlight, FullBlockHitboxes, FuckDiorite, PersistentSecretHeads
- **Huds** — Splits, Secrets, TickTimers, InvincibilityTimer, CooldownDisplay, M3FFDisplay, F7BossTitles, M7Relics, DoorKeys, MaxorsCrystals, ShadowAssassinAlert
- **Solvers** — PuzzleSolvers (Blaze / Boulder / Beams / Ice Fill / Ice Path / Quiz / TicTacToe / Water / Weirdos / Maze), SimonSays, ArrowAlign, TerminalWaypoints
- **Qol** — LeapMenu, AutoPotionBag, CancelInteract, Ragnarock, AutoCroesus
- **Cheats** — TerminalAura, AutoTerms, SecretTriggerBot, SecretAura, DungeonBreaker, AutoBloodRush, AutoClear, AutoRCM, AutoLCM, M3AutoFF, AutoMask, AutoLeap, AutoCloseChest, AutoSuperboom

<!--
Screenshot-Slot. Empfehlung: ClickGUI Dungeon-Spalte mit aufgeklappten Sub-Headers.

Ablage: docs/images/dungeon-subcategories.png

  ![Dungeon sub-categories in the ClickGUI](docs/images/dungeon-subcategories.png)
-->

</details>

<details>
<summary><b>Mining</b></summary>

<br>

Crystal Hollows Map, Crystal Hollows Scanner, Griefer Tracker.

</details>

<details>
<summary><b>Render</b></summary>

<br>

Name Tags, Player ESP, Etherwarp Overlay, Custom Mage Beam, Arrow Hitboxes, Game Tint, Render Optimiser, Nick Hider, Custom ClickGUI.

</details>

<details>
<summary><b>Misc</b></summary>

<br>

Spotify HUD (Windows SMTC, kein Login nötig), Inventory Search, Wardrobe Keybinds, Pet Keybinds, Anti Nick, Auto Clicker, Mirrorverse Solvers, Cat Mode, Chat Replacements, Auto Updater (GitHub Releases).

</details>

<details>
<summary><b>Player</b></summary>

<br>

Auto Sprint, Tweaks, Lag Detector, Snap Tap, Etherwarp Helper, Fishing Helper, AutoSoulcry.

</details>

## Commands

| Command | Was passiert |
|---|---|
| `/cop` | Öffnet die ClickGUI |
| `/cop toggle <module>` | Modul an/aus |
| `/cop hud` | HUD-Editor |
| `/cop loot [today\|week\|all\|reset]` | Auto-Croesus Loot-Summary ([Details](docs/auto-croesus.md)) |
| `/cop alwaysbuy [list\|add\|remove\|clear] [ID]` | Auto-Croesus Always-Buy IDs |
| `/cop worthless [list\|add\|remove\|clear] [ID]` | Auto-Croesus Worthless IDs |
| `/cop findlobby` / `/cop antiafk` | Lobby-Helpers |
| `/copdev …` | Dev-Befehle (copy, simulate, currentroom, area, featurelist, centre, rotate, pricetest, croesusdump) |
| `/clearchat` | Chat leeren |
| `/f0` … `/m7` | Joint die jeweilige Catacombs-Instanz |

## Build

Mehrere Minecraft-Versionen aus einem gemeinsamen Source-Tree via [Stonecutter](https://github.com/kikugie/stonecutter). Branch-Layout:

- **`main`** — JDK 21, Loom 1.14, baut für 1.21.10 und 1.21.11
- **`mc26`** — JDK 25, Loom 1.16, baut für unobfuskiertes MC 26.1.2

```bash
./gradlew build                       # baut die aktive Version (Default 1.21.10 auf main)
./gradlew build -Pmc_target=1.21.11   # einzelne Zielversion auf main
./gradlew buildAll                    # baut alle konfigurierten Versionen
```

Output liegt unter `dist/cop-<version>+mc<MC>.jar`.

Für einen Dev-Run mit DevAuth:

```bash
./gradlew runClient
```

**mc26 lokal bauen:** der Branch braucht einen JDK-25-Daemon. Setze `JAVA_HOME` per-Shell oder global auf deinen JDK-25-Pfad bevor du `./gradlew` aufrufst. CI macht das automatisch via `actions/setup-java`.

## Contributing

Issues + Pull Requests sind willkommen — [Bug-Report-Template](https://github.com/elv1n200/COP/issues/new?template=bug_report.yml) zwingt zu den nötigen Infos (MC-Version, Logs, Repro-Steps).

## Credits

COP integriert Ports und Konzepte aus mehreren Open-Source-Mods. Volle Liste mit Lizenzen in [CREDITS.md](CREDITS.md).

## License

[GPL-3.0](LICENSE).
