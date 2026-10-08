package ch.so.agi.gis;

import java.util.*;

final class Operations {
  record Operation(String description, Map<String, Object> schema) {}

  static final Map<String, Operation> ALL = new LinkedHashMap<>();

  static {
    add(
        "start",
        "Vorgang mit getrennten Git-Refs und ausgewählten Arbeitsdateien aufnehmen.",
        "workflow",
        "refs:object",
        "schema_jobs:array",
        "gretl_job",
        "tasks:array",
        "parameters:object",
        "credentials_env:object",
        "working_files:object");
    add("context", "Modelle, Legacy-Schema-/Jobdateien und notwendige Angaben lesen.", "run_id!");
    add(
        "configure",
        "Schema-Auswahl, Jobtasks und Parameter eines Vorgangs ausdrücklich ändern.",
        "run_id!",
        "schema_jobs:array",
        "gretl_job",
        "tasks:array",
        "parameters:object",
        "credentials_env:object");
    add("status", "Prüfstände, Freigaben, Artefakte und aktive Ausführungen anzeigen.", "run_id!");
    add(
        "stage_change",
        "Fachliche Datei in einer isolierten Arbeitskopie als Kandidat schreiben.",
        "run_id!",
        "repository!",
        "relative_path!",
        "content",
        "source_path",
        "delete:boolean");
    add(
        "prepare_schema",
        "Neuen Legacy-Schema-Job mit vorhandener Vorlage vorbereiten.",
        "run_id!",
        "topic!",
        "schema_dir!",
        "properties:object!",
        "authorization_permissions!",
        "grants_sql");
    add(
        "prepare_job",
        "Vollständigen GRETL-Job mit SQL und job.properties vorbereiten.",
        "run_id!",
        "name!",
        "files:object!",
        "job_properties:object!");
    add(
        "models_prepare",
        "Lokale Hauptmodelle und Abhängigkeiten kompilieren und einfrieren.",
        "run_id!",
        "models:array",
        "primary_files:object");
    add("interlis_tools", "Werkzeugschemas des echten INTERLIS-MCP lesen.");
    add(
        "model",
        "INTERLIS-Fachwerkzeug aufrufen; Authoring-Ergebnis optional als Kandidat sichern.",
        "run_id!",
        "tool!",
        "arguments:object!",
        "relative_path");
    add(
        "schema_inspect",
        "Lokale Schema-Struktur, Modelle und Rechte vergleichen.",
        "run_id!",
        "schema_key!");
    add(
        "db_query",
        "Read-only-Abfrage der eigenen lokalen edit/pub-Datenbank.",
        "run_id!",
        "database!",
        "sql!",
        "limit:integer");
    add(
        "job_validate",
        "GRETL-Konfiguration und tatsächlichen Taskgraph im gewählten Image prüfen.",
        "run_id!");
    add("task_reference", "Offizielle GRETL-3.x-Taskreferenz mit Quellenstand lesen.", "task!");
    add(
        "find_job_examples",
        "Passende GRETL-Beispiele mit Repo-Commit und Dateihash lesen.",
        "task!",
        "run_id",
        "limit:integer");
    add(
        "attach",
        "Originaldatei unveränderlich zum Vorgang aufnehmen.",
        "run_id!",
        "path!",
        "role!");
    add(
        "plan",
        "Konkrete nächste Etappe für menschliches Review vorbereiten.",
        "run_id!",
        "action!",
        "spec:object");
    add(
        "approve",
        "Tatsächliche menschliche Antwort an den konkreten Plan binden.",
        "run_id!",
        "plan_id!",
        "fingerprint!",
        "human_statement!");
    add(
        "execute",
        "Genau eine freigegebene Etappe ausführen; danach wieder beim Menschen anhalten.",
        "run_id!",
        "plan_id!");
    add(
        "reconcile",
        "Unterbrochene Ausführung anhand eigener Runner und Logs aufklären.",
        "run_id!",
        "runner_stopped:boolean");
  }

  static void add(String name, String description, String... fields) {
    var properties = Json.map();
    var required = new ArrayList<String>();
    for (String field : fields) {
      boolean mandatory = field.endsWith("!");
      field = field.replace("!", "");
      String[] parts = field.split(":");
      String key = parts[0], type = parts.length > 1 ? parts[1] : "string";
      var schema = Json.map("type", type);
      if (type.equals("array")) schema.put("items", Json.map());
      properties.put(key, schema);
      if (mandatory) required.add(key);
    }
    ALL.put(
        name,
        new Operation(
            description,
            Json.map(
                "type",
                "object",
                "properties",
                properties,
                "required",
                required,
                "additionalProperties",
                false)));
  }

  static void validate(String operation, Map<String, Object> args) {
    var op = ALL.get(operation);
    if (op == null)
      throw new Problem("operation_unknown", "Unbekannte Operation.", "operation", operation);
    var properties = Json.obj(op.schema.get("properties"));
    for (String key : args.keySet())
      if (!properties.containsKey(key))
        throw new Problem("invalid_arguments", "Unbekanntes Argument.", "field", key);
    for (String key : Json.strings(op.schema.get("required")))
      if (!args.containsKey(key))
        throw new Problem("missing_argument", "Pflichtangabe fehlt.", "field", key);
    for (var entry : args.entrySet()) {
      String type = Json.required(Json.obj(properties.get(entry.getKey())), "type");
      boolean valid =
          switch (type) {
            case "object" -> entry.getValue() instanceof Map;
            case "array" -> entry.getValue() instanceof List;
            case "boolean" -> entry.getValue() instanceof Boolean;
            case "integer" ->
                entry.getValue() instanceof Integer || entry.getValue() instanceof Long;
            default -> entry.getValue() instanceof String;
          };
      if (!valid)
        throw new Problem("invalid_arguments", "Falscher Argumenttyp.", "field", entry.getKey());
    }
  }
}
