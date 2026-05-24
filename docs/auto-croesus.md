# Auto Croesus

Auto-claim driver für den Croesus-NPC im Dungeon Hub. Highlightet
unclaimed Runs, zeigt pro Chest Cost/Value/Profit, und kann auf Tastendruck
komplette Multi-Run-Zyklen abklicken — inklusive NPC-Re-Interact, Kismet
Rerolls und Loot-Log.

## Quickstart

1. Stell dich neben den Croesus-NPC im Dungeon Hub.
2. Module aktivieren: `/cop` → **Dungeons** → **Auto Croesus**.
3. **Auto claim (master)** anschalten — das ist der Killswitch, ohne ihn
   ist die Claim-Taste inert.
4. Tastenbelegung setzen: **Claim best chest** auf z.B. `R`.
5. **Multi-run claim** anschalten.
6. Croesus öffnen → Claim-Taste drücken → zurücklehnen.

## Settings — was macht was

### Visuals (jederzeit aus/an, beeinflussen das Auto-Claim nicht)

| Setting | Standard | Effekt |
|---|---|---|
| **Unclaimed highlight** | grün | Farbe um Runs mit noch nicht geclaimten Chests im Croesus-Menü. |
| **Border width** | 2 px | Dicke der Highlight-Border. |
| **Show profit overlay** | an | Per-Chest Overlay (Cost / Value / Profit) in der Run-Sub-Screen. |
| **Highlight best chest** | an | Goldene Border + ★ vor dem profitabelsten Chest. |
| **Best-chest highlight** | gold | Farbe der Best-Chest-Border. |
| **Refresh rate** | 5 t | Wie oft (in Ticks) das Run-GUI re-parsed wird. |
| **Debug dump key** | none | In jedem Chest-GUI: dumpt Titel + alle Slots + Inventory in `latest.log`. Diagnose-Tool. |

### Auto-Claim Core

| Setting | Standard | Effekt |
|---|---|---|
| **Auto claim (master)** | **aus** | Killswitch. Ohne den ist die Claim-Taste inert. |
| **Claim best chest** | none | Hauptkeybind. In Run-Sub-Screen → 1-Chest-Claim. In Croesus-Liste (+ Multi-Run on) → Full-Cycle. |
| **Min profit** | 50.000 | Coins. Chests unter dem Wert werden ohne Kismet skipped. |
| **Claim timeout** | 60 t | Abort, wenn Buy-Confirm-GUI nicht in N Ticks aufgeht. |

### Chain (mehrere Chests pro Run)

| Setting | Standard | Effekt |
|---|---|---|
| **Chain claim (this run)** | aus | Nach jedem Buy automatisch zurück zur Run-Sub-Screen, nächsten besten Chest claimen, weiter — **braucht Dungeon Chest Keys** in der Inv. |

### Multi-Run (alle Runs auf einer Croesus-Seite)

| Setting | Standard | Effekt |
|---|---|---|
| **Multi-run claim** | aus | Vom Croesus-Listing aus: Run öffnen → besten Chest claimen → zurück zur Liste → nächster Run → wiederholen. Nutzt **NPC-Re-Interact** nach jedem Buy (Menü schließt sich Server-seitig komplett). |
| **Multi-run pacing** | 6 t | Padding zwischen Server-Aktionen: post-Buy → NPC-Reopen, und Menü-Open → Klick. Runter (3-4) auf schneller Connection, hoch (10-15) wenn `expected run sub-screen, got Croesus` Fehler auftauchen. |

### Kismet Rerolls

| Setting | Standard | Effekt |
|---|---|---|
| **Use kismet** | aus | Wenn ein Chest unter `Reroll threshold` liegt und du eine Kismet Feather in der Inv hast, wird einmal rerollt. Danach: Buy wenn Profit ≥ `Min profit`, sonst Skip. |
| **Reroll threshold** | 500.000 | Coins. Sollte über `Min profit` liegen, sonst feuert der Reroll nie. |

## Modus-Kombinationen — was tut welche Combo?

| Multi-run | Chain | Use kismet | Verhalten |
|---|---|---|---|
| off | off | off | **Phase 3a:** 1 Chest, 1 Run. Keybind in Run-Screen drücken. |
| off | on | off | **Phase 3b:** alle Chests in EINEM Run, solange Profit > Min und Keys da sind. |
| on | off | off | **Phase 3c:** 1 Chest pro Run, durch alle unclaimed Runs auf Seite 1. |
| on | on | off | 3b+3c kombiniert — voll auto MIT Keys. |
| on | off | on | 3c + Kismet-Upgrade auf jedem marginalen Chest. **Setup für Keyless Profit-Maxing.** |
| on | on | on | Komplettes Setup für Key-User mit Kismets. |

## Commands

Alle laufen über `/cop`.

### `/cop loot [today|week|all|reset]`

Summary über das Loot-Log. Default `today`.

```
/cop loot
  → Auto Croesus loot (today • 10 chests across 10 runs)
    Spent: 8.50M  Earned: 15.68M  Profit: +7.18M  Kismets: 0
    By tier:
      Bedrock x1  profit +5.18M
      ...
    Top items:
      Recombobulator 3000  10.61M
      ...

/cop loot reset
  → leert das Log
```

Die Log-Datei liegt unter `config/cop/croesus-loot.jsonl` —
append-only JSON Lines, ein Chest pro Zeile. Backup-bar.

### `/cop alwaysbuy [list|add|remove|clear] [SKYBLOCK_ID]`

Items die du **immer** willst, egal ob der Chest profitabel ist.

```
/cop alwaysbuy add RECOMBOBULATOR_3000
/cop alwaysbuy add HYPERION
/cop alwaysbuy list
  → Croesus alwaysbuy list (2):
      RECOMBOBULATOR_3000
      HYPERION
```

Wenn ein Chest mindestens ein Listen-Item enthält:
- wird er den anderen Chests vorgezogen,
- wird **Min profit ignoriert** (Chest wird auch bei -1M Profit geclaimt).
- Kismet-Logik gilt weiter, falls aktiviert.

Im Chat sichtbar als oranges `★ AutoCroesus: claiming … (always-buy item present)`.

### `/cop worthless [list|add|remove|clear] [SKYBLOCK_ID]`

Items die du nicht verkaufen wirst — werden in der Profit-Rechnung
als 0 gewertet. Praktisch für Hot Potato Books, Fuming etc. wenn
du schon overcapped bist.

```
/cop worthless add HOT_POTATO_BOOK
/cop worthless add FUMING_POTATO_BOOK
```

Wirkt sich auf **Overlay**, **Min profit**, **Reroll threshold** und
**Loot-Log** aus — überall wo Profit berechnet wird.

## IDs herausfinden

Die Lists wollen Skyblock-IDs (Uppercase, mit Unterstrichen). Drei Wege:

1. **Loot-Log Top Items** — `/cop loot all` zeigt Top-Items mit ihrem ID-relevanten Namen.
2. **Debug Dump** — im Croesus-Menü Debug-Taste drücken, im `latest.log` siehst du `name="..." id="..."` für jeden Slot.
3. **Bekannte IDs** — z.B. `HYPERION`, `WITHER_BLADE`, `RECOMBOBULATOR_3000`, `FUMING_POTATO_BOOK`, `KISMET_FEATHER`, `ENCHANTMENT_ULTIMATE_WISE_5`.

Die Commands uppercasen + ersetzen Leerzeichen mit `_` automatisch — du
kannst `add recombobulator 3000` schreiben, gespeichert wird `RECOMBOBULATOR_3000`.

## Wie's intern funktioniert (Quick Reference)

State-Machine in `AutoCroesus.kt`:

```
IDLE
 → tryStartClaim (Keybind in Run-Screen)
 → AWAIT_CONFIRM (Tier geklickt, warte auf Buy-Confirm)
 → [decideBuyOrReroll]
     ├─ Kismet path → AWAIT_REROLL_RESULT → re-decide
     ├─ Buy → AWAIT_AFTER_BUY (multi-run) / AWAIT_NEXT_PARSE (chain) / IDLE
     └─ Skip → click Go Back → AWAIT_NEXT_PARSE
 → AWAIT_AFTER_BUY → mc.screen=null detector → tryReopenCroesus (entity click)
 → AWAIT_CROESUS_LIST → poll for slot 4 + slot 49 populated + 10t sync delay
 → click next unclaimed → AWAIT_RUN_SCREEN
 → ...
```

Wichtige Timing-Hacks:
- **`multiRunPacing` (default 6 t)** auf Croesus-Liste — Hypixel rejected sonst Klicks weil `lastStateId` noch nicht synct ist. Gleicher Wert auch als "kein Screen für N Ticks = Menü zu" Detector im post-Buy-Pfad.
- **15 t Sync-Delay** nach Reroll-Klick — sonst parsen wir die alten Slot-31 Lore-Daten.
- **10 t Sync-Delay** auf buy-confirm in der Kismet-Path — wartet auf Slot-31 Lore vor parse.

Loot-Persistence: `CroesusLootLog.append()` schreibt eine Zeile pro Chest
als JSON in `config/cop/croesus-loot.jsonl`. Reads sind line-by-line,
malformed Lines werden geloggt und übersprungen.

Lists: `CroesusLists.alwaysBuy` / `.worthless` sind `MutableList<String>`,
persistiert via `configList()` in `config/cop/croesus-{alwaysbuy,worthless}.json`.

## Bekannte Limits

- **Page 1 only** — Multi-Run scannt nur die aktuell sichtbare Croesus-Seite. Wenn du >50 Runs hast, muss Page 2 manuell aufgemacht werden. (Phase 3d, kein Termin.)
- **Kein Sack-Scan** — Kismet-Detection läuft nur über die 36 Hauptinventar-Slots, nicht über Sack of Sacks oder Ender Chest.
- **Run-Count vor Stand mai 2026** — Loot-Entries vor dem `runId`-Field nutzen einen `floor@minute`-Bucket-Fallback und können bei Multi-Run-Cycles untercounten. Neue Entries sind genau.
- **Kein "already bought" Check** für Chests die in einer früheren Session schon gerollt wurden — der Reroll-Click ginge raus, würde aber serverseitig still failen.
