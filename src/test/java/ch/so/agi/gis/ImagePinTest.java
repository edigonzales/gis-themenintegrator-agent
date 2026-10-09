package ch.so.agi.gis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ImagePinTest {
  @TempDir Path root;

  Settings settings(String source) {
    return new Settings(
        root.resolve("config/local.toml"),
        Json.map(
            "images", Json.map("postgis", "gis-postgis:18-3.6-arm64", "3.2", "registry/gretl:3.2"),
            "image_sources", Json.map("postgis", source)));
  }

  static class Docker extends Processes {
    final Map<String, Map<String, Object>> available = new HashMap<>();
    final List<String> pulls = new ArrayList<>();

    void add(String reference, String id, boolean registry) {
      var record =
          Json.map(
              "id",
              id,
              "architecture",
              "arm64",
              "digests",
              registry ? List.of(reference + "@" + id) : List.of());
      available.put(reference, record);
      available.put(id, record);
      if (registry) available.put(reference + "@" + id, record);
    }

    @Override
    Result run(List<String> command, Path cwd, int timeout, Path log) {
      assertEquals("docker", command.getFirst());
      String reference = command.getLast();
      if (command.contains("pull")) {
        pulls.add(reference);
        var pinned = available.get(reference);
        if (reference.contains("@") && pinned != null)
          available.put(pinned.get("id").toString(), pinned);
        else add(reference, "sha256:" + Json.digest(reference + pulls.size()), true);
        return new Result(0, "synthetic registry pull");
      }
      var record = available.get(reference);
      if (record == null) return new Result(1, "synthetic image absent");
      String format = command.get(command.indexOf("--format") + 1);
      return new Result(
          0, format.startsWith("{") ? Json.text(record) : record.get("id").toString());
    }
  }

  @Test
  void localTagMovesOnlyOnExplicitUpdateAndNeverPulls() {
    var s = settings("local");
    var docker = new Docker();
    docker.add(s.image("postgis"), "sha256:first", false);
    docker.add(s.image("3.2"), "sha256:gretl", true);
    var runtime = new DockerRuntime(s, docker);
    runtime.pin(false);
    var gretl = new LinkedHashMap<>(Json.obj(runtime.images().get("3.2")));
    assertEquals("local", runtime.image("postgis").get("source"));
    assertEquals("", runtime.image("postgis").get("digest"));
    docker.add(s.image("postgis"), "sha256:second", false);
    runtime.pin(false);
    assertEquals("sha256:first", runtime.image("postgis").get("id"));
    runtime.pin(false, "postgis");
    assertEquals("sha256:second", runtime.image("postgis").get("id"));
    assertEquals(gretl, runtime.images().get("3.2"));
    assertTrue(docker.pulls.isEmpty());
    runtime.pin(true);
    assertEquals(List.of(s.image("3.2")), docker.pulls);
  }

  @Test
  void missingLocalImageOrPinnedIdNeverFallsBackToRegistryOrMovedTag() {
    var s = settings("local");
    var docker = new Docker();
    var runtime = new DockerRuntime(s, docker);
    assertEquals("local_image_missing", assertThrows(Problem.class, () -> runtime.pin(false)).code);
    docker.add(s.image("postgis"), "sha256:first", false);
    docker.add(s.image("3.2"), "sha256:gretl", true);
    runtime.pin(false);
    docker.available.remove("sha256:first");
    docker.add(s.image("postgis"), "sha256:second", false);
    assertEquals(
        "local_image_missing", assertThrows(Problem.class, () -> runtime.image("postgis")).code);
    var run = Json.map("runtime_images", runtime.images());
    assertEquals(
        "local_image_missing",
        assertThrows(Problem.class, () -> runtime.runImage(run, "postgis")).code);
    assertEquals("local_image_missing", assertThrows(Problem.class, () -> runtime.pin(false)).code);
    assertEquals("sha256:first", Json.obj(runtime.images().get("postgis")).get("id"));
    runtime.pin(false, "postgis");
    assertEquals("sha256:second", runtime.image("postgis").get("id"));
    assertTrue(docker.pulls.isEmpty());
  }

  @Test
  void targetedUpdatePreservesLegacyRegistryPinAndAllowsSourceChange() {
    var s = settings("registry");
    var docker = new Docker();
    var runtime = new DockerRuntime(s, docker);
    runtime.pin(false);
    var pins = runtime.images();
    Json.obj(pins.get("3.2")).remove("source"); // Existing format-v1 locks are registry pins.
    Json.atomic(runtime.lockFile(), Json.map("format_version", 1, "images", pins));
    var oldGretl = new LinkedHashMap<>(Json.obj(pins.get("3.2")));
    docker.pulls.clear();
    docker.add(s.image("postgis"), "sha256:native", false);
    var nativeRuntime = new DockerRuntime(settings("local"), docker);
    assertEquals(
        "runtime_changed", assertThrows(Problem.class, () -> nativeRuntime.image("postgis")).code);
    nativeRuntime.pin(false, "postgis");
    assertEquals(oldGretl, nativeRuntime.images().get("3.2"));
    assertEquals("sha256:native", nativeRuntime.image("postgis").get("id"));
    assertTrue(docker.pulls.isEmpty());
  }

  @Test
  void restoringMissingRegistryIdKeepsItsLegacyRecordDuringTargetedUpdate() {
    var s = settings("local");
    var docker = new Docker();
    docker.add(s.image("postgis"), "sha256:native", false);
    docker.add(s.image("3.2"), "sha256:gretl", true);
    var runtime = new DockerRuntime(s, docker);
    runtime.pin(false);
    var pins = runtime.images();
    Json.obj(pins.get("3.2")).remove("source");
    Json.atomic(runtime.lockFile(), Json.map("format_version", 1, "images", pins));
    var legacy = new LinkedHashMap<>(Json.obj(pins.get("3.2")));
    docker.available.remove("sha256:gretl");
    docker.available.remove(s.image("3.2"));
    runtime.pin(false, "postgis");
    assertEquals(legacy, runtime.images().get("3.2"));
    assertEquals(List.of(legacy.get("digest")), docker.pulls);
  }

  @Test
  void imageSourcesAndUpdateTargetsAreValidatedBeforeDockerCalls() {
    assertEquals(
        "configuration_invalid", assertThrows(Problem.class, () -> settings("invalid")).code);
    var s = settings("local");
    var values = new LinkedHashMap<>(s.values);
    values.put("image_sources", Json.map("unknown", "local"));
    assertEquals(
        "configuration_invalid",
        assertThrows(Problem.class, () -> new Settings(s.file, values)).code);
    values.put("image_sources", List.of("local"));
    assertEquals(
        "configuration_invalid",
        assertThrows(Problem.class, () -> new Settings(s.file, values)).code);
    var runtime = new DockerRuntime(s, new Docker());
    assertEquals(
        "invalid_arguments", assertThrows(Problem.class, () -> runtime.pin(true, "postgis")).code);
    assertEquals(
        "invalid_arguments", assertThrows(Problem.class, () -> runtime.pin(false, "unknown")).code);
    assertFalse(Files.exists(runtime.lockFile()));
  }

  @Test
  void cliRejectsMissingUnknownOrConflictingUpdateOptionsBeforeInit() {
    for (var flags :
        List.of(
            List.of("--update-image"),
            List.of("--update-image", "unknown"),
            List.of("--update-image", "postgis", "--update-tools"),
            List.of("--update-image", "postgis", "--update-image", "postgis"))) {
      var args = new ArrayList<>(List.of("init"));
      args.addAll(flags);
      assertEquals(2, Main.execute(args.toArray(String[]::new)));
    }
  }
}
