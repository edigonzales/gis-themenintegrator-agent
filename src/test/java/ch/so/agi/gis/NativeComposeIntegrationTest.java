package ch.so.agi.gis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("integration")
class NativeComposeIntegrationTest {
  @Test
  void externalOverridesKeepOriginalConfigurationAndInitializeAllNativeDatabases()
      throws Exception {
    var s = Settings.load(null);
    Assumptions.assumeTrue(s.imageSource("postgis").equals("local"));
    Assumptions.assumeTrue(s.image("postgis").equals("gis-postgis:18-3.6-arm64"));
    var p = new Processes();
    Path helper = s.root.resolve("runtime/postgis-arm64/gretl-compose.sh");
    String project = "gis-native-fixture-" + UUID.randomUUID().toString().substring(0, 8);
    Path evidence = s.state.resolve("acceptance").resolve(project);
    Files.createDirectories(evidence);
    var services = List.of("edit-db", "pub-db", "oereb_v2-db", "processing-db");
    var original =
        new ArrayList<>(
            List.of("docker", "compose", "--project-directory", s.repoPath("gretl").toString()));
    for (String file : List.of("compose.yaml", "compose.oereb_v2.yaml", "compose.processing.yaml"))
      original.addAll(List.of("-f", s.repoPath("gretl").resolve(file).toString()));
    original.addAll(List.of("config", "--format", "json"));
    var base = Json.obj(Json.read(p.checked(original, s.root, 30)).get("services"));
    var command =
        new ArrayList<>(
            List.of(
                helper.toString(),
                "--gretl-repo",
                s.repoPath("gretl").toString(),
                "--oereb",
                "--processing",
                "--project-name",
                project));
    var mergedCommand = new ArrayList<>(command);
    mergedCommand.addAll(List.of("config", "--format", "json"));
    var merged = Json.obj(Json.read(p.checked(mergedCommand, s.root, 30)).get("services"));
    for (String service : services) {
      var before = Json.obj(base.get(service));
      var after = Json.obj(merged.get(service));
      assertEquals("gis-postgis:18-3.6-arm64", after.get("image"));
      assertEquals("linux/arm64", after.get("platform"));
      assertEquals("never", after.get("pull_policy"));
      assertTrue(
          Objects.equals(before.get("environment"), after.get("environment")),
          "Original DB environment changed");
      assertTrue(
          Objects.equals(before.get("volumes"), after.get("volumes")),
          "Original DB mounts changed");
      assertTrue(
          Objects.equals(before.get("command"), after.get("command")),
          "Original DB command changed");
      assertTrue(Path.of(Json.required(Json.obj(after.get("build")), "context")).isAbsolute());
    }
    assertEquals(base.keySet(), merged.keySet());
    // Isolated software fixture: preserve original initialization, replace only host ports.
    Path ports = evidence.resolve("fixture-ports.yaml");
    var override = new StringBuilder("services:\n");
    for (String service : services)
      override
          .append("  ")
          .append(service)
          .append(":\n    ports: !override [\"127.0.0.1::5432\"]\n");
    Json.write(ports, override.toString());
    command.addAll(List.of("-f", ports.toString()));
    try {
      var up = new ArrayList<>(command);
      up.addAll(List.of("up", "-d"));
      up.addAll(services);
      p.checked(up, s.root, 120);
      for (String service : services) {
        var environment = Json.obj(Json.obj(merged.get(service)).get("environment"));
        String user = Json.str(environment, "POSTGRES_USER", "postgres");
        String database = Json.str(environment, "POSTGRES_DB", user);
        var ready = new ArrayList<>(command);
        ready.addAll(
            List.of(
                "exec",
                "-T",
                service,
                "sh",
                "-c",
                "for i in $(seq 1 90); do pg_isready -h 127.0.0.1 -U postgres >/dev/null 2>&1 && exit 0; sleep 1; done; exit 1"));
        p.checked(ready, s.root, 100);
        var query = new ArrayList<>(command);
        query.addAll(
            List.of(
                "exec",
                "-T",
                service,
                "psql",
                "-X",
                "-U",
                user,
                "-d",
                database,
                "-At",
                "-c",
                "SELECT extname FROM pg_extension ORDER BY extname"));
        String extensions = p.checked(query, s.root, 30);
        assertTrue(extensions.lines().anyMatch("postgis"::equals));
        assertTrue(extensions.lines().anyMatch("postgis_topology"::equals));
        if (!service.equals("processing-db")) {
          assertTrue(extensions.lines().anyMatch("postgis_raster"::equals));
          assertTrue(extensions.lines().anyMatch("uuid-ossp"::equals));
          var roles = new ArrayList<>(command);
          roles.addAll(
              List.of(
                  "exec",
                  "-T",
                  service,
                  "psql",
                  "-X",
                  "-U",
                  user,
                  "-d",
                  database,
                  "-At",
                  "-c",
                  "SELECT count(*) FROM pg_roles WHERE rolname IN ('ddluser','dmluser','gretl')"));
          assertEquals("3", p.checked(roles, s.root, 30));
        }
      }
      String architecture =
          p.checked(
              List.of(
                  "docker",
                  "image",
                  "inspect",
                  "--format",
                  "{{.Architecture}}",
                  s.image("postgis")),
              s.root,
              30);
      assertEquals("arm64", architecture);
      Json.atomic(
          evidence.resolve("result.json"),
          Json.map(
              "valid",
              true,
              "test_only",
              true,
              "services",
              services,
              "architecture",
              architecture,
              "original_initialization",
              true));
    } finally {
      var down = new ArrayList<>(command);
      down.addAll(List.of("down", "--volumes"));
      p.checked(down, s.root, 90);
    }
  }
}
