# Support

COP `mc26` richtet sich an Minecraft 26.1.2. Für ältere JARs, veränderte Forks oder nicht passende Minecraft-Versionen ist eine zuverlässige Fehleranalyse meist nicht möglich.

## Vor einem Bug Report

1. Prüfe, dass Minecraft **26.1.2**, Java **25** und eine `cop-…+mc26.1.2.jar` verwendet werden.
2. Nutze mindestens den getesteten Stand: Fabric Loader 0.19.2, Fabric API 0.149.0+26.1.2 und Fabric Language Kotlin 1.13.9+kotlin.2.3.10.
3. Entferne eine alte COP-JAR, damit nie zwei COP-Versionen gleichzeitig geladen werden.
4. Reproduziere den Fehler wenn möglich in einer separaten Instanz nur mit COP und seinen Pflichtabhängigkeiten.
5. Prüfe die [bestehenden Issues](https://github.com/elv1n200/COP/issues) und die [Client-Test-Checkliste](docs/client-test-checklist.md).

## Diagnose sammeln

Führe im Client aus:

```text
/cop diagnostics
```

Der Markdown-Report liegt danach in der Zwischenablage. Er enthält Laufzeitversionen, Speicher-/Fensterdaten, Clientstatus, geladene Mods und aktivierte COP-Module. Er enthält absichtlich keinen Spielernamen, keine Serveradresse, keine Tokens, keine einzelnen Einstellungswerte und keine Dateipfade. Lies ihn trotzdem vor dem Posten.

Ergänze außerdem den relevanten Ausschnitt aus `latest.log` oder den vollständigen Crash Report. Beide liegen im jeweiligen Minecraft-Profil, nicht zwingend in der globalen Standardinstallation. Lade keine Microsoft-, Discord-, Hypixel- oder API-Tokens hoch; schwärze private Chatnachrichten und lokale Benutzernamen in Pfaden.

## Konfiguration und Recovery

COP-Konfigurationen, Loot-Log und Spotify-Cache liegen unter `<Minecraft-Profil>/config/cop/`. Der optionale Updater verwaltet heruntergeladene Update-Kandidaten zusätzlich unter `.autoupdates/cop/` neben dem Mod-Ziel. Vor manuellen Änderungen Minecraft vollständig schließen und die betroffenen Ordner kopieren.

Ist eine JSON-Datei unlesbar, verwendet COP Defaults und legt für kleine Dateien eine Sicherung im gleichen Ordner an:

```text
dateiname.json.corrupt-<hash>.bak
```

Pro Datei werden höchstens drei unterschiedliche Recovery-Fassungen behalten; leere oder über 16 MiB große Dateien werden nicht automatisch kopiert. Hänge eine solche Sicherung nur an einen Report an, wenn du ihren Inhalt geprüft und private Daten entfernt hast.

## Einen guten Report schreiben

Ein verwertbarer [Bug Report](https://github.com/elv1n200/COP/issues/new?template=bug_report.yml) enthält:

- den Diagnose-Report;
- erwartetes und tatsächliches Verhalten;
- kurze, nummerierte Reproduktionsschritte;
- betroffene Module und deren relevante Einstellungen, ohne Geheimnisse;
- `latest.log`/Crash Report rund um den Fehler;
- die Information, ob der Fehler mit einer minimalen Mod-Liste bleibt.

Allgemeine Fragen können über den [Discord](https://discord.gg/Uc9gVncs6P) gestellt werden. Sicherheitsprobleme folgen ausschließlich [SECURITY.md](SECURITY.md).
