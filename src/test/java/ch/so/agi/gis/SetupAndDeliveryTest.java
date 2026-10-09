package ch.so.agi.gis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SetupAndDeliveryTest {
  @Test
  void interruptedImagePreparationKeepsCompletedPinsAndResumes() throws Exception {
    var fixture = new Fixture(root);
    Files.delete(fixture.s.state.resolve("tools/images.json"));
    var values = new LinkedHashMap<>(fixture.s.values);
    values.put("images", Json.map("2.x", fixture.s.image("2.x"), "3.2", fixture.s.image("3.2")));
    var sources = new LinkedHashMap<>(Json.obj(values.get("image_sources")));
    sources.keySet().retainAll(Set.of("2.x", "3.2"));
    values.put("image_sources", sources);
    var s = new Settings(fixture.s.file, values);
    class FakeDocker extends Processes {
      boolean fail = true;
      final Map<String, Map<String, Object>> available = new HashMap<>();
      final Map<String, Integer> pulls = new HashMap<>();

      @Override
      Result run(List<String> command, Path cwd, int timeout, Path log) {
        assertEquals("docker", command.getFirst());
        String reference = command.getLast();
        if (command.contains("pull")) {
          if (fail && reference.equals(s.image("3.2")))
            return new Result(1, "simulated interrupted download");
          pulls.merge(reference, 1, Integer::sum);
          String id = "sha256:" + Json.digest(reference);
          var record =
              Json.map("id", id, "architecture", "arm64", "digests", List.of("fixture@" + id));
          available.put(reference, record);
          available.put(id, record);
          return new Result(0, "synthetic image prepared");
        }
        var record = available.get(reference);
        if (record == null) return new Result(1, "synthetic image absent");
        String format = command.get(command.indexOf("--format") + 1);
        return new Result(
            0, format.startsWith("{") ? Json.text(record) : record.get("id").toString());
      }
    }
    var docker = new FakeDocker();
    var runtime = new DockerRuntime(s, docker);
    assertThrows(Problem.class, () -> runtime.pin(false));
    assertTrue(runtime.images().containsKey("2.x"));
    assertFalse(runtime.images().containsKey("3.2"));
    String id = Json.required(Json.obj(runtime.images().get("2.x")), "id");
    docker.fail = false;
    assertTrue(runtime.pin(false).containsKey("3.2"));
    assertEquals(id, Json.obj(runtime.images().get("2.x")).get("id"));
    assertEquals(1, docker.pulls.get(s.image("2.x")));
  }

  @TempDir Path root;

  @Test
  void missingRepositoriesCloneWithoutChangingExistingSourceBranches() throws Exception {
    var fixture = new Fixture(root);
    var values = Json.read(Json.text(fixture.s.values));
    for (String name : List.of("gretl", "schema", "models"))
      Json.obj(Json.obj(values.get("repositories")).get(name)).put("path", "fresh/" + name);
    var fresh = new Settings(fixture.s.file, values);
    var repos = new Repositories(fresh, new Processes());
    for (String name : List.of("gretl", "schema", "models")) {
      assertEquals(fresh.repoPath(name).toString(), repos.ensure(name).get("path"));
      assertTrue(Files.isRegularFile(fresh.repoPath(name).resolve(".git/config")));
      assertEquals(
          name.equals("models") ? "master" : "main",
          fixture.p.checked(
              List.of("git", "branch", "--show-current"), fixture.s.repoPath(name), 30));
    }
  }

  @Test
  void sourceEditBeforeStagingIsNotAdoptedAsAnUnseenBaseline() throws Exception {
    var fixture = new Fixture(root);
    try (var w = new Steps(fixture.s)) {
      var run = fixture.start(w, false);
      String id = Json.required(run, "id");
      Json.write(
          fixture.s.repoPath("gretl").resolve("gis_smoke/transfer.sql"), "SELECT 'human' AS acode");
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
              "SELECT 'agent' AS acode"));
      var result = fixture.approveExecute(w, run, "changes", Json.map());
      assertEquals("checkout_conflict", Json.obj(result.get("problem")).get("error"));
      assertEquals(
          "SELECT 'human' AS acode",
          Json.contents(fixture.s.repoPath("gretl").resolve("gis_smoke/transfer.sql")));
      assertTrue(Files.isDirectory(w.work(run, "gretl").getParent().resolve("schema-jobs")));
    }
  }

  @Test
  void planTamperingAndChangedLocalDependencyInvalidateEvidence() throws Exception {
    var fixture = new Fixture(root);
    try (var w = new Steps(fixture.s)) {
      var run = fixture.start(w, false);
      String id = Json.required(run, "id");
      w.call(
          "stage_change",
          Json.map(
              "run_id",
              id,
              "repository",
              "gretl",
              "relative_path",
              "new_job/sql.sql",
              "content",
              "SELECT 1"));
      var plan = Json.obj(w.call("plan", Json.map("run_id", id, "action", "changes")).get("plan"));
      w.store.with(
          id,
          state -> {
            Json.obj(
                    Json.obj(Json.obj(state.get("plans")).get(plan.get("id").toString()))
                        .get("spec"))
                .put("delivery", "pr");
            return null;
          });
      assertEquals(
          "plan_changed",
          assertThrows(
                  Problem.class,
                  () ->
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
                              "ISOLATED FIXTURE ONLY")))
              .code);
    }
    Path models = fixture.s.repoPath("models");
    Json.write(
        models.resolve("models/DEMO/GisAgent_Dep.ili"),
        "INTERLIS 2.3; MODEL GisAgent_Dep (en) AT \"https://example.invalid\" VERSION \"2026-10-08\" = STRUCTURE Value = label : TEXT*30; END Value; END GisAgent_Dep.");
    Json.write(
        models.resolve("models/DEMO/GisAgent_Smoke.ili"),
        "INTERLIS 2.3; MODEL GisAgent_Smoke (en) AT \"https://example.invalid\" VERSION \"2026-10-08\" = IMPORTS GisAgent_Dep; TOPIC Data = CLASS Item = value : GisAgent_Dep.Value; END Item; END Data; END GisAgent_Smoke.");
    var resolver = new Models(fixture.s);
    Path directory = root.resolve("model-run");
    var first =
        resolver.prepare(
            models, fixture.s.repoPath("schema"), directory, List.of("GisAgent_Smoke"), Json.map());
    Json.write(
        models.resolve("models/DEMO/GisAgent_Dep.ili"),
        "INTERLIS 2.3; MODEL GisAgent_Dep (en) AT \"https://example.invalid\" VERSION \"2026-10-08\" = STRUCTURE Value = label : TEXT*50; END Value; END GisAgent_Dep.");
    var second =
        resolver.prepare(
            models, fixture.s.repoPath("schema"), directory, List.of("GisAgent_Smoke"), Json.map());
    assertNotEquals(
        Json.obj(first.get("file_hashes")).get("GisAgent_Dep.ili"),
        Json.obj(second.get("file_hashes")).get("GisAgent_Dep.ili"));
  }

  @Test
  void simulatedGhDeliveryUsesApprovedTreeAndResumesWithoutDuplicate() throws Exception {
    var fixture = new Fixture(root);
    var settings = new LinkedHashMap<>(fixture.s.values);
    settings.put("git", Json.map("name", "Fixture", "email", "fixture@example.invalid"));
    var s = new Settings(fixture.s.file, settings);
    var gh =
        new Processes() {
          int created;

          @Override
          Result run(List<String> command, Path cwd, int timeout, Path log) {
            if (command.getFirst().equals("gh")) {
              if (command.contains("create")) {
                created++;
                assertEquals(1, created);
                return new Result(0, "https://github.com/fixture/example/pull/1\n");
              }
              return new Result(0, "[]");
            }
            return super.run(command, cwd, timeout, log);
          }
        };
    try (var w = new Steps(s, gh)) {
      var run = fixture.start(w, false);
      String id = Json.required(run, "id");
      w.call(
          "stage_change",
          Json.map(
              "run_id",
              id,
              "repository",
              "gretl",
              "relative_path",
              "new_job/sql.sql",
              "content",
              "SELECT 1"));
      var delivered =
          fixture.approveExecute(
              w, run, "changes", Json.map("delivery", "pr", "title", "Synthetic fixture"));
      assertEquals(true, delivered.get("valid"), Json.pretty(delivered));
      var record = Json.obj(Json.list(delivered.get("pull_requests")).getFirst());
      assertTrue(Json.required(record, "branch").startsWith("codex/gis-"));
      w.store.with(
          id,
          state -> {
            var stored =
                Json.obj(Json.obj(state.get("plans")).get(delivered.get("plan_id").toString()));
            var again = new GitDelivery(w).pullRequests(state, Json.obj(stored.get("spec")));
            assertEquals(
                Json.text(delivered.get("pull_requests")), Json.text(again.get("pull_requests")));
            return null;
          });
    }
  }
}
