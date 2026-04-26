# COP

Hypixel Skyblock Endgame-Mod für Minecraft 1.21.10 und 1.21.11 (Fabric).

## Features

**Dungeons** — Map, ESP, Puzzle Solver (Blaze / Boulder / Beams / Ice Fill / Ice Path / Quiz / TicTacToe / Water / Weirdos / Maze), Terminal Aura, Secret Aura / Trigger Bot, Auto Leap / Mask / CloseChest, Leap Menu, Splits, Tick Timer, Invincibility Timer, Cooldown Display, M3 Fire-Freeze (Display + Auto), F7 Boss Titles, M7 Relics, Maxor Crystals, Door Keys, Shadow Assassin Alert, Hidden Mobs, Persistent Secret Heads, Secret Routes (~150 vorgefertigte Routen), Necron Platform Highlight, Auto Blood Rush, Auto Clear, Auto RCM / LCM, Auto Superboom, Bonzo Staff Helper, Ragnarock, Dungeon Breaker, Fuck Diorite.

**Mining** — Crystal Hollows Map, Crystal Hollows Scanner, Griefer Tracker.

**Render** — Name Tags, Player ESP, Etherwarp Overlay, Custom Mage Beam, Arrow Hitboxes, Game Tint, Render Optimiser, Nick Hider, Custom ClickGUI.

**Misc** — Spotify HUD (Windows SMTC, kein Login nötig), Inventory Search, Wardrobe Keybinds, Pet Keybinds, Anti Nick, Auto Clicker, Mirrorverse Solvers, Cat Mode, Chat Replacements, Auto Updater (GitHub Releases).

**Player** — Auto Sprint, Tweaks, Lag Detector, Snap Tap, Etherwarp Helper, Fishing Helper.

## Build

Beide unterstützten Minecraft-Versionen werden über Stonecutter aus einem gemeinsamen Source-Tree gebaut.

```bash
./gradlew build                       # baut die aktuell aktive Version (Default 1.21.10)
./gradlew build -Pmc_target=1.21.11   # einzelne Zielversion
./gradlew buildAll                    # baut beide, Output liegt in dist/
```

Output:
- `dist/cop-<version>+mc1.21.10.jar`
- `dist/cop-<version>+mc1.21.11.jar`

Für einen Dev-Run mit DevAuth:

```bash
./gradlew runClient
```

## Commands

- `/cop` — öffnet die ClickGui
- `/copdev` — Dev-Befehle (copy, simulate, currentroom, area, featurelist, centre, rotate)
- `/clearchat` — Chat leeren
- `/f0` … `/m7` — joint die jeweilige Catacombs-Instanz

## Credits

COP integriert Ports und Konzepte aus mehreren Open-Source-Mods. Volle Liste mit Lizenzen in [CREDITS.md](CREDITS.md).

## License

GPL-3.0 — siehe [LICENSE](LICENSE).
