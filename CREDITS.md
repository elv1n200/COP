# Credits

COP enthält portierten oder von Konzepten dieser Open-Source-Mods abgeleiteten Code. Diese Datei listet die Quellen mit ihren Lizenzen — die jeweiligen Quelldateien tragen zusätzlich Copyright-Header wo es die Lizenz vorschreibt.

## Quoi — siehe [Repository](https://github.com/pigeonlover1998/quoi)

© [pigeonlover1998](https://github.com/pigeonlover1998).

UI-Framework `abobaui` (Element-/Constraint-/Layout-System), NVG-Renderer, ClickGUI-Grundstruktur, das Modul-/Event-/HUD-/Settings-DSL. Bildet die strukturelle Basis von COP — fast jede UI-Interaktion läuft durch hier portierte Klassen.

Betroffene Dateien u.a.:
- `cop/api/abobaui/**`
- `cop/utils/ui/rendering/NVG*`
- `cop/utils/ui/elements/**`
- Teile von `cop/module/settings/**`

## Odin — BSD 3-Clause

© [odtheking](https://github.com/odtheking), 2025–2026.

Konzepte und Ports für: das Odon-Scanning-System, der größte Teil der Puzzle-Solver (Beams / Blaze / Ice Fill / Ice Path / Maze / Quiz / TicTacToe / Water / Weirdos), FuckDiorite, EtherwarpOverlay sowie diverse Utility-Klassen unter `cop/utils/`.

Betroffene Dateien u.a.:
- `cop/api/skyblock/dungeon/odonscanning/**`
- `cop/utils/render/CustomRenderLayer.kt`
- `cop/utils/render/CustomRenderPipelines.kt`
- `cop/utils/render/WorldRenderContextUtils.kt`
- `cop/module/impl/dungeon/worldrender/FuckDiorite.kt`
- diverse Solver unter `cop/module/impl/dungeon/solvers/`

Source-Header in den jeweiligen Dateien.

## OdinClient Fabric / Athen / Nebulune — siehe Repository

© [skies-starred](https://github.com/skies-starred).

OdinClient Fabric ist der Fabric-Port der ursprünglich von odtheking entwickelten Odin-Mod und brachte die Fabric-Plattform-Anpassungen die COPs Stonecutter-Multi-Version-Setup mit beeinflusst haben.

Konzepte und Ports aus **Athen** (Package `xyz.aerii.athen`): Arrow Hitboxes, Game Tint, Lag Detector, Snap Tap, TerminalWaypoints (Koordinaten-Tabelle). Stonecutter-Setup-Inspiration für die Multi-Version-Build-Konfiguration.

Konzepte und Ports aus **Nebulune** (Package `xyz.aerii.nebulune`): Etherwarp Helper, Fishing Helper, Auto Superboom, AutoSoulcry (leichtere Variante).

## NoammAddons / CatgirlAddons — GPL-3.0

© [Noamm9](https://github.com/noamm9).

Konzepte und Ports für: F7 Boss Titles, Door Keys, Hidden Mobs, Ragnarock, Maxor Crystals, M7 Relics, Inventory Search, PersonalBest Helper.

## CritsAddons — siehe [Repository](https://github.com/FateShop/CritsAddons)

© [FateShop](https://github.com/FateShop).

Konzepte und Ports für: Auto RCM, Auto LCM, M3 Auto FF, M3 FF Display, Cooldown Display, Persistent Secret Heads.

(Hinweis: die `Port of …`-Header in den Source-Dateien referenzieren teils noch das alte `com.github.noamm9.critsaddons.*`-Package — das ist nur der Build-Pfad, nicht die Authorenangabe.)

## Hunchclient — siehe Repository

Konzepte und Ports für: Custom Mage Beam.

## libautoupdate — MIT

© [nea (moe.nea)](https://github.com/nea89o). Wird für das `Auto Updater`-Modul verwendet. Die Bibliothek wird über `include(...)` in die Mod-Jar gebündelt.

---

Falls ein Author hier fehlt oder eine Attribution falsch ist, bitte eine [Issue auf GitHub](https://github.com/elv1n200/COP/issues/new?template=bug_report.yml) eröffnen.
