# Credits

COP enthält portierten oder von Konzepten dieser Open-Source-Mods abgeleiteten Code. Diese Datei listet die Quellen mit ihren Lizenzen — die jeweiligen Quelldateien tragen zusätzlich Copyright-Header wo es die Lizenz vorschreibt.

## Quoi / Odin / OdinFabric — BSD 3-Clause

© odtheking, 2025–2026.

Bildet die Basis von COP: Modul/Event/HUD/Settings DSL, NVG-Renderer, ClickGUI, das `abobaui`-Framework, das Odon-Scanning, der größte Teil der Puzzle-/Terminal-Solver, FuckDiorite, EtherwarpOverlay, sowie viele Utility-Klassen unter `cop/utils/` und `cop/api/`.

Betroffene Dateien u.a.:
- `cop/api/abobaui/**`
- `cop/api/skyblock/dungeon/odonscanning/**`
- `cop/utils/render/CustomRenderLayer.kt`
- `cop/utils/render/CustomRenderPipelines.kt`
- `cop/utils/render/WorldRenderContextUtils.kt`
- `cop/module/impl/dungeon/FuckDiorite.kt`
- diverse Puzzle-Solver unter `cop/module/impl/dungeon/puzzlesolvers/`

Source-Header in den jeweiligen Dateien.

## NoammAddons / CatgirlAddons — GPL-3.0

© Noamm9.

Konzepte und Ports für: F7 Boss Titles, Door Keys, Hidden Mobs, Ragnarock, Maxor Crystals, M7 Relics, Persistent Secret Heads, Inventory Search.

## CritsAddons — GPL-3.0

© noamm9.

Konzepte und Ports für: Secret Routes (inkl. der `assets/cop/secretRoutes.json` Route-DB mit ~150 Räumen), Auto RCM, Auto LCM, M3 Auto FF, M3 FF Display, Cooldown Display.

## Hunchclient — Quelle

Konzepte und Ports für: Bonzo Staff Helper, Custom Mage Beam.

## Athen — Quelle

Konzepte und Ports für: Arrow Hitboxes, Game Tint, Lag Detector, Snap Tap. Stonecutter-Setup-Inspiration für die Multi-Version-Build-Konfiguration.

## Nebulune — Quelle

Konzepte und Ports für: Etherwarp Helper, Fishing Helper, Auto Superboom.

## libautoupdate — MIT

© nea (moe.nea). Wird für das `Auto Updater`-Modul verwendet. Die Bibliothek wird über `include(...)` in die Mod-Jar gebündelt.

---

Falls ein Author hier fehlt oder eine Attribution falsch ist, bitte eine Issue auf [github.com/elv1n200/COP](https://github.com/elv1n200/COP) eröffnen.
