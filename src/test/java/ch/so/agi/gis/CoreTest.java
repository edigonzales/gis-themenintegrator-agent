package ch.so.agi.gis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CoreTest {
  @TempDir Path root;

  @Test
  void dottedImageKeysAndLegacyVersionsRemainExact() throws Exception {
    var fixture = new Fixture(root);
    assertEquals("sogis/gretl:2.2.617", fixture.s.image("2.x"));
    assertEquals("2.x", fixture.s.runtime(Map.of()));
    assertEquals("3.2", fixture.s.runtime(Map.of("nodeLabel", "gretl-3.2")));
    assertThrows(Problem.class, () -> fixture.s.runtime(Map.of("nodeLabel", "unavailable")));
    var schemas =
        Legacy.schemas(fixture.s, root.resolve("repos/schema"), List.of("schema_gis_smoke_pub"));
    assertEquals("gis_smoke_pub_v1", schemas.getFirst().get("physical_name"));
    assertEquals("pub", schemas.getFirst().get("database"));
  }

  @Test
  void branchesAreIndependentAndSourceTreesArePreserved() throws Exception {
    var fixture = new Fixture(root);
    Path gretl = fixture.s.repoPath("gretl"), models = fixture.s.repoPath("models");
    fixture.p.checked(List.of("git", "switch", "-c", "ticket"), gretl, 30);
    Json.write(gretl.resolve("gis_smoke/transfer.sql"), "SELECT 'ticket' AS code");
    fixture.p.checked(List.of("git", "commit", "-am", "Ticket branch"), gretl, 30);
    fixture.p.checked(List.of("git", "switch", "main"), gretl, 30);
    Json.write(models.resolve("models/DEMO/working.ili"), "uncommitted fixture");
    String before = fixture.p.checked(List.of("git", "status", "--short"), models, 30);
    try (var workflow = new Steps(fixture.s)) {
      String id =
          Json.required(
              workflow.call(
                  "start",
                  Json.map(
                      "refs", Json.map("gretl", "ticket", "schema", "main", "models", "master"))),
              "run_id");
      workflow.store.with(
          id,
          run -> {
            assertEquals(
                "SELECT 'ticket' AS code",
                Json.contents(workflow.work(run, "gretl").resolve("gis_smoke/transfer.sql")));
            assertFalse(
                Files.exists(workflow.work(run, "models").resolve("models/DEMO/working.ili")));
            return null;
          });
    }
    assertEquals(before, fixture.p.checked(List.of("git", "status", "--short"), models, 30));
    assertEquals("main", fixture.p.checked(List.of("git", "branch", "--show-current"), gretl, 30));
  }

  @Test
  void approvalIsRequiredBoundToFilesAndConsumedOnce() throws Exception {
    var fixture = new Fixture(root);
    try (var workflow = new Steps(fixture.s)) {
      var run = fixture.start(workflow, false);
      String id = Json.required(run, "id");
      workflow.call(
          "stage_change",
          Json.map(
              "run_id",
              id,
              "repository",
              "gretl",
              "relative_path",
              "new_job/query.sql",
              "content",
              "SELECT 1"));
      var plan =
          Json.obj(workflow.call("plan", Json.map("run_id", id, "action", "changes")).get("plan"));
      assertEquals(
          "approval_required",
          assertThrows(
                  Problem.class,
                  () -> workflow.call("execute", Json.map("run_id", id, "plan_id", plan.get("id"))))
              .code);
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
      workflow.call(
          "stage_change",
          Json.map(
              "run_id",
              id,
              "repository",
              "gretl",
              "relative_path",
              "new_job/query.sql",
              "content",
              "SELECT 2"));
      assertEquals(
          "approval_stale",
          assertThrows(
                  Problem.class,
                  () -> workflow.call("execute", Json.map("run_id", id, "plan_id", plan.get("id"))))
              .code);
      var completed = fixture.approveExecute(workflow, run, "changes", Json.map());
      assertEquals("APPLIED", completed.get("status"));
      assertEquals(
          "SELECT 2", Json.contents(fixture.s.repoPath("gretl").resolve("new_job/query.sql")));
      assertEquals(
          "plan_consumed",
          assertThrows(
                  Problem.class,
                  () ->
                      workflow.call(
                          "execute", Json.map("run_id", id, "plan_id", completed.get("plan_id"))))
              .code);
    }
  }

  @Test
  void localDeliveryDetectsConcurrentChangesBeforeWriting() throws Exception {
    var fixture = new Fixture(root);
    try (var workflow = new Steps(fixture.s)) {
      var run = fixture.start(workflow, false);
      String id = Json.required(run, "id");
      workflow.call(
          "stage_change",
          Json.map(
              "run_id",
              id,
              "repository",
              "gretl",
              "relative_path",
              "gis_smoke/transfer.sql",
              "content",
              "SELECT 'changed' AS code"));
      Json.write(
          fixture.s.repoPath("gretl").resolve("gis_smoke/transfer.sql"), "SELECT 'human' AS code");
      var result = fixture.approveExecute(workflow, run, "changes", Json.map());
      assertFalse((Boolean) result.get("valid"));
      assertEquals("checkout_conflict", Json.obj(result.get("problem")).get("error"));
      assertEquals(
          "SELECT 'human' AS code",
          Json.contents(fixture.s.repoPath("gretl").resolve("gis_smoke/transfer.sql")));
    }
  }

  @Test
  void schemaDefaultsAndMultiDatabaseRoleSuffixFollowLegacy() throws Exception {
    var fixture = new Fixture(root);
    Json.write(
        root.resolve("repos/schema/topics/gis_smoke/schema/schema.properties"),
        "schemaVersion=2\ndatabases=edit,pub\nmodels=GisAgent_Smoke\nschemaName=custom\n");
    var schemas =
        Legacy.schemas(fixture.s, root.resolve("repos/schema"), List.of("gis_smoke/schema"));
    assertEquals(2, schemas.size());
    assertEquals("custom_v2", schemas.getFirst().get("physical_name"));
    assertEquals("_editdb", schemas.getFirst().get("role_suffix"));
    assertEquals("_pubdb", schemas.getLast().get("role_suffix"));
  }

  @Test
  void modelClosureIsCompiledAndArchiveTamperingIsRejected() throws Exception {
    var fixture = new Fixture(root);
    var evidence =
        new Models(fixture.s)
            .prepare(
                root.resolve("repos/models"),
                root.resolve("repos/schema"),
                root.resolve("model-run"),
                List.of("GisAgent_Smoke"),
                Json.map());
    assertTrue((Boolean) evidence.get("compiled"));
    Json.write(root.resolve("model-run/frozen-models/GisAgent_Smoke.ili"), "changed");
    assertEquals(
        "model_evidence_changed",
        assertThrows(Problem.class, () -> Models.verify(root.resolve("model-run"), evidence)).code);
  }

  @Test
  void malformedSqlAndUnsafePathsCannotBecomeSilentSuccess() throws Exception {
    var fixture = new Fixture(root);
    assertThrows(
        Problem.class, () -> Repositories.businessPath("schema", "shared/schema/build.gradle"));
    assertThrows(Problem.class, () -> Repositories.businessPath("models", "../escape.ili"));
    assertThrows(Problem.class, () -> Database.ident("foo;drop schema"));
    assertThrows(
        Problem.class,
        () ->
            Operations.validate(
                "approve",
                Json.map(
                    "run_id", "x", "plan_id", "p", "fingerprint", "f", "human_statement", true)));
    assertThrows(Problem.class, () -> Operations.validate("start", Json.map("unknown", "field")));
  }

  @Test
  void referenceSectionsAndReportsPreserveContentWithoutExecutingHtml() {
    var reference =
        Knowledge.extract(
            "<h3 id='db2db'>Db2Db</h3><p>Parameters</p><pre>tasks.register('x', Db2Db)</pre><h3 id='next'>Next (deprecated)</h3><p>Other</p>");
    assertTrue(Json.required(Json.obj(reference.get("Db2Db")), "text").contains("tasks.register"));
    assertTrue(reference.containsKey("Next"));
    Path report =
        Reports.write(
            root.resolve("report.html"),
            "Review",
            Json.map("untrusted", "<script>alert(1)</script>"));
    assertFalse(Json.contents(report).contains("<script>"));
  }
}
