# Auto Croesus

[← Dokumentationsübersicht](README.md)

Auto Croesus unterstützt das Croesus-Menü im Dungeon Hub. Das Modul markiert noch nicht abgeholte Durchläufe, berechnet Kosten, Wert und Gewinn der verfügbaren Truhen und kann auf Tastendruck eine einzelne Truhe, mehrere Truhen eines Durchlaufs oder mehrere Durchläufe nacheinander bearbeiten.

> [!CAUTION]
> Die automatische Bedienung ist standardmäßig deaktiviert. Prüfe vor der Nutzung die Regeln des Servers und beobachte einen neuen Ablauf zunächst vollständig. Preisangaben externer Dienste können fehlen oder veraltet sein.

## Schnellstart

1. Stelle dich im Dungeon Hub in die Nähe von Croesus.
2. Öffne `/cop` und aktiviere unter **Dungeons → Qol → Auto Croesus** das Modul.
3. Aktiviere **Auto claim (master)**. Ohne diesen Hauptschalter löst die Claim-Taste keine Aktion aus.
4. Weise **Claim best chest** eine Taste zu.
5. Öffne zunächst einen einzelnen Durchlauf, prüfe das Gewinn-Overlay und teste eine einzelne Truhe.
6. Aktiviere **Multi-run claim** oder **Chain claim (this run)** erst, nachdem der Einzelablauf korrekt funktioniert.

## Einstellungen

### Anzeige

Diese Optionen ändern nur die Darstellung.

| Einstellung | Standard | Wirkung |
|---|---:|---|
| **Unclaimed highlight** | Grün | Rahmen um Durchläufe mit noch nicht abgeholten Truhen |
| **Border width** | 2 px | Breite des Rahmens |
| **Show profit overlay** | An | Zeigt Kosten, Gesamtwert und Gewinn pro Truhe |
| **Highlight best chest** | An | Markiert die Truhe mit dem höchsten berechneten Gewinn |
| **Best-chest highlight** | Gold | Farbe der Markierung für die beste Truhe |
| **Refresh rate** | 5 t | Intervall, in dem ein geöffnetes Truhenmenü neu ausgewertet wird |
| **Debug dump key** | Nicht belegt | Schreibt Titel, Slots und Lore des Menüs sowie IDs aus dem Hauptinventar in `latest.log` |

### Automatisches Abholen

| Einstellung | Standard | Wirkung |
|---|---:|---|
| **Auto claim (master)** | **Aus** | Hauptschalter für alle automatischen Claim-Aktionen |
| **Claim best chest** | Nicht belegt | Startet je nach geöffnetem Menü einen Einzel- oder Multi-Run-Ablauf |
| **Min profit** | **100.000 Coins** | Eine reguläre Truhe wird nur bei einem Gewinn von **mindestens** diesem Wert gekauft (`Gewinn >= Min profit`) |
| **Claim timeout** | 60 t | Bricht einen festhängenden Ablauf ab; 20 Ticks entsprechen ungefähr einer Sekunde |

`Min profit = 0` entfernt die reguläre Gewinnuntergrenze. Always-Buy-Truhen bilden eine ausdrücklich konfigurierte Ausnahme; Kismet kann eine zunächst zu schwache Truhe nach einem Neuwurf noch über die Grenze bringen.

### Mehrere Truhen eines Durchlaufs

| Einstellung | Standard | Wirkung |
|---|---:|---|
| **Chain claim (this run)** | Aus | Kehrt nach einem Kauf in denselben Durchlauf zurück und wählt die nächste geeignete Truhe. Zusätzliche Truhen benötigen Dungeon Chest Keys. |

Die Kette endet, sobald keine weitere reguläre Truhe `Min profit` erreicht und keine andere konfigurierte Ausnahme greift.

### Mehrere Durchläufe und Seiten

| Einstellung | Standard | Wirkung |
|---|---:|---|
| **Multi-run claim** | Aus | Öffnet nacheinander noch nicht abgeholte Durchläufe und verarbeitet pro Durchlauf die beste geeignete Truhe. Zusammen mit Chain Claim können mehrere Truhen je Durchlauf gekauft werden. |
| **Multi-run pacing** | **6 t** | Sicherheitsabstand zwischen serverabhängigen Aktionen; Wertebereich 3–20 Ticks |

Sind auf der aktuellen Croesus-Seite keine offenen Durchläufe mehr vorhanden, prüft COP **Slot 53** auf eine Schaltfläche namens `Next Page`. Pro Multi-Run-Zyklus werden aus Sicherheitsgründen höchstens **fünf Seitenwechsel** ausgeführt. Nach jedem Wechsel wartet das Modul erneut auf vollständig geladene Slot-Daten.

`Multi-run pacing` wird an zwei Stellen verwendet:

- Nachdem die Croesus-Liste in den Slots 4 und 49 als geladen erkannt wurde, wartet COP standardmäßig weitere 6 Ticks vor dem nächsten Klick.
- Nach einem Kauf muss `mc.screen` für dieses Intervall vollständig `null` bleiben, bevor COP Croesus erneut anspricht.

Die NPC-Interaktion ist daher **kein allgemeiner Schritt nach jedem Kauf**. Öffnet der Server direkt den Durchlauf oder die Croesus-Liste, navigiert COP dort weiter. Nur wenn das Menü vollständig geschlossen bleibt, wird der Croesus-NPC in bis zu sechs Blöcken Entfernung erneut angesprochen.

### Kismet-Neuwürfe

| Einstellung | Standard | Wirkung |
|---|---:|---|
| **Use kismet** | Aus | Verwendet höchstens eine Kismet Feather für die aktuell ausgewählte Truhe, wenn deren Gewinn unter dem Neuwurf-Limit liegt |
| **Reroll threshold** | 500.000 Coins | Unterhalb dieses Werts kann ein Neuwurf ausgelöst werden |

Voraussetzungen für einen Neuwurf:

- `Use kismet` ist aktiv;
- der Gewinn liegt unter `Reroll threshold`;
- im 36-Slot-Hauptinventar liegt eine Kismet Feather;
- die Truhe wurde in diesem Ablauf noch nicht neu gewürfelt;
- es handelt sich **nicht** um eine Always-Buy-Truhe.

Nach einem Neuwurf kauft COP nur, wenn der neue Gewinn `Min profit` erreicht. Andernfalls geht das Modul zurück und setzt den Ablauf mit einer anderen Truhe oder dem nächsten Durchlauf fort. Die eingesetzte Kismet Feather ist dann bereits verbraucht.

## Kombinationen

| Multi-run | Chain | Kismet | Verhalten |
|---|---|---|---|
| Aus | Aus | Aus | Eine geeignete Truhe im geöffneten Durchlauf |
| Aus | An | Aus | Mehrere geeignete Truhen desselben Durchlaufs; zusätzliche Truhen benötigen Keys |
| An | Aus | Aus | Eine geeignete Truhe pro Durchlauf, einschließlich unterstützter Folgeseiten |
| An | An | Aus | Mehrere geeignete Truhen über mehrere Durchläufe und Seiten |
| An | Aus | An | Eine Truhe pro Durchlauf mit optionalem Kismet-Neuwurf |
| An | An | An | Mehrere Truhen und Durchläufe mit optionalen Neuwürfen |

## Befehle

Alle Nutzerbefehle beginnen mit `/cop`.

### Loot-Zusammenfassung

```text
/cop loot [today|week|all|reset]
```

- `today` ist der Standard und umfasst die **rollierenden letzten 24 Stunden**, nicht den aktuellen Kalendertag.
- `week` umfasst die **rollierenden letzten 7 Tage**, nicht die laufende Kalenderwoche.
- `all` wertet die gesamte vorhandene Logdatei aus.
- `reset` leert die Logdatei unmittelbar. Erstelle vorher bei Bedarf eine Sicherung.

Die Ausgabe enthält Anzahl der Truhen und Durchläufe, Kosten, Wert, Gewinn, verbrauchte Kismets, eine Aufteilung nach Truhentyp sowie die wertvollsten Gegenstände. Die Datei liegt unter `config/cop/croesus-loot.jsonl` und verwendet JSON Lines: ein erfolgreicher Kauf pro Zeile. Fehlerhafte Einzelzeilen werden beim Lesen protokolliert und übersprungen.

> [!NOTE]
> `/cop loot all` zeigt Anzeigenamen und Summen, aber keine verlässliche Liste der SkyBlock-IDs. Verwende diesen Befehl daher **nicht** als ID-Quelle für Always Buy oder Worthless.

### Always Buy

```text
/cop alwaysbuy [list|add|remove|clear] [SKYBLOCK_ID]
```

Enthält mindestens eine Truhe einen Eintrag aus dieser Liste, wählt COP unter den passenden Truhen die mit dem höchsten Gewinn. Dabei wird `Min profit` ignoriert. Eine Always-Buy-Truhe wird bewusst direkt gekauft und **niemals mit Kismet neu gewürfelt**, auch wenn `Use kismet` aktiv ist.

```text
/cop alwaysbuy add RECOMBOBULATOR_3000
/cop alwaysbuy list
```

### Worthless

```text
/cop worthless [list|add|remove|clear] [SKYBLOCK_ID]
```

Gelistete Gegenstände werden bei der Gewinnberechnung mit einem Wert von 0 angesetzt. Das beeinflusst Overlay, Auswahl, `Min profit`, `Reroll threshold` und den im Loot-Log gespeicherten berechneten Wert.

```text
/cop worthless add HOT_POTATO_BOOK
/cop worthless add FUMING_POTATO_BOOK
```

Die Befehle wandeln Eingaben in Großbuchstaben um und ersetzen Leerzeichen durch Unterstriche. Aus `recombobulator 3000` wird beispielsweise `RECOMBOBULATOR_3000`.

## SkyBlock-IDs ermitteln

Verwende eine kanonische SkyBlock-ID, nicht nur den sichtbaren Gegenstandsnamen.

1. Nach einem eigenen Kauf steht die ID im Feld `items[].id` der lokalen Datei `config/cop/croesus-loot.jsonl`.
2. Der **Debug dump key** schreibt für Gegenstände im 36-Slot-Hauptinventar `name="…" id="…"` nach `latest.log`.
3. Bekannte Beispiele sind `RECOMBOBULATOR_3000`, `FUMING_POTATO_BOOK`, `KISMET_FEATHER` und `ENCHANTMENT_ULTIMATE_WISE_5`.

Prüfe die ID vor dem Eintragen. Eine falsche ID wird gespeichert, kann aber nie mit einem Truheninhalt übereinstimmen.

## Technischer Ablauf und Synchronisation

Vereinfacht arbeitet der Treiber als Zustandsautomat:

```text
Leerlauf
  → Truhenstufe im Durchlauf anklicken
  → Bestätigungsmenü abwarten und verifizieren
  → optional einmal mit Kismet neu würfeln
  → kaufen oder zurückgehen
  → optional nächste Truhe, nächster Durchlauf oder nächste Seite
```

Wichtige Schutzmaßnahmen:

- **10 Ticks Bestätigungs-Synchronisation:** Jedes neu geöffnete Bestätigungsmenü wartet zunächst 10 Ticks. Erst wenn Slot 31 eine `Cost`-Zeile enthält und die Truhenstufe der zuvor gewählten Stufe entspricht, darf gekauft oder neu gewürfelt werden.
- **15 Ticks Kismet-Synchronisation:** Nach einem Neuwurf wartet COP mindestens 15 Ticks und verlangt veränderte Lore in Slot 31, bevor der neue Gewinn bewertet wird.
- **6 Ticks Croesus-Synchronisation:** Die Multi-Run-Voreinstellung wartet nach geladenen Listen-Slots und beim Erkennen eines vollständig geschlossenen Menüs jeweils `multiRunPacing` Ticks.
- **Seitenlimit:** `Next Page` wird ausschließlich in Slot 53 erkannt; nach fünf Seitenwechseln endet die automatische Seitennavigation.
- **Sicherer Abbruch:** Nicht lesbare oder widersprüchliche Bestätigungsdaten führen zum Abbruch, nicht zu einem blinden Kauf.

## Bekannte Grenzen

- Kismet Feathers werden nur im 36-Slot-Hauptinventar erkannt, nicht in Sacks, Ender Chest oder anderen Speichern.
- Die Seitennavigation hängt vom englischen Namen `Next Page` in Slot 53 ab und ist auf fünf Wechsel pro Zyklus begrenzt.
- Menü- und Lore-Erkennung sind von den aktuellen Hypixel-Titeln und -Texten abhängig.
- Preisberechnungen hängen von erreichbaren und aktuellen Hypixel-/Coflnet-Daten ab.
- Alte Loot-Einträge ohne `runId` gruppieren Durchläufe ersatzweise nach Dungeon-Ebene und Minute; mehrere alte Durchläufe derselben Ebene innerhalb einer Minute können deshalb unterzählt werden.
