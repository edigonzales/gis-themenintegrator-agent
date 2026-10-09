# Lokales ARM64-PostGIS für GRETL und GIS-Integrator

Diese ausdrücklich gewählte Variante baut ein natives Image
`gis-postgis:18-3.6-arm64`. Dockerfile, Hilfsscripts und Overrides liegen hier;
die Compose-Dateien, Init-Scripts und DB-Konfigurationen in `gretljobs` bleiben
die Quellen. Das Image enthält PostgreSQL 18 und PostGIS 3.6 einschließlich
Raster-/Topology-Erweiterungen. PostgreSQL 18 verwendet weiterhin den
Volume-Mount `/var/lib/postgresql`.

## Build und GRETL-Compose

Vom Integrator-Repo aus:

```sh
# Originale GRETL-Dateien plus ARM64-Override, in einem eigenen Compose-Projekt:
./runtime/postgis-arm64/gretl-compose.sh build edit-db
./runtime/postgis-arm64/gretl-compose.sh up -d edit-db pub-db
./runtime/postgis-arm64/gretl-compose.sh ps
```

Beide DB-Services nutzen dasselbe Image. `build edit-db` genügt daher. `up`
verwendet mit `pull_policy: never` ausschließlich das bereits gebaute Image.
Neue Volumes gehören zum Projekt `gretljobs-arm64`. Die Ports stammen weiterhin
aus dem Original-Compose: Edit 54321, Pub 54322. Falls sie belegt sind, den
anderen Stack bewusst stoppen oder eine weitere persönliche Port-Override-Datei
mit `-f` ergänzen; bestehende Datenvolumes nicht entfernen.

Optionale DBs werden nur mit ihren Original- und ARM64-Dateien aufgenommen:

```sh
./runtime/postgis-arm64/gretl-compose.sh --oereb --processing up -d edit-db pub-db oereb_v2-db processing-db
./runtime/postgis-arm64/gretl-compose.sh --oereb --processing ps
./runtime/postgis-arm64/gretl-compose.sh --oereb --processing down
```

Bei **allen** Befehlen die gleichen Optionen wählen. `down` erhält die Volumes;
`down --volumes` löscht sie. Ein anderer GRETL-Checkout lässt sich durch
`--gretl-repo /absoluter/pfad/gretljobs` auswählen. Compose-Optionen wie
`--project-name eigener-arm64-test` können vor dem Unterbefehl angegeben werden.
Der Helfer setzt einen absoluten Build-Kontext und lädt zuerst die Originale,
danach die ARM64-Overrides. Init-Mounts, SQL und DB-Einstellungen werden geerbt.
Processing verwendet in dieser lokalen Variante ebenfalls das Debian-Image;
dies ersetzt das ursprüngliche Alpine-Image ausdrücklich.

Ohne Helfer: `GIS_POSTGIS_BUILD_CONTEXT` auf diesen absoluten Ordner setzen und
`docker compose --project-directory /pfad/gretljobs --project-name gretljobs-arm64
-f /pfad/gretljobs/compose.yaml -f /pfad/compose.arm64.yaml …` verwenden.
Bei Zusatz-DBs jeweils die originale Compose-Datei vor den ARM64-Overrides
ergänzen. Zur Konfigurationsprüfung `config --quiet`, `config --services` oder
`config --images` verwenden; vollständiges `config` kann Zugangsdaten enthalten.

## GIS-Integrator

Der Integrator erzeugt seine eigene Compose-Datei und liest die GRETL-Overrides
nicht. Er nutzt jedoch dasselbe gebaute Image. In `config/local.toml`:

```toml
[images]
# Die übrigen vorhandenen Images hier erhalten.
postgis = "gis-postgis:18-3.6-arm64"

[image_sources]
postgis = "local"
```

Danach:

```sh
./bin/gis-agent init --update-image postgis
./bin/gis-agent doctor
```

Das gezielte Update übernimmt den aktuellen lokalen Tag als feste Image-ID;
andere Pins bleiben erhalten. Normales `init` folgt einem später verschobenen
Tag nicht. Fehlt die festgehaltene lokale ID, ist ein erneuter Build und ein
bewusstes Update nötig. Lokale Images werden auch mit `--update-tools` niemals
gepullt. Unbenannte Image-Quellen gelten weiterhin als `registry`.

`--update-image KEY` und `--update-tools` sind gegenseitig ausgeschlossen.
Nach der Umstellung Codex vollständig beenden und erneut öffnen, damit der MCP
die persönliche Konfiguration neu lädt. Ein neuer Vorgang verwendet ARM64;
bestehende Vorgänge bleiben an ihren bisherigen Stand gebunden. Ihre Container,
Volumen und Testdaten werden nicht migriert oder gelöscht. Ein Image-Wechsel
blockiert weitere Ausführung alter Vorgänge durch die vorhandene Laufzeitprüfung.
Die bisherige DB bleibt direkt erreichbar.

Zum Zurückwählen den bisherigen Image-Verweis einsetzen, `postgis = "registry"`
unter `[image_sources]` setzen und erneut `init --update-image postgis` ausführen.

## Herkunft und Prüfung

Basis-Digest und PostGIS-Pakete sind im Dockerfile festgelegt. Die beiden
unveränderten Scripts stammen aus `postgis/docker-postgis`, Commit
`2bcd236e3af9ec6e668db51eb37162a79f0eaeaa`, Ordner `18-3.6`; ihre MIT-Lizenz
liegt in `THIRD-PARTY-NOTICES`. Das offizielle PostgreSQL-Image konfiguriert die
signierte PGDG-Paketquelle. Die Basis unterstützt ARM64; der Build prüft die
Architektur ausdrücklich. Werden festgelegte Pakete irgendwann aus der Quelle
entfernt, muss der Build bewusst aktualisiert werden.

```sh
docker image inspect --format '{{.Architecture}} {{.Id}}' gis-postgis:18-3.6-arm64
./gradlew test jar spotlessCheck
./gradlew integrationTest
```

Die Integrationstests laufen mit der persönlichen Image-Auswahl in isolierten
Fixtures. Sie verwenden die originalen Schema-/GRETL-Schritte sowie IliValidator;
zusätzliche XML-/XTF-Inhaltsprüfungen werden daraus nicht abgeleitet.
Die bestehenden Healthcheck-Intervalle werden durch diese Option nicht verändert.
