# Abnahme, 8. Oktober 2026

## Tatsächlich ausgeführt

- 13 JUnit-Tests: getrennte Branches, unveränderte Quellen, Freigaben und deren
  Entwertung, lokale Konflikte, Schemaoptionen, eingefrorene Modelle,
  Änderungen an Abhängigkeiten, fehlende Checkouts und wiederaufnehmbare Einrichtung.
- Zehn Integrationsszenarien mit echten Werkzeugen: originale Schema-Jobs und
  GRETL 2.2.617/3.1.770/3.2.861, SQL-Fixtures, unabhängige Assertions, Wiederholung,
  Neuaufbau, XTF-Import, CSV/HTTP, themenspezifischer Gradle-Override, Read-only-
  Rechte, Transaktionsrollback und geschütztes DROP.
- INTERLIS-Modell mit dem tatsächlichen MCP erzeugt, technisch geprüft,
  ausschliesslich als Kandidat gespeichert und mit ili2c erneut kompiliert.
- CLI-/MCP-Parität, zwölf parallele MCP-Antworten und EOF-Prozessende geprüft.
- Timeout-Probe: eigener GRETL-Runner gestoppt und markierte PostgreSQL-Sitzung
  beendet; kein automatischer Wiederholungsversuch.
- Vollständiger Init-Smoke: originale shared-Schema-Logik, Edit/Pub, Daten,
  echter Db2Db-Transfer und Wiederholung.
- Vollständiger Docker-Einstieg mit ausdrücklich deaktiviertem Host-Java:
  JAR-Aufbau, Init, MCP-Verbindung und derselbe reale Schema-/Transfer-Smoke
  aus dem Agentcontainer. Ergebnis `ready=true`.
- Anschliessend sauberer Container-Build nach `clean`: Java-Kompilierung,
  alle 13 Unit-Tests, ausführbares JAR und `spotlessCheck` erfolgreich.
- Skill-Frontmatter, Shell-Syntax und Java-Formatierung geprüft.

Der Gesamtlauf bestand zunächst neun der zehn Integrationsszenarien. Die
Timeout-Prüfung deckte auf, dass gestoppte Container bei der Statusabfrage
ausgeblendet wurden. Nach der Korrektur bestand die gezielte Wiederholung.
Nachweise liegen lokal unter `.gis-agent/acceptance/final-suite`;
Vorgangslogs bleiben in den jeweiligen Abnahmeverzeichnissen erhalten.
Der Docker-Init-Bericht liegt unter `.gis-agent/acceptance/docker-init.json`.

## Eindämmungsbarrieren-Ticket

Der Fall wurde mit synthetischen Daten und den tatsächlichen Branchständen
reproduziert: GRETL `d9741f89f006d4d36c7a3762ec1217ea201d42f6`,
Schema-Jobs `cd01d7c8ee2dc9b7628ac0c7abcdca3334c4b313` und Modellrepo
`d880cf9cba943cf2055806f5a8b3264db6808a7c`.

Die leere Pflichtstruktur ist ein Datensatz in der Tabelle `modinfo`. Die
Referenz zum Hauptobjekt heisst hier `tiergsndht_mmngsbrrren_modinfo`.
Fehlt dieser Datensatz, melden Export/Validierung den im Ticket beschriebenen
Kardinalitätsfehler. Ein gültiges XTF mit der leeren Struktur lässt sich importieren.

Der ausgewählte GRETL-Branch überträgt nur die Haupttabelle. Er kann technisch
erfolgreich sein und trotzdem den Struktureintrag in der Pub-DB auslassen.
Der `main`-Stand enthält zusätzlich eine überholte SELECT-Spalte `modinfo`;
dies ist vom getesteten Ticketbranch zu unterscheiden.

Ein Kandidat in der isolierten Arbeitskopie exportiert und validiert XTF,
entfernt die bisherigen Pub-Objekte und importiert das XTF mit seinen Beziehungen.
Dieser Transfer wurde wiederholt; der Struktureintrag verweist jeweils auf das
richtige Pub-Hauptobjekt. Modell und Pflichtigkeit bleiben erhalten.
Löschen und Import sind separate GRETL-Tasks; ein späterer Importfehler kann
bereits veränderte Pub-Daten hinterlassen.

Die Originalcheckouts wurden fachlich nicht verändert. Die zuvor vorhandene
lokale Änderung im Modellrepo blieb erhalten. Der getestete Kandidat ist ein
Abnahmenachweis und wurde nicht automatisch ins Quellrepo übernommen.

## Simulation und offene menschliche Abnahme

Der PR-Ablauf wurde mit tatsächlichem lokalem Git und simuliertem `gh` geprüft:
vollständiger Diff, Bindung an Git-Tree/Zielcommit und Wiederaufnahme ohne doppelten
PR. Es wurde kein echter GitHub-PR erstellt.

Automatische Freigaben wurden ausschliesslich in ausdrücklich markierten,
isolierten Software-Testfixtures eingesetzt. Fachliche Freigaben für neue Themen,
freie LLM-Entwürfe und echte Repository-Übernahmen bleiben beim Menschen.
Codex-/OpenCode-Konfigurationen wurden erzeugt; ein freier interaktiver LLM-Lauf
ist kein Bestandteil dieser technischen Abnahme.

V1 liefert Jenkins-Dateien und einen Bootstrap-Plan. Remote-Jenkins-Registrierung
und Ausführung wurden gemäss dem gewählten lokalen Umfang nicht durchgeführt.
