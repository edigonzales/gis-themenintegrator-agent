# Legacy-GIS-Themenintegrator

Java-25-CLI und MCP für getrennte INTERLIS-, Schema- und GRETL-Repos.
Der Agent arbeitet in eigenen lokalen Docker-Datenbanken, bereitet geprüfte
Kandidaten vor und hält nach fachlichen Etappen beim Menschen an.

```sh
./bin/gis-agent init
./bin/gis-agent doctor
```

Init verwendet vorhandene benachbarte Checkouts und klont fehlende Repos,
bereitet festgehaltene Images und Werkzeuge vor, verbindet INTERLIS-MCP und
prüft einen echten synthetischen Schema-/GRETL-Lauf. Ein Host-JDK ist optional:
der Launcher kann den Agenten vollständig über Docker bauen und starten.

- [Anwenderhandbuch](docs/anwenderhandbuch.md): Einrichtung, Tickets, Testdaten,
  neue Jobs, menschliche Etappen, Git und Wiederaufnahme.
- [Entwicklerhandbuch](docs/entwicklerhandbuch.md): Architektur und Prüfungen.
- [Abnahme](docs/abnahme.md): tatsächlich durchgeführte und offene Prüfungen.
- [Gemeinsamer Skill](skills/gis-themenintegrator/SKILL.md): Codex und OpenCode.

GRETL 2.x/3.1/3.2 laufen in getrennten Images. Die originalen Schema-Jobs und
ihre gemeinsamen Gradle-/SQL-Dateien bleiben die Ausführungsgrundlage.
Für Taskentwürfe liest der Agent die [GRETL-Referenz](https://gretl.app/reference.html#tasks)
und passende Beispiele aus dem ausgewählten gretljobs-Stand.

`bin/gis-agent schema OP` zeigt das verbindliche Eingabeschema;
`call OP --args-file DATEI.json` verwendet dieselbe Operation wie MCP.
`serve` startet den stdio-MCP; stdout bleibt dem Protokoll vorbehalten.
