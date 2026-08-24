# Credits

COP enthält portierten Code aus entsprechend lizenzierten Projekten sowie
eigenständige Implementierungen, die sich an offen beobachtbarem Verhalten
anderer Mods orientieren. Diese Datei trennt beide Fälle und nennt Quellen und
Lizenzen; die jeweiligen Quelldateien tragen zusätzlich Copyright-Header, wo
die Lizenz es vorschreibt.

## Quoi-Codefamilie — GPL-3.0-only

Der für die mc26-Überarbeitung geprüfte Quoi-Snapshot `1.1.1+26.1` nennt
**frogs** und **jcnlk** als Autoren und verweist auf
[jcnlk/quoi](https://github.com/jcnlk/quoi). Ältere COP-Port-Hinweise
verweisen außerdem auf [pigeonlover1998/quoi](https://github.com/pigeonlover1998/quoi)
und © pigeonlover1998; diese historische Herkunft bleibt erhalten.

UI-Framework `abobaui` (Element-/Constraint-/Layout-System), NVG-Renderer,
ClickGUI-Grundstruktur und das Modul-/Event-/HUD-/Settings-DSL bilden die
strukturelle Basis von COP. Der aktuelle Snapshot diente zusätzlich als
Konzeptreferenz für Chat-Waypoints, Commission Display, Mining Ability Alert,
Warp Cooldown, die renderseitige Entity-Unterdrückung sowie die neu geschriebene
Dungeon-Automation für Klassenskills, gescannte Türen, Dungeon-Potions,
Invincibility-Fallbacks, Wither Cloak und Barrier Boom. Die neuen
Implementierungen für Hotbar-/Loadout-/Wardrobe-Abläufe, Carnival, Party-Regeln,
Chocolate Factory, Auto Sell, Escrow-Retries, Blaze/Dojo sowie No Rotate,
Defensive Blink und No Break Reset orientieren sich ebenfalls nur am dort
beobachteten Verhalten. Sie wurden für COP neu geschrieben und übernehmen weder
Quoi-Netzwerkcode noch fremde Endpunkte oder Assets.

Betroffene Dateien u.a.:
- `cop/api/abobaui/**`
- `cop/utils/ui/rendering/NVG*`
- `cop/utils/ui/elements/**`
- Teile von `cop/module/settings/**`

## Odin — BSD 3-Clause

© [odtheking](https://github.com/odtheking), 2025.

Konzepte und Ports für: das Odon-Scanning-System, der größte Teil der Puzzle-Solver (Beams / Blaze / Ice Fill / Ice Path / Maze / Quiz / TicTacToe / Water / Weirdos), FuckDiorite, EtherwarpOverlay sowie diverse Utility-Klassen unter `cop/utils/`.

Betroffene Dateien u.a.:
- `cop/api/skyblock/dungeon/odonscanning/**`
- `cop/utils/render/CustomRenderLayer.kt`
- `cop/utils/render/CustomRenderPipelines.kt`
- `cop/utils/render/WorldRenderContextUtils.kt`
- `cop/module/impl/dungeon/worldrender/FuckDiorite.kt`
- diverse Solver unter `cop/module/impl/dungeon/solvers/`

Source-Header in den jeweiligen Dateien.

## dtMap — BSD 3-Clause

© 2026 rice.who. [Source](https://github.com/ricedotwho/dtMap)

Für die Dungeon-Map-Überarbeitung diente dtMap als Konzeptreferenz für die lokale Ableitung der Map-Topologie. Weder das dtMap-Archiv noch dessen Runtime- oder Netzwerkcode werden von COP gebündelt.

## NoammAddons 26.1.2 — CC0 1.0 Universal im Repository-Root

Der geprüfte [NoammAddons-Snapshot](https://github.com/Noamm9/NoammAddons)
enthält im Repository-Root die Lizenz **CC0 1.0 Universal**, umfasst jedoch
auch eingebettete Komponenten und Assets mit eigenen oder nicht eindeutig
dokumentierten Herkünften. Deshalb wurde er ausschließlich als
Konzeptvergleich für Dungeon Map, Item Protection, Dungeon Score/Blessing HUD,
Room Alerts, aktive Terminal-Anzeigen, Auto I4, Last-Breath-Debuffs,
Dungeon-Requeue, Architect's Draft und M7-Twilight-Refills genutzt. COP bündelt daraus weder
Archive, Assets, Runtime-/Netzwerkcode, WebSocket-Logik noch Noamm-Endpunkte;
die genannten COP-Funktionen sind eigenständige Implementierungen.

## OdinClient Fabric / Athen / Nebulune — BSD 3-Clause

© [skies-starred](https://github.com/skies-starred).

OdinClient Fabric ist der Fabric-Port der ursprünglich von odtheking entwickelten Odin-Mod und brachte die Fabric-Plattform-Anpassungen die COPs Stonecutter-Multi-Version-Setup mit beeinflusst haben.

Konzepte und Ports aus **Athen** (Package `xyz.aerii.athen`): Arrow Hitboxes, Game Tint, Lag Detector, Snap Tap, TerminalWaypoints (Koordinaten-Tabelle). Stonecutter-Setup-Inspiration für die Multi-Version-Build-Konfiguration.

Konzepte und Ports aus **Nebulune** (Package `xyz.aerii.nebulune`): Etherwarp Helper, Fishing Helper, Auto Superboom, AutoSoulcry (leichtere Variante).

## Ältere NoammAddons- / CatgirlAddons-Referenzen — GPL-3.0

© [Noamm9](https://github.com/noamm9).

Konzepte und Ports für: F7 Boss Titles, Door Keys, Hidden Mobs, Ragnarock, Maxor Crystals, M7 Relics, Inventory Search, PersonalBest Helper.

Dieser historische Eintrag ist vom oben genannten, aktuell geprüften NoammAddons-26.1.2-Snapshot unter CC0 1.0 Universal getrennt.

## CritsAddons — Verhaltensreferenz

© [FateShop](https://github.com/FateShop).

Das öffentliche Repository ist mit `All rights reserved` gekennzeichnet.
Ältere COP-Revisionen bezeichneten Auto RCM, Auto LCM, M3 Auto FF und
Persistent Secret Heads noch als Ports. Für `1.8.0-beta.1` wurden diese Dateien
sowie M3 FF Display und Cooldown Display vollständig durch Clean-Room-
Implementierungen anhand einer Funktionsbeschreibung und der öffentlichen
COP-APIs ersetzt. Der aktuelle Quellbaum und die daraus erzeugten Artefakte
enthalten keinen CritsAddons-Implementierungscode.

## RandomStuff / AutoCroesus — historische Verhaltensreferenz

Das öffentliche [AutoCroesus-Projekt](https://github.com/UnclaimedBloom6/RandomStuff/tree/main/AutoCroesus)
veröffentlicht keine Repository-weite Softwarelizenz. Der frühere Parser wurde
deshalb für `1.8.0-beta.1` durch einen Clean-Room-Zustands- und Tokenparser
ersetzt. Der aktuelle Parser nutzt ausschließlich COP-APIs sowie die sichtbaren
Hypixel-Menü- und Lore-Daten.

## Weitere direkt attribuierte Quellen

- **Material Color Utilities / HCT** — Apache-2.0, © 2025 Google LLC.
- **Stella** und **Skyblocker** — LGPL-3.0-only; modifizierte Hilfs-/Solverteile
  werden als Teil des vollständig veröffentlichten COP-Source vertrieben.
- **rsm** — BSD 3-Clause, © 2026 rice.who (`MutableInput`).
- **NoobRoutes** — Unlicense (`SecretAura`).
- **devonian**, **Meteor Client**, **GumTuneClient** und
  **Client-Custom-Name** — GPL-3.0-kompatible, in den jeweiligen Quelldateien
  verlinkte Referenzen bzw. angepasste Teile.

## Secret Routes Mod — GPL-3.0

© [yourboykyle](https://github.com/yourboykyle) & [R-aMcC (wyannnnn)](https://github.com/R-aMcC), mit Routen-Daten von [itplays](https://github.com/itplays) und [zzyyrraa](https://github.com/zzyyrraa).

Liefert die Secret-Routen-Daten (`routes.json` + `pearlroutes.json`) die das `Secret Routes`-Modul rendert. Die JSON-Dateien sind verbatim aus dem [Secret Routes Mod Repo](https://github.com/yourboykyle/SecretRoutes) unter `src/main/resources/assets/cop/secretroutes/` mitgebündelt, inklusive der originalen `#origin` / `#copyright` Header-Keys.

Die COP-seitige Render-/Lookup-Implementierung ist eigenständig (room detection läuft über Odin Scanning statt der dort verwendeten Skeleton-Matches, daher müssen wir die ~17 MB Skeleton-Assets nicht bündeln).

## libautoupdate — BSD 2-Clause

© Linnea Gräf. [Source](https://git.nea.moe/nea/libautoupdate/). Wird für das `Auto Updater`-Modul verwendet. Die Bibliothek wird über `include(...)` in die Mod-Jar gebündelt.

## ClassGraph — MIT

© Luke Hutchison. [Source](https://github.com/classgraph/classgraph). Wird für die Annotation-/Klassen-Suche verwendet und über `include(...)` gebündelt.

## LWJGL NanoVG — BSD 3-Clause

© Lightweight Java Game Library. [Source](https://github.com/LWJGL/lwjgl3). Die NanoVG-Bindings und nativen Bibliotheken für Windows, Linux und macOS werden über `include(...)` gebündelt.

---

Falls ein Author hier fehlt oder eine Attribution falsch ist, bitte eine [Issue auf GitHub](https://github.com/elv1n200/COP/issues/new?template=bug_report.yml) eröffnen.
