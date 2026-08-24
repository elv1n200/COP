# Sicherheitsrichtlinie

## Unterstützter Stand

| Ziel | Status |
|---|---|
| `mc26` / Minecraft 26.1.2 | Aktive Entwicklung |
| Ältere 1.21.x-Builds | Keine aktive Client-Validierung auf dem mc26-Branch |

Installiere nur JARs aus den [offiziellen GitHub Releases](https://github.com/elv1n200/COP/releases) oder baue den Source selbst. Prüfe, dass der Dateiname ausdrücklich `+mc26.1.2.jar` enthält; eine `-sources.jar` ist nicht spielbar.

## Sicherheitslücke melden

Veröffentliche Exploit-Details, Tokens, private Logs oder einen funktionierenden Angriff **nicht** in einem öffentlichen Issue.

1. Nutze nach Möglichkeit GitHubs [private Vulnerability-Meldung](https://github.com/elv1n200/COP/security/advisories/new).
2. Falls diese Funktion nicht verfügbar ist, kontaktiere den Maintainer zunächst über den im Repository verlinkten [Discord](https://discord.gg/Uc9gVncs6P) und frage nach einem privaten Kanal. Sende dort noch keine Geheimnisse in einen öffentlichen Raum.
3. Nenne COP-/Minecraft-Version, Auswirkung, Reproduktionsschritte und eine sichere Minimaldemonstration.

Eine Empfangs- oder Behebungsfrist wird nicht garantiert. Bitte gib dem Maintainer angemessene Zeit zur Untersuchung, bevor Details veröffentlicht werden.

## Daten und externe Verbindungen

COP benötigt keinen eigenen Account und speichert seine Einstellungen lokal unter `<Minecraft-Profil>/config/cop/`. Einzelne Funktionen können externe Dienste verwenden:

- Preisfunktionen: öffentliche Hypixel- und Coflnet-Endpunkte;
- Auto Updater: GitHub Releases, nur bei aktivem Modul; Auto Download ist standardmäßig deaktiviert;
- Spotify-HUD: lokale Windows Media Session, kein Spotify-Login in COP.

Der Updater akzeptiert nur genau ein zur laufenden Minecraft-Version passendes GitHub-Asset mit gültigem SHA-256-Digest. Trotz dieser Prüfung solltest du vor Updates Backups deiner Instanz behalten.

`/cop diagnostics` erstellt einen Support-Report ohne Spielernamen, Serveradresse, Tokens, einzelne Einstellungswerte oder Dateipfade. Er enthält jedoch eine Liste installierter Mods und aktivierter COP-Module. Lies den Report deshalb vor dem Teilen.

## Spiel- und Accountsicherheit

Automatisierte Eingaben oder Abläufe können gegen Serverregeln verstoßen. Technische Verfügbarkeit ist keine Aussage darüber, dass ein Modul auf einem Server erlaubt oder risikofrei ist. Der Nutzer ist selbst dafür verantwortlich, Regeln zu prüfen, Änderungen kontrolliert zu testen und Backups zu führen.
