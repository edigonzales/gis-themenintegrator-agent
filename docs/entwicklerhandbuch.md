# Entwicklung

Java 25, Gradle und ein JAR. `Main` bietet CLI/stdio-MCP; `Operations` deren
gemeinsame Argumentenschemas. `Workflow` liest Quellen und vorbereitet Kandidaten,
`Steps` plant und führt freigegebene Etappen aus. `Store` verwendet Dateisperren
und atomare Zustandsdateien.

`Setup` richtet Checkouts, Images, Wissenscache und Harness ein. `DockerRuntime`
erzeugt pro Vorgang Compose-Dienste, prüft Eigentümerlabels und verwendet je GRETL-
Profil einen eigenen Runner/Gradle-Cache. `Legacy` liest ursprüngliche Properties.
`Models` kompiliert und archiviert transitive Abhängigkeiten. Ein zusätzlicher
Gradle-Init-Adapter setzt lokale Verbindungen und eingefrorene Modellverzeichnisse.
`Interlis` verwendet Docker-stdio oder externes Streamable HTTP.

Vorgänge liegen unter `.gis-agent/runs/<id>`. Lokale Zugangsdaten sind generiert,
in privaten ignorierten Dateien gespeichert und aus Prozessausgaben redigiert.
Inspection erfasst Struktur, Modelle, Views, Rechte und Rollen. MATCHING
bezeichnet Übereinstimmung mit einem erfolgreichen aufgezeichneten Lauf.
Eine zurückgerollte DROP-Probe und ein transaktionaler Event-Trigger blockieren
Kaskaden ausserhalb des konkreten Schemas.

GRETL/SQL sind vertrauenswürdiger ausführbarer Code; dies ist keine Sandbox.
Technischer Erfolg, bestätigte Test-Erwartungen und menschliche Abnahme sind
verschiedene Nachweise. Automatische Freigaben nur in markierten synthetischen Fixtures.

```sh
JAVA_HOME=/pfad/jdk25 ./gradlew test jar spotlessCheck
./bin/gis-agent init
JAVA_HOME=/pfad/jdk25 ./gradlew integrationTest
```

Tests erstellen eigene Datenbanken und entfernen nur deren Volumes.
Der Init-Smoke führt das originale Legacy-Schemaprojekt und einen echten Db2Db-Job aus.
Herkunft wiederverwendeter Quellen steht in `runtime/THIRD-PARTY-NOTICES`
und `src/test/resources/legacy/PROVENANCE.md`.
