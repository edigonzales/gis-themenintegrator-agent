package ch.so.agi.gis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("integration")
class DockerIntegrationTest {
  @Test
  void timeoutStopsDedicatedRunnerAndTerminatesMarkedDatabaseSession() throws Exception {
    var fixture = new Fixture(root);
    try (var w = new Steps(fixture.s)) {
      var run = fixture.start(w, true);
      String id = Json.required(run, "id");
      try {
        valid(fixture.approveExecute(w, run, "schema_create", Json.map()));
        w.store.with(
            id,
            state -> {
              Path project = w.dir(state).resolve("timeout-probe");
              Json.write(
                  project.resolve("build.gradle"),
                  "import ch.so.agi.gretl.tasks.*\napply plugin:'ch.so.agi.gretl'\ndefaultTasks 'hang'\ntasks.register('hang',SqlExecutor){database=[dbUriEdit,dbUserEdit,dbPwdEdit];sqlFiles=files('hang.sql')}\n");
              Json.write(project.resolve("hang.sql"), "SELECT pg_sleep(30)");
              assertEquals(
                  0,
                  w.runtime
                      .execute(state, "3.2", project, List.of("--dry-run"), "warm-timeout-probe")
                      .exitCode());
              var settings = new LinkedHashMap<>(w.s.values);
              settings.put("timeout_seconds", 2);
              var timed = new DockerRuntime(new Settings(w.s.file, settings), new Processes());
              var failure =
                  assertThrows(
                      Problem.class,
                      () -> timed.execute(state, "3.2", project, List.of(), "timeout-probe"));
              assertEquals("command_timeout", failure.code);
              assertFalse(state.containsKey("active_execution"));
              assertFalse(state.containsKey("recovery"), Json.pretty(state.get("recovery")));
              assertTrue(state.containsKey("last_interruption"));
              String container = w.runtime.container(state, "gretl-3-2");
              assertEquals(
                  "false",
                  w.p.checked(
                      List.of("docker", "inspect", "--format", "{{.State.Running}}", container),
                      w.s.root,
                      30));
              try (var connection = Database.connect(w.runtime, state, "edit", "postgres")) {
                assertTrue(
                    Database.query(
                            connection,
                            "SELECT pid FROM pg_stat_activity WHERE application_name LIKE ?",
                            List.of("gis_" + id.substring(0, 8) + "_%"),
                            100)
                        .isEmpty());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              return null;
            });
      } finally {
        w.store.with(
            id,
            state -> {
              w.runtime.stop(state, true);
              return null;
            });
      }
    }
  }

  Path root;

  @BeforeEach
  void evidenceDirectory(TestInfo info) throws Exception {
    root =
        Path.of(System.getProperty("gis.root"))
            .resolve(".gis-agent/acceptance")
            .resolve(info.getTestMethod().orElseThrow().getName() + "-" + UUID.randomUUID());
    Files.createDirectories(root);
  }

  static void valid(Map<String, Object> result) {
    assertEquals(true, result.get("valid"), Json.pretty(result));
  }

  @Test
  void fullWorkflowFixturesAssertionsRepetitionAndExplicitRecreate() throws Exception {
    var fixture = new Fixture(root);
    try (var w = new Steps(fixture.s)) {
      var run = fixture.start(w, true);
      String id = Json.required(run, "id");
      try {
        valid(fixture.approveExecute(w, run, "schema_create", Json.map()));
        var spec =
            Json.map(
                "requirements",
                "Both fixture codes transfer, with no additional rows.",
                "fixtures",
                List.of(
                    Json.map(
                        "database",
                        "edit",
                        "sql",
                        "INSERT INTO gis_smoke_v1.data_item(acode) VALUES ('a'),('b')")),
                "assertions",
                List.of(
                    Json.map(
                        "id",
                        "exact",
                        "database",
                        "pub",
                        "sql",
                        "(SELECT acode FROM gis_smoke_pub_v1.data_item EXCEPT ALL SELECT unnest(ARRAY['a','b'])) UNION ALL (SELECT unnest(ARRAY['a','b']) EXCEPT ALL SELECT acode FROM gis_smoke_pub_v1.data_item)")),
                "repeatable",
                true,
                "comparison_queries",
                List.of(
                    Json.map(
                        "database", "pub", "sql", "SELECT acode FROM gis_smoke_pub_v1.data_item")));
        valid(fixture.approveExecute(w, run, "job_test", spec));
        valid(
            fixture.approveExecute(
                w,
                run,
                "data_sql",
                Json.map(
                    "schema_key",
                    "gis_smoke/schema:edit",
                    "sql",
                    "INSERT INTO $" + "{dbSchema}.data_item(acode) VALUES ('actual')")));
        valid(fixture.approveExecute(w, run, "job_run", Json.map()));
        assertEquals(
            List.of(Json.map("acode", "actual")),
            Json.list(
                w.call(
                        "db_query",
                        Json.map(
                            "run_id",
                            id,
                            "database",
                            "pub",
                            "sql",
                            "SELECT acode FROM gis_smoke_pub_v1.data_item"))
                    .get("rows")));
        assertEquals(
            "MATCHING",
            w.call("schema_inspect", Json.map("run_id", id, "schema_key", "gis_smoke/schema:edit"))
                .get("status"));
        valid(
            fixture.approveExecute(
                w, run, "schema_recreate", Json.map("schema_key", "gis_smoke/schema:edit")));
        assertEquals(
            0,
            Json.list(
                    w.call(
                            "db_query",
                            Json.map(
                                "run_id",
                                id,
                                "database",
                                "edit",
                                "sql",
                                "SELECT acode FROM gis_smoke_v1.data_item"))
                        .get("rows"))
                .size());
      } finally {
        w.store.with(
            id,
            state -> {
              w.runtime.stop(state, true);
              return null;
            });
      }
    }
  }

  @Test
  void oldRuntimeGenerationsUseUnchangedSharedSchemaLogic() throws Exception {
    for (String profile : List.of("2.x", "3.1")) {
      var fixture = new Fixture(root.resolve(profile.replace(".", "-")));
      for (String folder : List.of("schema", "schema_pub"))
        Json.write(
            fixture.s.repoPath("schema").resolve("topics/gis_smoke/" + folder + "/job.properties"),
            profile.equals("2.x") ? "" : "nodeLabel=gretl-3.1\n");
      Json.write(
          fixture.s.repoPath("gretl").resolve("gis_smoke/job.properties"),
          profile.equals("2.x") ? "" : "nodeLabel=gretl-3.1\n");
      for (String repo : List.of("schema", "gretl"))
        fixture.p.checked(
            List.of("git", "commit", "-am", "Select older runtime"), fixture.s.repoPath(repo), 30);
      try (var w = new Steps(fixture.s)) {
        var run = fixture.start(w, true);
        String id = Json.required(run, "id");
        try {
          valid(fixture.approveExecute(w, run, "schema_create", Json.map()));
          valid(
              fixture.approveExecute(
                  w,
                  run,
                  "data_sql",
                  Json.map(
                      "schema_key",
                      "gis_smoke/schema:edit",
                      "sql",
                      "INSERT INTO gis_smoke_v1.data_item(acode) VALUES ('" + profile + "')")));
          valid(fixture.approveExecute(w, run, "job_run", Json.map("mode", "diagnosis")));
          assertEquals(
              profile,
              Json.obj(
                      Json.list(
                              w.call(
                                      "db_query",
                                      Json.map(
                                          "run_id",
                                          id,
                                          "database",
                                          "pub",
                                          "sql",
                                          "SELECT acode FROM gis_smoke_pub_v1.data_item"))
                                  .get("rows"))
                          .getFirst())
                  .get("acode"));
        } finally {
          w.store.with(
              id,
              state -> {
                w.runtime.stop(state, true);
                return null;
              });
        }
      }
    }
  }

  @Test
  void transactionRollbackReaderPermissionsAndExternalCascadeAreEnforced() throws Exception {
    var fixture = new Fixture(root);
    try (var w = new Steps(fixture.s)) {
      var run = fixture.start(w, true);
      String id = Json.required(run, "id");
      try {
        valid(fixture.approveExecute(w, run, "schema_create", Json.map()));
        w.store.with(
            id,
            state -> {
              assertThrows(
                  Problem.class,
                  () ->
                      Database.dml(
                          w.runtime,
                          state,
                          "edit",
                          "INSERT INTO gis_smoke_v1.data_item(acode) VALUES ('rolled-back'); INSERT INTO gis_smoke_v1.data_item(missing) VALUES ('invalid')"));
              assertTrue(
                  Database.read(
                          w.runtime, state, "edit", "SELECT acode FROM gis_smoke_v1.data_item", 10)
                      .isEmpty());
              assertThrows(
                  Problem.class,
                  () ->
                      Database.read(
                          w.runtime,
                          state,
                          "edit",
                          "DELETE FROM gis_smoke_v1.data_item RETURNING acode",
                          10));
              try (var c = Database.connect(w.runtime, state, "edit", "postgres");
                  var sql = c.createStatement()) {
                sql.execute(
                    "CREATE VIEW public.gis_external_fixture AS SELECT acode FROM gis_smoke_v1.data_item");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              assertEquals(
                  "drop_blocked",
                  assertThrows(
                          Problem.class,
                          () ->
                              w.plan(
                                  state,
                                  "schema_recreate",
                                  Json.map("schema_key", "gis_smoke/schema:edit")))
                      .code);
              return null;
            });
      } finally {
        w.store.with(
            id,
            state -> {
              w.runtime.stop(state, true);
              return null;
            });
      }
    }
  }

  @Test
  void wrongTransformFailsIndependentExpectationAndCannotAuthorizeVerifiedRun() throws Exception {
    var fixture = new Fixture(root);
    try (var w = new Steps(fixture.s)) {
      var run = fixture.start(w, true);
      String id = Json.required(run, "id");
      try {
        w.call(
            "stage_change",
            Json.map(
                "run_id",
                id,
                "repository",
                "gretl",
                "relative_path",
                "gis_smoke/transfer.sql",
                "content",
                "SELECT 'wrong'::text AS acode FROM gis_smoke_v1.data_item"));
        var spec =
            Json.map(
                "requirements",
                "The original fixture code must be preserved.",
                "fixtures",
                List.of(
                    Json.map(
                        "database",
                        "edit",
                        "sql",
                        "INSERT INTO gis_smoke_v1.data_item(acode) VALUES ('expected')")),
                "assertions",
                List.of(
                    Json.map(
                        "id",
                        "code",
                        "database",
                        "pub",
                        "sql",
                        "(SELECT acode FROM gis_smoke_pub_v1.data_item EXCEPT ALL SELECT 'expected') UNION ALL (SELECT 'expected' EXCEPT ALL SELECT acode FROM gis_smoke_pub_v1.data_item)")));
        var result = fixture.approveExecute(w, run, "job_test", spec);
        assertEquals(false, result.get("valid"), Json.pretty(result));
        assertTrue((Boolean) Json.obj(result.get("first_run")).get("gradle_success"));
        assertEquals(
            "test_required",
            assertThrows(
                    Problem.class,
                    () -> w.call("plan", Json.map("run_id", id, "action", "job_run")))
                .code);
      } finally {
        w.store.with(
            id,
            state -> {
              if (Files.exists(w.dir(state).resolve("compose.yaml"))) w.runtime.stop(state, true);
              return null;
            });
      }
    }
  }

  @Test
  void xtfImportIsValidatedAndUsesActualLegacyApi() throws Exception {
    var fixture = new Fixture(root);
    try (var w = new Steps(fixture.s)) {
      var run = fixture.start(w, true);
      String id = Json.required(run, "id");
      try {
        valid(fixture.approveExecute(w, run, "schema_create", Json.map()));
        Path xtf = root.resolve("input.xtf");
        Json.write(
            xtf,
            "<?xml version=\"1.0\"?><TRANSFER xmlns=\"http://www.interlis.ch/INTERLIS2.3\"><HEADERSECTION SENDER=\"fixture\" VERSION=\"2.3\"><MODELS><MODEL NAME=\"GisAgent_Smoke\" VERSION=\"2026-10-08\" URI=\"https://example.invalid\"/></MODELS></HEADERSECTION><DATASECTION><GisAgent_Smoke.Data BID=\"b1\"><GisAgent_Smoke.Data.Item TID=\"i1\"><code>imported</code></GisAgent_Smoke.Data.Item></GisAgent_Smoke.Data></DATASECTION></TRANSFER>");
        var artifact =
            w.call("attach", Json.map("run_id", id, "path", xtf.toString(), "role", "test_input"));
        valid(
            fixture.approveExecute(
                w,
                run,
                "data_xtf",
                Json.map(
                    "schema_key", "gis_smoke/schema:edit", "artifact_id", artifact.get("id"))));
        assertEquals(
            "imported",
            Json.obj(
                    Json.list(
                            w.call(
                                    "db_query",
                                    Json.map(
                                        "run_id",
                                        id,
                                        "database",
                                        "edit",
                                        "sql",
                                        "SELECT acode FROM gis_smoke_v1.data_item"))
                                .get("rows"))
                        .getFirst())
                .get("acode"));
      } finally {
        w.store.with(
            id,
            state -> {
              w.runtime.stop(state, true);
              return null;
            });
      }
    }
  }

  @Test
  void downloadsCsvAndTopicOverrideRunWithRealTasks() throws Exception {
    var fixture = new Fixture(root);
    Path schema = fixture.s.repoPath("schema");
    Json.write(
        schema.resolve("topics/gis_smoke/schema/build.gradle"),
        "task createSchema { dependsOn ':createSchemaShared'; doLast { println 'GIS_TOPIC_OVERRIDE_EXECUTED' } }\n");
    fixture.p.checked(List.of("git", "add", "."), schema, 30);
    fixture.p.checked(List.of("git", "commit", "-m", "Synthetic topic override"), schema, 30);
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("0.0.0.0", 0), 0);
    server.createContext(
        "/fixture.csv",
        exchange -> {
          byte[] bytes = "acode\nfrom-http\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    try (var w = new Steps(fixture.s)) {
      var run = fixture.start(w, true);
      String id = Json.required(run, "id");
      try {
        valid(fixture.approveExecute(w, run, "schema_create", Json.map()));
        String script =
            "import ch.so.agi.gretl.tasks.*\nimport ch.so.agi.gretl.api.TransferSet\napply plugin:'ch.so.agi.gretl'\ndefaultTasks 'exportCsv'\n"
                + "tasks.register('download',Curl) { serverUrl='http://"
                + System.getProperty(
                    "gis.testHttpHost",
                    "true".equals(System.getenv("GIS_CONTAINER_MODE"))
                        ? System.getenv("HOSTNAME")
                        : "host.docker.internal")
                + ":"
                + server.getAddress().getPort()
                + "/fixture.csv'; method=Curl.MethodType.GET; outputFile=file(\"$buildDir/input.csv\");expectedStatusCode=200 }\n"
                + "tasks.register('importCsv',CsvImport) { dependsOn 'download';database=[dbUriEdit,dbUserEdit,dbPwdEdit];schemaName='gis_smoke_v1';tableName='data_item';dataFile=file(\"$buildDir/input.csv\");firstLineIsHeader=true;encoding='UTF-8' }\n"
                + "tasks.register('transfer',Db2Db) { dependsOn 'importCsv';sourceDb=[dbUriEdit,dbUserEdit,dbPwdEdit];targetDb=[dbUriPub,dbUserPub,dbPwdPub];transferSets=[new TransferSet('transfer.sql','gis_smoke_pub_v1.data_item',true)] }\n"
                + "tasks.register('exportCsv',CsvExport) { dependsOn 'transfer';database=[dbUriPub,dbUserPub,dbPwdPub];schemaName='gis_smoke_pub_v1';tableName='data_item';dataFile=file(\"$buildDir/output.csv\");attributes=['acode'];firstLineIsHeader=true;encoding='UTF-8' }\n";
        w.call(
            "stage_change",
            Json.map(
                "run_id",
                id,
                "repository",
                "gretl",
                "relative_path",
                "gis_smoke/build.gradle",
                "content",
                script));
        var result = fixture.approveExecute(w, run, "job_run", Json.map("mode", "diagnosis"));
        valid(result);
        assertEquals(
            List.of(Json.map("acode", "from-http")),
            Json.list(
                w.call(
                        "db_query",
                        Json.map(
                            "run_id",
                            id,
                            "database",
                            "pub",
                            "sql",
                            "SELECT acode FROM gis_smoke_pub_v1.data_item"))
                    .get("rows")));
        var output =
            Json.list(result.get("artifacts")).stream()
                .map(Json::obj)
                .filter(a -> "output.csv".equals(a.get("name")))
                .findFirst()
                .orElseThrow();
        assertTrue(Json.contents(Path.of(Json.required(output, "path"))).contains("from-http"));
        w.store.with(
            id,
            state -> {
              var record = Json.obj(Json.obj(state.get("schemas")).get("gis_smoke/schema:edit"));
              Path log =
                  w.dir(state)
                      .resolve("attempts")
                      .resolve(Json.required(record, "attempt"))
                      .resolve("gretl.log");
              assertTrue(Json.contents(log).contains("GIS_TOPIC_OVERRIDE_EXECUTED"));
              return null;
            });
      } finally {
        w.store.with(
            id,
            state -> {
              w.runtime.stop(state, true);
              return null;
            });
      }
    } finally {
      server.stop(0);
    }
  }
}
