package ch.so.agi.gis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("integration")
class TicketIntegrationTest {
  static final String MODEL = "SO_ALW_Tiergesundheit_Eindaemmungsbarrieren_20261002";

  @Test
  void mandatoryEmptyStructureRoundTripsWithActualTicketBranches() throws Exception {
    var source = Settings.load(null);
    Path evidence = source.state.resolve("acceptance/ticket-" + UUID.randomUUID());
    Files.createDirectories(evidence);
    var values = new LinkedHashMap<>(source.values);
    values.put("state_dir", evidence.toString());
    values.put("timeout_seconds", 180);
    var s = new Settings(source.file, values);
    Json.atomic(
        s.state.resolve("tools/images.json"), Json.read(source.state.resolve("tools/images.json")));
    try (var w = new Steps(s)) {
      String id =
          Json.required(
              w.call(
                  "start",
                  Json.map(
                      "workflow",
                      "isolated_ticket_acceptance",
                      "refs",
                      Json.map(
                          "gretl",
                          "alw_tiergesundheit_eindaemmungsbarrieren_pub",
                          "schema",
                          "alw_tiergesundheit_eindaemmungsbarrieren_model",
                          "models",
                          "master"),
                      "schema_jobs",
                      List.of(
                          "schema_alw_tiergesundheit_eindaemmungsbarrieren",
                          "schema_alw_tiergesundheit_eindaemmungsbarrieren_pub"),
                      "gretl_job",
                      "alw_tiergesundheit_eindaemmungsbarrieren_pub")),
              "run_id");
      w.store.with(
          id,
          run -> {
            run.put("test_only", true);
            return null;
          });
      try {
        valid(execute(w, id, "schema_create", Json.map()));
        Path xtf = evidence.resolve("valid-empty-structure.xtf");
        String item =
            "<"
                + MODEL
                + ".Tiergesundheit.Eindaemmungsbarrieren TID=\"fixture1\"><SurveyDate>2026-10-08</SurveyDate><ResponsibleAuthority>Isolated acceptance fixture</ResponsibleAuthority><Country>CHE</Country><Canton>SO</Canton><Location><COORD><C1>2600000.000</C1><C2>1200000.000</C2></COORD></Location><LocationDescription>Fixture</LocationDescription><ClosureRequiredStatus>Pendent</ClosureRequiredStatus><StructuralClosurePossibleStatus>Pendent</StructuralClosurePossibleStatus><ModInfo><WithOneState_V1.ModInfo/></ModInfo><bfs_Gemeindenummer>2601</bfs_Gemeindenummer></"
                + MODEL
                + ".Tiergesundheit.Eindaemmungsbarrieren>";
        Json.write(
            xtf,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><TRANSFER xmlns=\"http://www.interlis.ch/INTERLIS2.3\"><HEADERSECTION SENDER=\"isolated-acceptance-fixture\" VERSION=\"2.3\"><MODELS><MODEL NAME=\""
                + MODEL
                + "\" VERSION=\"2026-07-16\" URI=\"https://geo.so.ch/models/ALW\"/></MODELS></HEADERSECTION><DATASECTION><"
                + MODEL
                + ".Tiergesundheit BID=\"fixture\">"
                + item
                + "</"
                + MODEL
                + ".Tiergesundheit></DATASECTION></TRANSFER>");
        var attachment =
            w.call(
                "attach",
                Json.map("run_id", id, "path", xtf.toString(), "role", "synthetic_fixture"));
        String key = "alw_tiergesundheit_eindaemmungsbarrieren/schema:edit";
        valid(
            execute(
                w,
                id,
                "data_xtf",
                Json.map("schema_key", key, "artifact_id", attachment.get("id"))));
        String table =
            "alw_tiergesundheit_eindaemmungsbarrieren_v1.tiergesundheit_eindaemmungsbarrieren";
        var actual =
            w.call(
                "db_query",
                Json.map(
                    "run_id",
                    id,
                    "database",
                    "edit",
                    "sql",
                    "SELECT count(*) AS structures FROM alw_tiergesundheit_eindaemmungsbarrieren_v1.modinfo m JOIN "
                        + table
                        + " p ON p.t_id=m.tiergsndht_mmngsbrrren_modinfo"));
        Json.atomic(evidence.resolve("modinfo-database.json"), actual);
        assertEquals(
            1L,
            ((Number) Json.obj(Json.list(actual.get("rows")).getFirst()).get("structures"))
                .longValue());
        valid(
            execute(
                w,
                id,
                "data_sql",
                Json.map(
                    "schema_key",
                    key,
                    "sql",
                    "DELETE FROM alw_tiergesundheit_eindaemmungsbarrieren_v1.modinfo")));
        var negative = execute(w, id, "job_run", Json.map("mode", "diagnosis"));
        assertEquals(false, negative.get("valid"), Json.pretty(negative));
        String log = Json.contents(Path.of(Json.required(negative, "log")));
        assertTrue(log.contains("ModInfo") && log.contains("wrong number"), log);
        valid(
            execute(
                w,
                id,
                "data_sql",
                Json.map(
                    "schema_key",
                    key,
                    "sql",
                    "INSERT INTO alw_tiergesundheit_eindaemmungsbarrieren_v1.modinfo(t_seq,t_lastchange,t_createdate,t_user,tiergsndht_mmngsbrrren_modinfo) SELECT 1,now(),now(),current_user,t_id FROM "
                        + table)));
        var originalTransfer = execute(w, id, "job_run", Json.map("mode", "diagnosis"));
        valid(originalTransfer);
        var originalStructures =
            w.call(
                "db_query",
                Json.map(
                    "run_id",
                    id,
                    "database",
                    "pub",
                    "sql",
                    "SELECT count(*) AS structures FROM alw_tiergesundheit_eindaemmungsbarrieren_pub_v1.modinfo"));
        assertEquals(
            0L,
            ((Number)
                    Json.obj(Json.list(originalStructures.get("rows")).getFirst())
                        .get("structures"))
                .longValue());
        Json.atomic(
            evidence.resolve("original-transfer-missing-structure.json"),
            Json.map("gradle_success", true, "structures", originalStructures));
        // Same model and data; XTF carries the structure relationship instead of
        // pretending it is an inline column or copying source t_id values.
        String jobName = "alw_tiergesundheit_eindaemmungsbarrieren_pub";
        String script =
            "import ch.so.agi.gretl.tasks.*\napply plugin:'ch.so.agi.gretl'\ndefaultTasks 'importPublication'\ndef xtf=file(\"$buildDir/validated.xtf\")\n"
                + "tasks.register('exportXtf',Ili2pgExport) { database=[dbUriEdit,dbUserEdit,dbPwdEdit];models='"
                + MODEL
                + "';dbschema='alw_tiergesundheit_eindaemmungsbarrieren_v1';dataFile=files(xtf);disableValidation=false }\n"
                + "tasks.register('validateXtf',IliValidator) { dependsOn 'exportXtf';dataFiles=files(xtf) }\n"
                + "tasks.register('clearPublication',SqlExecutor) { dependsOn 'validateXtf';database=[dbUriPub,dbUserPub,dbPwdPub];sqlFiles=files('clear-publication.sql') }\n"
                + "tasks.register('importPublication',Ili2pgImport) { dependsOn 'clearPublication';database=[dbUriPub,dbUserPub,dbPwdPub];models='"
                + MODEL
                + "';dbschema='alw_tiergesundheit_eindaemmungsbarrieren_pub_v1';dataFile=files(xtf);disableValidation=false }\n";
        w.call(
            "stage_change",
            Json.map(
                "run_id",
                id,
                "repository",
                "gretl",
                "relative_path",
                jobName + "/build.gradle",
                "content",
                script));
        w.call(
            "stage_change",
            Json.map(
                "run_id",
                id,
                "repository",
                "gretl",
                "relative_path",
                jobName + "/clear-publication.sql",
                "content",
                "DELETE FROM alw_tiergesundheit_eindaemmungsbarrieren_pub_v1.modinfo;\nDELETE FROM alw_tiergesundheit_eindaemmungsbarrieren_pub_v1.tiergesundheit_eindaemmungsbarrieren;\n"));
        valid(execute(w, id, "job_run", Json.map("mode", "diagnosis")));
        valid(execute(w, id, "job_run", Json.map("mode", "diagnosis")));
        var output =
            w.call(
                "db_query",
                Json.map(
                    "run_id",
                    id,
                    "database",
                    "pub",
                    "sql",
                    "SELECT count(*) AS structures FROM alw_tiergesundheit_eindaemmungsbarrieren_pub_v1.modinfo m JOIN alw_tiergesundheit_eindaemmungsbarrieren_pub_v1.tiergesundheit_eindaemmungsbarrieren p ON p.t_id=m.tiergsndht_mmngsbrrren_modinfo"));
        assertEquals(Json.text(actual.get("rows")), Json.text(output.get("rows")));
        Json.atomic(
            evidence.resolve("result.json"),
            Json.map(
                "valid",
                true,
                "run_id",
                id,
                "test_only",
                true,
                "model",
                MODEL,
                "negative_null",
                true,
                "positive_empty_structure",
                true,
                "model_semantics_preserved",
                true));
      } finally {
        w.store.with(
            id,
            run -> {
              if (Files.exists(w.dir(run).resolve("compose.yaml"))) w.runtime.stop(run, true);
              return null;
            });
      }
    }
  }

  static Map<String, Object> execute(Steps w, String id, String action, Map<String, Object> spec) {
    Map<String, Object> plan;
    try {
      plan =
          Json.obj(
              w.call("plan", Json.map("run_id", id, "action", action, "spec", spec)).get("plan"));
    } catch (Problem error) {
      throw new AssertionError(Json.pretty(error.result()), error);
    }
    w.call(
        "approve",
        Json.map(
            "run_id",
            id,
            "plan_id",
            plan.get("id"),
            "fingerprint",
            plan.get("fingerprint"),
            "human_statement",
            "AUTOMATED ISOLATED ACCEPTANCE FIXTURE ONLY; no source repository writeback"));
    return w.call("execute", Json.map("run_id", id, "plan_id", plan.get("id")));
  }

  static void valid(Map<String, Object> result) {
    assertEquals(true, result.get("valid"), Json.pretty(result));
  }
}
