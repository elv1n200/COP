# Client-Test-Checkliste für mc26

[← Dokumentationsübersicht](README.md)

Diese Checkliste prüft einen COP-Release-Kandidaten auf Minecraft **26.1.2**. Sie ergänzt Gradle-Tests um die Dinge, die nur ein echter Client zeigt: Mixins, Rendering, Eingaben, Hypixel-Texte, GUIs und Paketverhalten.

> [!CAUTION]
> Teste automatisierte Module nur, wenn sie auf dem verwendeten Server erlaubt sind und du ihre Wirkung verstehst. Verwende für Recovery-Tests eine Kopie der Instanz und sichere `config/cop/`.

## Testdaten

- [ ] COP-Version/Commit: `________________`
- [ ] JAR-Datei: `cop-________________+mc26.1.2.jar`
- [ ] SHA-256: `________________`
- [ ] Java: `25 / ________________`
- [ ] Fabric Loader: `________________` (getesteter Mindeststand: `0.19.2`)
- [ ] Fabric API: `________________` (getesteter Mindeststand: `0.149.0+26.1.2`)
- [ ] Fabric Language Kotlin: `________________` (getesteter Mindeststand: `1.13.9+kotlin.2.3.10`)
- [ ] Betriebssystem/GPU/Treiber: `________________`
- [ ] Weitere Mods: `________________`

Die Prüfsumme lässt sich unter Windows mit `Get-FileHash <jar> -Algorithm SHA256` und unter Linux/macOS mit `sha256sum <jar>` erfassen.

## 1. Vorbereitungen

- [ ] Separates Minecraft-26.1.2-Profil angelegt oder vollständiges Backup erstellt.
- [ ] Nur eine COP-JAR im `mods/`-Ordner; sie enthält `+mc26.1.2` im Namen.
- [ ] Minecraft verwendet Java 25.
- [ ] Pflichtabhängigkeiten erreichen die oben genannten mc26-Mindeststände.
- [ ] `./gradlew :26.1.2:test` war erfolgreich.
- [ ] `./gradlew :26.1.2:build` war erfolgreich.

## 2. Kaltstart und Basis-UI

- [ ] Client startet ohne Mixin-Fehler, LinkageError oder COP-Crash bis ins Hauptmenü.
- [ ] Welt/Server lässt sich betreten und wieder verlassen.
- [ ] `/cop` und `/cope` öffnen dieselbe ClickGUI.
- [ ] Right Shift öffnet die ClickGUI, sofern der Keybind unverändert ist.
- [ ] Kategorien, Subkategorien, Suche, Scrollen und Tooltips reagieren korrekt.
- [ ] Switches, Slider, Textfelder, Listen, Farbwähler und Keybind-Erfassung lassen sich bedienen.
- [ ] `/cop hud` öffnet den Editor; Verschieben, Skalieren und Schließen funktionieren.
- [ ] Ein ungefährliches Testmodul lässt sich über `/cop toggle <module>` ein- und ausschalten.

## 3. Diagnose, Speicherung und Recovery

- [ ] `/cop diagnostics` kopiert einen lesbaren Markdown-Report.
- [ ] Report enthält die korrekten Runtime-Versionen sowie Mod-/Modullisten.
- [ ] Report enthält aktivierte Modulnamen, aber keinen Spielernamen, keine Serveradresse, keine Tokens, keine einzelnen Einstellungswerte und keine Dateipfade.
- [ ] Eine harmlose Einstellung ändern, Client normal schließen und neu starten: Wert bleibt erhalten.
- [ ] Recovery nur in der Testkopie: Client schließen und `config/cop/cop-config.json` an einen sicheren Ort kopieren.
- [ ] Die Testkopie von `cop-config.json` absichtlich durch ungültiges JSON ersetzen, beispielsweise `{broken`, und den Client neu starten.
- [ ] Der Neustart verwendet Modul-Defaults, crasht nicht und erzeugt im selben Ordner `cop-config.json.corrupt-<hash>.bak`.
- [ ] Client wieder schließen und die zuvor gesicherte `cop-config.json` wiederherstellen.

## 4. Chat und Commands

- [ ] Normale, System-, Party- und formatierte Nachrichten erscheinen ohne Duplikate.
- [ ] Chat-Suche aktivieren, Treffer prüfen und Suche wieder verlassen; Verlauf und Reihenfolge bleiben erhalten.
- [ ] Während aktiver Suche neue Nachrichten empfangen; nach dem Beenden erscheint jede genau einmal.
- [ ] Während aktiver Suche eine Nachricht löschen/entfernen lassen; sie taucht danach nicht wieder auf.
- [ ] Links, Hover-Texte, Signaturen/Tags und Click-Actions einer sichtbaren Nachricht funktionieren weiter.
- [ ] `/clearchat` leert den sichtbaren Verlauf ohne Folgefehler.
- [ ] `/cop loot today`, `/cop alwaysbuy list` und `/cop worthless list` reagieren ohne offenen Container sicher.
- [ ] **Chat Waypoints**: eine Party-Nachricht im Format `x: 10, y: 70, z: -20` erzeugt genau einen Marker mit richtigem Absender; Systemmeldungen mit Koordinaten erzeugen keinen.
- [ ] Public-Chat-Waypoints bleiben mit der Standardeinstellung aus; nach bewusstem Aktivieren funktionieren Ablaufzeit, Ankunfts-Radius und Weltwechsel-Reset.
- [ ] Ungültige Argumente erzeugen eine verständliche Meldung statt eines Crashes.

## 5. Rendering und Performance

- [ ] HUDs rendern in GUI-Scale 1, 2 und der üblichen persönlichen Skalierung korrekt.
- [ ] World-Overlays zeigen Linien, Boxen, Flächen und Text ohne Flackern oder fehlende Geometrie.
- [ ] Mehrere Overlays gleichzeitig erzeugen keine sichtbaren Batch-/Layer-Artefakte.
- [ ] ClickGUI mehrfach öffnen/schließen und Welt mehrfach wechseln: keine wachsenden UI-Artefakte oder Native-Resource-Fehler im Log.
- [ ] Fenstergröße/Vollbild wechseln; UI und Overlays passen sich an.
- [ ] Kurzer FPS-Vergleich mit denselben Overlays an/aus zeigt keine unerwartete starke Regression.

## 6. Dungeon Map

- [ ] Einen frischen Dungeon mit aktivierter Option **Show undiscovered layout** betreten: Das anfängliche Layout erscheint vollständig, sobald die lokale Magical Map verfügbar ist, ohne dass zuerst jeder Raum geladen werden muss.
- [ ] Das anfängliche Layout mit der Vanilla-Magical-Map vergleichen: Raumformen, zusammenhängende Mehrfachräume, Türen und Abzweigungen stimmen überein; es erscheinen keine zusätzlichen Verbindungen.
- [ ] Während des Runs mehrere Räume betreten und Karten-Updates abwarten: Bereits erkannte Räume verschwinden, springen oder duplizieren sich nicht.
- [ ] Mindestens drei neue Runs auf unterschiedlichen Floors testen, darunter möglichst ein kleiner Floor und F6/F7: Kalibrierung, Kartengröße und Eingangsausrichtung passen jeweils zum aktuellen Run.
- [ ] Zwei Räume mit identischem Anzeigenamen im selben Run finden: Beide bleiben an ihrer eigenen Position sichtbar und werden weder zusammengeführt noch gegenseitig überschrieben.
- [ ] Spieleranzeige mit eigenem Spieler und mehreren Teammates prüfen: Köpfe/Marker, Namen, Position und Blickrichtung gehören zum richtigen Spieler und bewegen sich plausibel.
- [ ] Tod und Wiederbelebung eines Teammates prüfen: Ein toter Spieler erzeugt keinen eingefrorenen oder einem anderen Spieler zugeordneten Marker; nach der Wiederbelebung wird er wieder korrekt angezeigt.
- [ ] Einen Teammate außerhalb des sichtbaren Kartenausschnitts bzw. ohne gültige Map-Position prüfen: Sein Marker bleibt ausgeblendet oder sauber am Rand und springt nicht auf `(0, 0)` bzw. einen fremden Spieler.
- [ ] Fenstergröße, Vollbildmodus, GUI-Scale und Dungeon-Map-HUD-Größe während eines Runs ändern: Karte, Texte und Spieler-Marker bleiben zentriert, scharf und innerhalb des HUDs.
- [ ] Dungeon verlassen und einen neuen Run starten: Layout, Raumzustände, Spielerpositionen, Kartengröße und Mimic-Status des vorherigen Runs sind vollständig zurückgesetzt.
- [ ] Außerhalb des Dungeons eine normale Karte und innerhalb eines passenden Puzzles eine Puzzle-/Spezialkarte aktualisieren lassen: Diese Karten überschreiben weder Dungeon-Layout noch Spielerpositionen.
- [ ] Auf F6/F7 einen Run mit Mimic testen: Der korrekte Raum erhält den Mimic-Status erst nach gültiger Erkennung; nach Mimic-Kill bzw. spätestens im nächsten Run bleibt keine veraltete Markierung zurück.
- [ ] Dieselben Prüfungen einmal mit deaktivierter Option **Show undiscovered layout** wiederholen: Unentdeckte Räume werden nicht vorab ergänzt, normale Scan- und Statusupdates funktionieren weiterhin.
- [ ] **Dungeon Score HUD** mit Tab-/Scoreboard-Werten vergleichen; lokale 270- und 300-Hinweise erscheinen höchstens einmal pro Run und senden keine Chatnachricht.
- [ ] **Blessing HUD** nach mehreren Blessings sowie nach einem neuen Run prüfen: Typen und Stufen stimmen, alte Werte bleiben nicht erhalten.
- [ ] **Room Alerts**: beim Wechsel auf weißen und grünen Raumstatus erscheint der jeweils aktivierte lokale Hinweis genau einmal; beim Betreten eines bereits fertigen Raums, außerhalb des eigenen Raums und nach dem Bossstart gibt es keinen verspäteten Hinweis.
- [ ] Room Alerts in einem neuen Run erneut prüfen: Zustände des vorherigen Runs bleiben nicht gespeichert; Räume ohne Secrets lösen keinen „Secrets Done“-Hinweis aus.
- [ ] **Warp Cooldown** beim Eintritt in eine Instanz prüfen: Countdown startet nur auf der echten Eintrittsmeldung, endet nach 30 Sekunden und wird bei Disconnect zurückgesetzt.
- [ ] Falls die optionale `/joininstance`-Sperre aktiv ist: ein früher Befehl wird lokal verständlich blockiert, nach Ablauf aber unverändert gesendet.
- [ ] **Terminal Waypoints** in allen vier Goldor-Abschnitten prüfen: standardmäßig ist nur der aktuelle Abschnitt sichtbar; unbekannte Abschnittsdaten blenden keine benötigten Marker aus.
- [ ] Ein Terminal öffnen/abschließen und dessen Armor-Stand-Status beobachten: nur serverseitig als aktiv bestätigte Terminals verschwinden, unbekannte oder verpasste Zustände bleiben als sicherer Fallback sichtbar; Levers bleiben erhalten.

## 7. Funktions-Smoke-Tests

- [ ] Mindestens ein Puzzle-Overlay mit bekanntem Lösungsweg geprüft.
- [ ] Secret Routes: Raumwechsel, nächster Schritt und Reset in einem neuen Run geprüft.
- [ ] HUD-Timer/-Zähler starten und stoppen am erwarteten Event.
- [ ] Leap-Menü und Dungeon-Commands reagieren nur im passenden Kontext.
- [ ] Crystal-Hollows-Map/-Scanner in der richtigen Area geprüft; außerhalb keine falschen Anzeigen.
- [ ] Inventory-Namens-/Lore-Suche, Pet-/Wardrobe-Keybinds und ein Player-/Render-Modul jeweils kurz geprüft.
- [ ] **Item Protection**: ein eindeutiges Item per `/cop protect toggle` und per Schutz-Klick markieren; Q-Drop, Drop außerhalb des Inventars, Shift-Klick in Sell/Salvage und normaler ungefährlicher Inventartransfer verhalten sich jeweils korrekt.
- [ ] Item Protection mit UUID-losen Items, Sternen/Recombobulation und `/cop protect id|list|clear` prüfen; keine Duplikate oder veralteten Marker nach Neustart.
- [ ] In einem Dungeon prüfen, dass die standardmäßig erlaubte Drop-Taste die Klassenfähigkeit nicht blockiert; anschließend die Option testweise deaktivieren und die Schutzwirkung verifizieren.
- [ ] **Commission Display** in Dwarven Mines/Crystal Hollows prüfen: Namen und Fortschritt entsprechen der Tabliste, verschwinden beim Weltwechsel und erzeugen den Abschluss-Titel nur bei der echten Servermeldung.
- [ ] **Mining Ability Alert** mit einer echten „is now available!“-Meldung prüfen; ähnlich aussehende normale Chatnachrichten dürfen keinen Titel auslösen.
- [ ] Auto Croesus zunächst nur lesend: GUI-Erkennung und Preis-/Profit-Anzeige geprüft, keine unbeabsichtigte Aktion.

## 7a. Neue Dungeon-Automation

> [!IMPORTANT]
> Zuerst jedes Modul **einzeln** mit konservativen Delays testen. Erst danach
> sinnvolle Kombinationen aktivieren und im Log auf übersprungene Aktionen des
> Automation-Coordinators achten. Auto Clear bleibt von diesem Testblock ausgenommen.

- [ ] ClickGUI: **Dungeon → Quality of Life** enthält Dungeon Potion, Architect Draft und Auto Requeue; **Dungeon → Cheats & Automation** enthält die neuen Boss-/Combat-Module.
- [ ] **Auto Terms**: Numbers, Panes, Starts-With, Colors, Rubix und Melody einzeln prüfen; bei künstlich höherem Ping wird immer erst die Serverbestätigung abgewartet, Farben wie Silver/White/Blue treffen auch Light Gray/Wool/Bone/Lapis und schwarze Füll-Panes werden nie geklickt.
- [ ] Auto Terms mit Golden Apple bzw. einem intrinsisch glitzernden gültigen Starts-With-Item prüfen: genau ein Klick; ein absichtlich verworfener/ausbleibender Server-Ack wird nach Timeout erneut versucht.
- [ ] **Auto Leap**: konfigurierte Namen/Klassen, S1–S4, P1/Predev, Green/Yellow/Purple, I4, Middle, P5 und Relic separat prüfen; unterhalb der Pads sowie an den Safe-Spots darf kein Leap auslösen.
- [ ] **Auto I4** erst ohne Support-Aktionen testen: S4-Erkennung, Emerald-Ziel, Prediction, Mouse-Move-Abbruch und Stall-Retry; eigene S1–S3-Device-Meldungen dürfen I4 nicht als fertig markieren.
- [ ] Auto I4 anschließend mit Rod, Mask und Leap testen; Weltwechsel, Tod, Terminalöffnung und manuelle Mausbewegung brechen eigene Tasks/Leases sauber ab.
- [ ] **Dungeon Abilities** je Klasse mit passendem Boss-Trigger prüfen; falsche Klasse/Floor oder doppelter Boss-Text erzeugen keinen zweiten Cast.
- [ ] **Door Opener** in Triggerbot und Aura: ausschließlich noch geschlossene, gescannte Wither-/Blood-Türen in Reichweite öffnen; keine normale Tür oder bereits offene Position anklicken.
- [ ] **Auto Dungeon Potion** mit leerem Slot, vollem Inventar, vorhandenem Potion-Tier, fehlendem Cookie und fehlender Potion testen; verstecktes Menü wird auf jedem Erfolgs-/Fehlerpfad geschlossen.
- [ ] Legacy **Auto Potion Bag** und Auto Dungeon Potion gemeinsam aktivieren: die alte Funktion öffnet kein zweites konkurrierendes Potion-Bag-Menü.
- [ ] **Auto Invincibility** mit Spirit → Phoenix → Bonzo prüfen; Pet-Menu- und Rod-Autopet-Modus getrennt, vorheriges Pet nach Phoenix-Proc/Timeout wiederhergestellt und keine parallelen Maskenmenüs mit Auto I4.
- [ ] **Auto Wither Cloak** am konfigurierten F7-Countdown prüfen; ursprünglicher Hotbar-Slot wird wiederhergestellt und verspätete Tasks feuern nach Weltwechsel/Disable nicht.
- [ ] **Barrier Boom** in S1, S2 und S3: nur die echte Gate-Fläche und nur in Reichweite akzeptieren; andere Barrier-Blöcke sowie gleichzeitiges Auto Superboom lösen keine Doppelaktion aus.
- [ ] **Debuff Helper** mit Last Breath: Server-Swing-/Use-Sequenz, Ladezeit, Dragon-/Phase-Auswahl, Release/Redraw und Abbruch bei Itemwechsel/Weltwechsel prüfen.
- [ ] **M3 Auto FF** nur in M3 auslösen; F3 darf nichts tun. Staff-Swap, optionale Reposition, Cast und Swap-back sowie Screen/Tod/Weltwechsel während der fünf Sekunden prüfen.
- [ ] **Dungeon Breaker** Triggerbot und Aura getrennt testen; gespeicherte Position ist nach dem Cooldown erneut verwendbar, FOV/Range/Delay und Inventar-Sperre stimmen.
- [ ] **Architect Draft** nur nach eigenem Puzzle-Fail; Item wird geholt, aber nicht automatisch benutzt. Party-Ankündigung nur bei erfolgreichem GFS.
- [ ] **Auto Requeue** als Leader/Nicht-Leader, volle/unvollständige Party, F/M-Floor und Weltwechsel während des Delays prüfen.
- [ ] **Auto GFS Twilight** an Lightning/Core/P5 sowie normales Pearl/Boom/Jerry/Leap-Refill prüfen; höchstens ein GFS-Befehl pro Cooldown und keine negativen/überfüllenden Mengen.

## 7b. Allgemeine, Economy-, Slayer- und Player-Automation

- [ ] ClickGUI-Gruppen **General Automation**, **Economy Automation**, **Slayer Automation**, **Dojo Automation**, **Player → Cheats & Automation** und **Mining → Cheats & Automation** erscheinen in der vorgesehenen Reihenfolge und lassen sich unabhängig einklappen.
- [ ] **Auto Hotbar**: Preset speichern/laden/löschen, mehrere gleiche SkyBlock-IDs mit unterschiedlichen UUIDs, fehlendes Item, voller Bestand, Chat-Trigger und ein währenddessen geöffnetes fremdes Menü prüfen.
- [ ] **Auto Loadout/Wardrobe**: gültige, leere, gesperrte und bereits aktive Slots; Timeout/Disable/Weltwechsel schließen nur den eigenen versteckten Container und hinterlassen keine festhängenden Tasten.
- [ ] Während Dungeon Potion, Mask/Phoenix, Loadout oder Wardrobe auf ein verstecktes Menü warten, absichtlich ein anderes Menü öffnen bzw. die Welt wechseln: der alte Ablauf muss abbrechen und darf das neue Menü weder anklicken noch schließen.
- [ ] **Auto Sell** ausschließlich mit einem absichtlich hinzugefügten Billig-Item testen; geschützte UUID/ID, Sterne, Recombobulation, Reforge und Enchants verhindern den Verkauf wie konfiguriert.
- [ ] **Party Auto Kick** als Leader/Nicht-Leader sowie während Command-Cooldown; keine Selbst-Kicks, stale Party-Daten oder ungeplanten Transfers, Pending-Regel wird sicher erneut geprüft.
- [ ] **Chocolate Factory**: pro Tick maximal eine Aktion, Strays nur im Factory-Container, K/M/B/T-Werte korrekt und Auto Upgrade zunächst deaktiviert; später mit kleinem, kontrolliertem Upgrade testen.
- [ ] **Blaze Slayer Automation** nur am direkt anvisierten eigenen Kampfziel; Air-Swings/fremde Bosse wechseln keinen Dagger. DDR-/Fire-Dodge endet sofort ohne Gefahr oder bei Disable/Weltwechsel.
- [ ] **Dojo Automation** je Test einzeln: Force, Discipline, Mastery, Stamina, Swiftness und Control; Rank-Ende stoppt Eingaben/Tasks und ein verzögerter Discipline-Klick schlägt danach nicht mehr.
- [ ] **Auto Carnival** nur in der Shootout-Arena mit Dart Tube; Dekozombies/Lampen außerhalb, unsichtbare Ziele und Weltwechsel werden ignoriert.
- [ ] **Auto Join SkyBlock** wartet auf Command-Readiness, versucht begrenzt erneut und feuert nicht bei Nicht-Hypixel-/bereits-SkyBlock-Verbindungen.
- [ ] **Escrow Fix** reagiert nur auf echte AH-/Bazaar-Fehler, nicht auf normale Claim-/Visit-Erfolgsmeldungen.
- [ ] **No Rotate** für Etherwarp, AOTE/AOTV und Wither Impact: Kamera bleibt ohne Ein-Tick-Snap stabil; normale Serverkorrektur bleibt bei ausgeschalteter Optional-Option unverändert.
- [ ] Optionale **Zero-Ping-Camera** je Teleportart: korrekte First-Person-Position, keine falsche Block-/Frustum-Position, Third Person bleibt unverändert und Server-Ack beendet die Prediction.
- [ ] **Defensive Blink** mit eigenem Keybind: maximal 500 ms/48 Movement-Pakete, Flush vor Interaktion; bei Server-Teleport/Korrektur wird die alte Queue verworfen statt an die neue Position gesendet.
- [ ] **No Break Reset** nur während aktivem Blockabbau mit ausgewähltem Hotbar-Update; versteckte Loadout-/Wardrobe-Container und normale Slotupdates verändern keinen Break-State.

## 8. Externe Funktionen

- [ ] Preisabruf reagiert auf Erfolg, Timeout und fehlende Daten ohne Client-Hänger.
- [ ] Modul **Auto Updater** aktivieren, **Auto download** deaktiviert lassen und dem Eintrag **Check now** vorübergehend eine Taste zuweisen.
- [ ] Den Check-now-Key drücken: Ein passendes mc26-Asset wird erkannt und ohne Zustimmung nicht heruntergeladen; danach Test-Keybind und Modul wieder zurücksetzen.
- [ ] Wenn Windows genutzt wird: Spotify-HUD mit Wiedergabe, Pause, Trackwechsel und geschlossenem Player geprüft.
- [ ] Wenn nicht Windows genutzt wird: Spotify-Funktion bleibt deaktiviert/harmlos und verursacht keinen Startfehler.

## 9. Abschluss

- [ ] `latest.log` enthält keine neuen COP-Exceptions, Mixin-Fehler oder wiederholte Warnschleifen.
- [ ] Weltwechsel, Disconnect und Client-Shutdown funktionieren sauber.
- [ ] Bekannte Abweichungen sind unten notiert und als Issue angelegt, wenn sie release-blockierend sind.
- [ ] Testinstanz/Config-Backup wurde wieder in den gewünschten Zustand gebracht.

## Ergebnis

```text
Ergebnis: PASS / PASS MIT EINSCHRÄNKUNGEN / FAIL
Getestet am:
Getestet von:
Nicht getestete Abschnitte:
Beobachtungen/Issue-Links:
```
