# Legacy-GIS-Themenintegrator

## Einrichtung

Git und Docker mit Compose werden benötigt. Ein Host-JDK 25 ist optional:
der Launcher kann Build und Java-Agent im Docker-Image ausführen.
GRETL verwendet immer die JVM seines Images.

```sh
./bin/gis-agent init
./bin/gis-agent doctor
```

Init verwendet vorhandene benachbarte Checkouts und klont fehlende Repos.
`config/local.toml` enthält Pfade und Images. Eigene Konfiguration:
`bin/gis-agent --config /absoluter/pfad/local.toml init`.
JDK wählen: `JAVA_HOME=/pfad/jdk25 bin/gis-agent init`.
Docker ausdrücklich wählen: `GIS_FORCE_DOCKER=1 bin/gis-agent init`.
Im Docker-Modus zusätzliche Eingaben ausserhalb des Checkout-Elternordners über
`GIS_INPUT_DIRECTORY=/absoluter/ordner` bereitstellen.

Der Bericht liegt in `.gis-agent/init.json`. Init ist wiederholbar.
`--update-tools` aktualisiert bewusst Images, `--update-docs` die Taskreferenz.
`--skip-smoke` meldet die Einrichtung bis zur tatsächlichen Prüfung als unvollständig.
Codex/OpenCode verwenden erzeugte projektlokale Konfigurationen und den gemeinsamen Skill.

## Ticket aufnehmen

```sh
./bin/gis-agent call start --json '{
  "refs": {
    "gretl": "alw_tiergesundheit_eindaemmungsbarrieren_pub",
    "schema": "alw_tiergesundheit_eindaemmungsbarrieren_model",
    "models": "master"
  },
  "schema_jobs": [
    "schema_alw_tiergesundheit_eindaemmungsbarrieren",
    "schema_alw_tiergesundheit_eindaemmungsbarrieren_pub"
  ],
  "gretl_job": "alw_tiergesundheit_eindaemmungsbarrieren_pub"
}'
```

Die Antwort enthält `run_id`. Vorgänge haben eigene persistente Datenbanken,
Arbeitskopien und eingefrorene Modellstände; physische Schemanamen bleiben erhalten.
`context` zeigt die Zuordnung, `plan` die nächste Etappe.

```sh
./bin/gis-agent call plan --json '{"run_id":"VORGANG","action":"schema_create"}'
./bin/gis-agent call approve --json '{
  "run_id":"VORGANG","plan_id":"PLAN","fingerprint":"GEZEIGTER_HASH",
  "human_statement":"Tatsächliche menschliche Antwort"
}'
./bin/gis-agent call execute --json '{"run_id":"VORGANG","plan_id":"PLAN"}'
```

Den Plan vor approve zeigen und die menschliche Antwort abwarten.
Execute führt genau diese Etappe aus. Freigaben sind Workflow-Nachweise;
das Werkzeug ersetzt keine unabhängige Authentisierung des Menschen.

## Testdaten und Jobs

`schema_inspect` zeigt tatsächliche Tabellen, Spalten, Constraints, Modelle und
Rechte. `db_query` verwendet eine Leserrolle und Read-only-Transaktionen.
SQL wird mit `plan(action=data_sql)` und
`spec={"schema_key":"amt_thema/schema:edit","sql":"INSERT INTO ..."}` vorbereitet.
Der Schemaplatzhalter lautet `${dbSchema}`; die Datenänderung läuft transaktional.
XTF zuerst mit `attach` aufnehmen, danach `data_xtf` mit `schema_key`/`artifact_id` planen.

`task_reference` und `find_job_examples` liefern Dokumentation und Beispiele
samt Quellenstand. `job_validate` prüft den realen Taskgraph im ausgewählten Image.
`job_test` erstellt eine weitere isolierte Umgebung mit gleichen Schemanamen.
Sein spec enthält:

```json
{
  "requirements": "Der Code wird vollständig übertragen.",
  "fixtures": [
    {"database":"edit","sql":"INSERT INTO gis_smoke_v1.data_item(acode) VALUES ('test')"}
  ],
  "assertions": [
    {"id":"expected","database":"pub","sql":"(SELECT acode FROM gis_smoke_pub_v1.data_item EXCEPT ALL SELECT 'test') UNION ALL (SELECT 'test' EXCEPT ALL SELECT acode FROM gis_smoke_pub_v1.data_item)"}
  ],
  "repeatable": true,
  "comparison_queries": [
    {"database":"pub","sql":"SELECT acode FROM gis_smoke_pub_v1.data_item"}
  ]
}
```

Fachliche Erwartungen vorab bestätigen. Assertions liefern Verletzungszeilen;
SQL-Fehler sind fehlgeschlagene Prüfungen.
`job_run` verlangt standardmässig einen aktuellen bestandenen Test.
Für ausdrücklich beauftragte Reproduktion `spec={"mode":"diagnosis"}` verwenden.
Prozesserfolg, Datenvalidierung und menschliche Abnahme bleiben getrennt.
Logs und kleine Jobausgaben werden archiviert.

Benötigte Zugangsdaten werden als `credentials_env` zugeordnet, beispielsweise
`{"downloadPassword":"MY_DOWNLOAD_PASSWORD"}`. Der Vorgang speichert nur den
Variablennamen. Im Docker-Modus benannte Variablen ausdrücklich mit
`GIS_FORWARD_ENV="MY_DOWNLOAD_PASSWORD GH_TOKEN"` weitergeben. Werte werden nur
temporär in privaten Laufdateien verwendet und aus Logs redigiert.
Nach einem Werkzeugupdate einen neuen Vorgang mit dem neuen Image-Stand aufnehmen.

## Neue Modelle und Jobs

`interlis_tools` zeigt echte Fachwerkzeugschemas. `model` nutzt High-Level-
Authoring oder Review und sichert mit `relative_path` geprüfte Modellkandidaten.
`prepare_schema` braucht Thema, Schemaordner, properties, bestätigte
authorization_permissions und optional grants_sql. Defaults stammen aus xy_template.
`prepare_job` braucht name, vollständige files und job_properties mit bestätigter
authorization.permissions. Neue Jobs verwenden standardmässig GRETL 3.2.
`stage_change` ergänzt SQL und fachliche Hooks.

`configure` ändert ausgewählte Schemas, Tasks und Parameter. Andere Repo-Refs
erhalten einen neuen Vorgang. Neuaufbau/Vorgängerlöschung werden separat geplant.
Vorgänger ist exakt n−1; externe Abhängigkeiten blockieren. DROP und anschliessender
Schemaimport sind keine gemeinsame Transaktion.

## Übernahme und Wiederaufnahme

`changes` mit delivery=local übernimmt nach Commit-/Dateikonfliktprüfung.
Bei anderem Zielcheckout den vorgesehenen Branch bewusst auswählen.
delivery=pr erstellt PRs je Repo. `gh` muss angemeldet und Git-Name/-E-Mail müssen
konfiguriert sein; alternativ im Docker-Modus `[git]` mit name/email setzen.
Der Bootstrap-Bericht nennt Generator, Dateien und Branchparameter.
Registrierung auf Jenkins und PR-Merge erfolgen durch Menschen.

Nach Unterbrechung `status` und Logs lesen. `reconcile` mit runner_stopped=true
stoppt eigene Runner und beendet markierte DB-Sitzungen; kein erneuter Jobstart.
Nach Aufklärung einen neuen Plan beauftragen.
`cleanup` stoppt die Umgebung; remove_data=true entfernt ihre Volumes.
Archivierte Dateien und Protokolle bleiben erhalten.
