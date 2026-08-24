# Zu COP beitragen

Danke, dass du COP verbessern möchtest. Der aktive Entwicklungszweig **`mc26`** zielt auf Minecraft **26.1.2**, Fabric und Java 25. Änderungen für ältere 1.21.x-Versionen gehören nicht in diesen Branch.

## Vor dem Start

- Suche zuerst in den [offenen Issues](https://github.com/elv1n200/COP/issues).
- Für neue Module oder größere Verhaltensänderungen: eröffne vor der Implementierung einen [Feature Request](https://github.com/elv1n200/COP/issues/new?template=feature_request.yml). So werden Zweck, Serverrisiko und UX geklärt, bevor viel Code entsteht.
- Sicherheitsprobleme gehören nicht in ein öffentliches Issue. Nutze [SECURITY.md](SECURITY.md).
- Ports oder übernommene Logik müssen mit Ursprung und Lizenz in [CREDITS.md](CREDITS.md) beziehungsweise [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) nachvollziehbar sein.

## Entwicklungsumgebung

Benötigt werden Git und ein **JDK 25**. Klone das Repository und wechsle auf den Zielbranch:

```bash
git clone https://github.com/elv1n200/COP.git
cd COP
git switch mc26
./gradlew :26.1.2:test
```

Unter Windows wird entsprechend `gradlew.bat` verwendet. Wenn Gradle eine falsche Java-Version meldet, setze `JAVA_HOME` für die aktuelle Shell auf dein JDK 25 und starte den Gradle-Daemon neu.

Die Verzeichnisse `versions/`, `build/` und `dist/` werden generiert. Ändere dort keine Quelldateien; gemeinsame Quellen liegen unter `src/` und versionsabhängige Anpassungen laufen über Stonecutter.

## Änderungen entwickeln

1. Halte einen Commit auf einen nachvollziehbaren Zweck begrenzt.
2. Bewahre bestehende Konfigurationen und Defaults, sofern eine Migration nicht ausdrücklich Teil der Änderung ist.
3. Blockiere den Minecraft-Thread nicht mit Netzwerk- oder Dateiarbeit.
4. Behandle Daten aus Chat, Paketen, externen APIs und Konfigurationsdateien als fehlerhaft oder unvollständig.
5. Ergänze für pure Logik oder einen behobenen Edge Case einen fokussierten Test.
6. Behaupte in Dokumentation und PR nur das, was du tatsächlich getestet hast.

## Prüfen

Mindestens:

```bash
./gradlew :26.1.2:test
./gradlew :26.1.2:build
```

Bei Änderungen an Mixins, Rendering, Eingaben, Paketen, Chat oder SkyBlock-Erkennung ist zusätzlich ein echter Client-Test nötig. Nutze die [Client-Test-Checkliste](docs/client-test-checklist.md) und notiere exakt, welche Abschnitte du ausgeführt hast.

Ein Gradle-Erfolg belegt keine Kompatibilität mit aktuellen Hypixel-Texten oder GUIs. Wenn du keinen Live-Test durchführen konntest, schreibe das offen in den PR.

## Pull Request

Ein guter PR enthält:

- Problem und Lösung in wenigen Sätzen;
- verlinktes Issue, falls vorhanden;
- tatsächliche Testumgebung und ausgeführte Gradle-Tasks;
- manuell getestete In-Game-Szenarien;
- bekannte Risiken, ungetestete Pfade und bewusst nicht gelöste Punkte;
- bei sichtbaren UI-Änderungen ein fokussiertes Bild ohne private Chat- oder Accountdaten.

Mit einem Beitrag bestätigst du, dass du den Code unter der bestehenden [GPL-3.0-Lizenz](LICENSE) veröffentlichen darfst.
