# GIS-Themenintegrator

Für Legacy-GIS-Fachabläufe `skills/gis-themenintegrator/SKILL.md` und den
`gis_integrator`-MCP verwenden. Codex entdeckt den Skill über `.agents/skills`;
OpenCode liest diese Anleitung.

Implementierung und Tests gehören hierher. Die Legacy-Repos sind fachliche
Quellen; dort nur explizit freigegebene Geschäftskandidaten übernehmen.
Vorhandene Branches und lokale Änderungen erhalten.

Java 25: `./gradlew test jar spotlessCheck`. Echte synthetische Docker-Prüfung:
`./gradlew integrationTest`. Keine Python-Implementierung und kein Host-GRETL.
Originale Schema-Jobs samt shared-Logik aus dem ausgewählten Checkout ausführen.

IliValidator ist die maßgebliche technische XTF-Prüfung. Danach Ergebnis berichten
und Datei anzeigen oder verlinken. Weitere XML-/XTF-Inhaltsprüfungen ausschließlich
auf ausdrücklichen Zusatzauftrag, auch bei Validierungsfehlern. Anschauen erlaubt
Anzeige, Formatierung und Erklärung, keine daraus abgeleiteten Zusatzprüfungen.
Vorhandene Validator-Meldungen auswerten; bereits beauftragte SQL-Prüfungen und
originale GRETL-Validierungsschritte weiterhin ausführen.

Freigaben nur für tatsächliche menschliche Antworten speichern. Automatische
Testfreigaben ausschliesslich in markierten isolierten Fixtures. Abnahmeberichte
unterscheiden tatsächliche, simulierte und offene Prüfungen. Nach unbekanntem
Laufstand aufklären; keine blinden Wiederholungen. Zugangsdaten und Container-
Umgebungsvariablen niemals ausgeben.
