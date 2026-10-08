package ch.so.agi.gis;

import java.nio.file.*;
import java.util.*;

final class Smoke {
  static final String MODEL =
      "INTERLIS 2.3;\nMODEL GisAgent_Smoke (en) AT \"https://example.invalid\" VERSION \"2026-10-08\" =\n TOPIC Data = CLASS Item = code : MANDATORY TEXT*40; END Item; END Data;\nEND GisAgent_Smoke.\n";

  static Map<String, Object> run(Steps w) {
    String id = UUID.randomUUID().toString().replace("-", "");
    var run =
        Json.map(
            "format_version",
            1,
            "id",
            id,
            "test_only",
            true,
            "purpose",
            "init_smoke",
            "input",
            Json.map(
                "schema_jobs",
                List.of("gis_smoke/schema", "gis_smoke/schema_pub"),
                "gretl_job",
                "gis_smoke"),
            "repositories",
            Json.map(),
            "changes",
            Json.map(),
            "artifacts",
            new ArrayList<>(),
            "schemas",
            Json.map(),
            "plans",
            Json.map(),
            "events",
            new ArrayList<>());
    w.store.create(run);
    try {
      Path shared = w.s.repoPath("schema").resolve("shared");
      try (var paths = Files.walk(shared)) {
        for (Path file : paths.filter(Files::isRegularFile).toList()) {
          Path target = w.work(run, "schema").resolve("shared").resolve(shared.relativize(file));
          Files.createDirectories(target.getParent());
          Files.copy(file, target);
        }
      }
      Json.write(w.work(run, "models").resolve("models/DEMO/GisAgent_Smoke.ili"), MODEL);
      for (String db : List.of("edit", "pub")) {
        String suffix = db.equals("edit") ? "" : "_pub";
        String folder = "schema" + suffix;
        Json.write(
            w.work(run, "schema").resolve("topics/gis_smoke/" + folder + "/schema.properties"),
            "schemaVersion=1\ndatabases="
                + db
                + "\nmodels=GisAgent_Smoke\nnameByTopic=true\ncreateMetaInfo=true\ncreateFk=true\ncreateUnique=true\n");
        Json.write(
            w.work(run, "schema").resolve("topics/gis_smoke/" + folder + "/job.properties"),
            "nodeLabel=gretl-3.2\n");
        Json.write(
            w.work(run, "schema").resolve("topics/gis_smoke/privileges" + suffix + "/grants.sql"),
            "GRANT $" + "{dbSchema}_write TO gretl;\n");
      }
      Json.write(
          w.work(run, "gretl").resolve("gis_smoke/build.gradle"),
          "import ch.so.agi.gretl.tasks.*\nimport ch.so.agi.gretl.api.TransferSet\napply plugin:'ch.so.agi.gretl'\ndefaultTasks 'transfer'\ntasks.register('transfer',Db2Db) { sourceDb=[dbUriEdit,dbUserEdit,dbPwdEdit];targetDb=[dbUriPub,dbUserPub,dbPwdPub];transferSets=[new TransferSet('transfer.sql','gis_smoke_pub_v1.data_item',true)] }\n");
      Json.write(
          w.work(run, "gretl").resolve("gis_smoke/transfer.sql"),
          "SELECT acode FROM gis_smoke_v1.data_item");
      Json.write(w.work(run, "gretl").resolve("gis_smoke/job.properties"), "nodeLabel=gretl-3.2\n");
      w.prepareModels(run, Json.map());
      for (var selected : w.schemas(run)) w.executeSchema(run, selected, true);
      Database.dml(
          w.runtime, run, "edit", "INSERT INTO gis_smoke_v1.data_item(acode) VALUES ('smoke')");
      var first = w.runJob(run);
      if (!Boolean.TRUE.equals(first.get("valid")))
        throw new Problem("smoke_failed", "GRETL-Smoke fehlgeschlagen.", "result", first);
      var actual =
          Database.read(
              w.runtime, run, "pub", "SELECT acode AS code FROM gis_smoke_pub_v1.data_item", 10);
      if (!actual.equals(List.of(Json.map("code", "smoke"))))
        throw new Problem("smoke_failed", "Transferinhalt stimmt nicht.", "actual", actual);
      var repeated = w.runJob(run);
      if (!Boolean.TRUE.equals(repeated.get("valid"))
          || !actual.equals(
              Database.read(
                  w.runtime,
                  run,
                  "pub",
                  "SELECT acode AS code FROM gis_smoke_pub_v1.data_item",
                  10))) throw new Problem("smoke_failed", "Wiederholung fehlgeschlagen.");
      var result =
          Json.map(
              "valid",
              true,
              "run_id",
              id,
              "test_only",
              true,
              "schema_import",
              true,
              "transfer",
              true,
              "repeatability",
              true);
      run.put("last_result", result);
      return result;
    } catch (Problem e) {
      run.put("last_result", e.result());
      throw e;
    } catch (Exception e) {
      throw new Problem(
          "smoke_failed",
          "Smoke konnte nicht abgeschlossen werden.",
          "exception",
          e.getClass().getSimpleName());
    } finally {
      w.store.create(run);
      if (Files.exists(w.dir(run).resolve("compose.yaml"))) w.runtime.stop(run, true);
    }
  }
}
