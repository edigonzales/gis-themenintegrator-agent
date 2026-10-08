package ch.so.agi.gis;

import java.nio.file.*;
import java.util.*;

final class Fixture {
  final Path root;
  final Settings s;
  final Processes p = new Processes();

  Fixture(Path root) throws Exception {
    this.root = root;
    Files.createDirectories(root.resolve("config"));
    Path resource = Path.of("src/test/resources/legacy/shared").toAbsolutePath();
    for (String repo : List.of("gretl", "schema", "models"))
      Files.createDirectories(root.resolve("repos").resolve(repo));
    try (var paths = Files.walk(resource)) {
      for (Path file : paths.filter(Files::isRegularFile).toList()) {
        Path target = root.resolve("repos/schema/shared").resolve(resource.relativize(file));
        Files.createDirectories(target.getParent());
        Files.copy(file, target);
      }
    }
    Json.write(root.resolve("repos/models/models/DEMO/GisAgent_Smoke.ili"), Smoke.MODEL);
    for (String db : List.of("edit", "pub")) {
      String suffix = db.equals("edit") ? "" : "_pub";
      Json.write(
          root.resolve("repos/schema/topics/gis_smoke/schema" + suffix + "/schema.properties"),
          "schemaVersion=1\ndatabases="
              + db
              + "\nmodels=GisAgent_Smoke\nnameByTopic=true\ncreateMetaInfo=true\ncreateFk=true\ncreateUnique=true\n");
      Json.write(
          root.resolve("repos/schema/topics/gis_smoke/schema" + suffix + "/job.properties"),
          "nodeLabel=gretl-3.2\n");
      Json.write(
          root.resolve("repos/schema/topics/gis_smoke/privileges" + suffix + "/grants.sql"),
          "GRANT $" + "{dbSchema}_write TO gretl;\n");
    }
    Json.write(
        root.resolve("repos/schema/topics/xy_template/schema/schema.properties"),
        "schemaVersion=1\nmodels=Modelname\ndatabases=edit\ndefaultSrsCode=2056\ncreateGeomIdx=true\ncreateFk=true\ncreateFkIdx=true\ncreateUnique=true\ncreateMetaInfo=true\nnameByTopic=true\n");
    Json.write(
        root.resolve("repos/gretl/gis_smoke/build.gradle"),
        "import ch.so.agi.gretl.tasks.*\nimport ch.so.agi.gretl.api.TransferSet\napply plugin:'ch.so.agi.gretl'\ndefaultTasks 'transfer'\ntasks.register('transfer',Db2Db) { sourceDb=[dbUriEdit,dbUserEdit,dbPwdEdit];targetDb=[dbUriPub,dbUserPub,dbPwdPub];transferSets=[new TransferSet('transfer.sql','gis_smoke_pub_v1.data_item',true)] }\n");
    Json.write(
        root.resolve("repos/gretl/gis_smoke/transfer.sql"),
        "SELECT acode FROM gis_smoke_v1.data_item");
    Json.write(root.resolve("repos/gretl/gis_smoke/job.properties"), "nodeLabel=gretl-3.2\n");
    var toml =
        new StringBuilder(
            "root=\"..\"\nstate_dir=\".gis-agent\"\ntimeout_seconds=60\ndefault_runtime=\"2.x\"\nmodel_repositories=[]\n");
    for (String name : List.of("gretl", "schema", "models")) {
      Path repo = root.resolve("repos").resolve(name);
      p.checked(List.of("git", "init", "-b", name.equals("models") ? "master" : "main"), repo, 30);
      p.checked(List.of("git", "config", "user.name", "Fixture"), repo, 30);
      p.checked(List.of("git", "config", "user.email", "fixture@example.invalid"), repo, 30);
      p.checked(List.of("git", "add", "."), repo, 30);
      p.checked(List.of("git", "commit", "-m", "Synthetic fixture"), repo, 30);
      p.checked(List.of("git", "remote", "add", "origin", repo.toString()), repo, 30);
      toml.append("[repositories.")
          .append(name)
          .append("]\npath=")
          .append(Json.text(repo.toString()))
          .append("\nurl=")
          .append(Json.text(repo.toString()))
          .append("\nref=")
          .append(Json.text(name.equals("models") ? "master" : "main"))
          .append("\n");
    }
    var production = Settings.load(null);
    toml.append("[images]\n");
    production
        .images()
        .forEach(
            (key, value) ->
                toml.append(Json.text(key)).append("=").append(Json.text(value)).append("\n"));
    toml.append("[interlis]\ntransport=\"stdio\"\n");
    Path config = root.resolve("config/local.toml");
    Json.write(config, toml.toString());
    s = Settings.load(config.toString());
    if (Files.isRegularFile(production.state.resolve("tools/images.json"))) {
      Json.atomic(
          s.state.resolve("tools/images.json"),
          Json.read(production.state.resolve("tools/images.json")));
    } else {
      var images = Json.map();
      s.images()
          .forEach(
              (key, value) ->
                  images.put(
                      key,
                      Json.map("reference", value, "id", "test-only", "architecture", "amd64")));
      Json.atomic(s.state.resolve("tools/images.json"), Json.map("images", images));
    }
  }

  Map<String, Object> start(Steps workflow, boolean jobs) {
    var args = Json.map("workflow", "isolated_test_fixture");
    if (jobs) {
      args.put("schema_jobs", List.of("schema_gis_smoke", "schema_gis_smoke_pub"));
      args.put("gretl_job", "gis_smoke");
    }
    String id = Json.required(workflow.call("start", args), "run_id");
    workflow.store.with(
        id,
        run -> {
          run.put("test_only", true);
          return null;
        });
    return workflow.store.with(id, LinkedHashMap::new);
  }

  Map<String, Object> approveExecute(
      Steps workflow, Map<String, Object> run, String action, Map<String, Object> spec) {
    String id = Json.required(run, "id");
    Map<String, Object> plan;
    try {
      plan =
          Json.obj(
              workflow
                  .call("plan", Json.map("run_id", id, "action", action, "spec", spec))
                  .get("plan"));
    } catch (Problem error) {
      throw new AssertionError(Json.pretty(error.result()), error);
    }
    workflow.call(
        "approve",
        Json.map(
            "run_id",
            id,
            "plan_id",
            plan.get("id"),
            "fingerprint",
            plan.get("fingerprint"),
            "human_statement",
            "AUTOMATED SYNTHETIC FIXTURE ONLY"));
    return workflow.call("execute", Json.map("run_id", id, "plan_id", plan.get("id")));
  }
}
