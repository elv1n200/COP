# Statische Prüfung der Referenz-Snapshots

[← Dokumentationsübersicht](README.md)

Stand: 24. August 2026. Diese Notiz dokumentiert ausschließlich die lokal
vorliegende Quellcodeprüfung. Die fremden Projekte wurden weder gebaut noch
ausgeführt; enthaltene Wrapper, Skripte, JARs und Netzwerkfunktionen wurden
nicht gestartet.

## Geprüfte Archive

| Snapshot | SHA-256 | Verwendung in COP |
|---|---|---|
| `quoi-26.1.x.zip` | `D5A40B884FCC761490FCE86A79FF94F9B5D523BC26138F6191FEB62FCACF4FC2` | Architektur- und Featurevergleich |
| `NoammAddons-26.1.2.zip` | `B4551E1440A923E6BC2ED81C952A52182F65CED99004B94B83F23FDFE7DA9B32` | Dungeon-Map-, HUD-, Item- und Terminalvergleich |

Beide ZIPs wurden vor dem Lesen auf Pfadtraversal, doppelte Zielpfade,
Symlinks und verschachtelte Archive geprüft. Dabei wurden keine entsprechenden
Einträge gefunden.

## Sicherheitsbeobachtungen

Im lesbaren Source beider Snapshots wurde kein klassischer Token-, Browser-,
Wallet- oder Session-Datei-Stealer, keine Shell-Ausführung und kein dynamischer
Classloader gefunden. Das ist keine Garantie für andere Releases oder
Abhängigkeiten.

Beim Noamm-Snapshot wurden trotzdem Komponenten gefunden, die COP bewusst
nicht übernimmt:

- ein Downloader, der bestehende Daten vor der Aktualisierung löscht, ein
  unsigniertes ZIP ohne Größen-/Hashprüfung lädt und Eintragspfade nicht sicher
  auf das Zielverzeichnis begrenzt;
- eine automatisch aufgebaute WebSocket-Verbindung, die Dungeon-/Team-/Raum-
  und Türzustände an einen Noamm-Dienst übermittelt;
- Remote-Feature-, Cosmetics-, Storage-, Hub-Map- und Update-Pfade sowie eine
  irreführend benannte Remote-Bild-Scherzfunktion.

Beim Quoi-Snapshot wurden unter anderem unlimitierte externe Preis-/Bildabrufe,
ein globaler nicht lebenszyklusgebundener Coroutine-Scope und eine unbegrenzte
Chat-History als Robustheitsrisiken bewertet. Auch diese Implementierungen
wurden nicht übernommen.

## Übernahmeprinzip

COP verwendet nur eigenständig geschriebene, lokale Implementierungen der
geeigneten Ideen: Dungeon-Map-Snapshots, Room Alerts, Statusfilter für
Terminal-Waypoints, Item Protection, Score-/Blessing-/Warp-HUDs,
Chat-Waypoints, Commission Display und Mining Ability Alert. Keine fremden
Service-Endpunkte, Downloader, WebSockets, Archive oder neuen Assets werden
gebündelt.

Quoi `1.1.1+26.1` ist als GPL-3.0-only gekennzeichnet und nennt frogs sowie
jcnlk als Autoren. Der Root des geprüften Noamm-Snapshots enthält CC0 1.0,
zugleich liegen darin eingebettete Komponenten und Assets mit eigenen oder
unklaren Herkünften. Aus diesem Grund bleiben Herkunft und Konzeptreferenzen in
[CREDITS.md](../CREDITS.md) und
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) erhalten.

Die Lizenzbewertung ist eine technische Projektdokumentation und keine
Rechtsberatung.
