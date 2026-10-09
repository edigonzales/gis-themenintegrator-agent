package ch.so.agi.gis;

import java.nio.file.*;
import java.util.*;

final class DockerRuntime {
  final Settings s;
  final Processes p;

  DockerRuntime(Settings s, Processes p) {
    this.s = s;
    this.p = p;
  }

  Path lockFile() {
    return s.state.resolve("tools/images.json");
  }

  Map<String, Object> images() {
    if (!Files.isRegularFile(lockFile()))
      throw new Problem("init_required", "Zuerst bin/gis-agent init ausführen.");
    return Json.obj(Json.read(lockFile()).get("images"));
  }

  Map<String, Object> image(String key) {
    var image = Json.obj(images().get(key));
    if (!Objects.equals(image.get("reference"), s.image(key))
        || !Json.str(image, "source", "registry").equals(s.imageSource(key)))
      throw new Problem(
          "runtime_changed",
          "Image-Konfiguration geändert; init --update-image " + key + " ausführen.",
          "profile",
          key);
    verifyImage(key, image);
    return image;
  }

  Map<String, Object> runImage(Map<String, Object> run, String key) {
    var selected = Json.obj(Json.obj(run.get("runtime_images")).get(key));
    if (selected.isEmpty()) return image(key);
    verifyImage(key, selected);
    return selected;
  }

  void verifyImage(String key, Map<String, Object> image) {
    try {
      p.checked(
          List.of("docker", "image", "inspect", "--format", "{{.Id}}", Json.required(image, "id")),
          s.root,
          s.timeout);
    } catch (Problem error) {
      if (error.code.equals("command_failed") && "local".equals(image.get("source")))
        throw localImageMissing(key, Json.required(image, "reference"));
      throw error;
    }
  }

  Map<String, Object> pin(boolean update) {
    return pin(update, null);
  }

  Map<String, Object> pin(boolean update, String updateImage) {
    if (update && updateImage != null)
      throw new Problem(
          "invalid_arguments", "--update-tools und --update-image nicht kombinieren.");
    if (updateImage != null && !s.images().containsKey(updateImage))
      throw new Problem(
          "invalid_arguments", "Unbekannter Image-Schlüssel.", "profile", updateImage);
    var images =
        Files.isRegularFile(lockFile())
            ? Json.obj(Json.read(lockFile()).get("images"))
            : Json.map();
    for (String key : s.images().keySet()) {
      String reference = s.image(key);
      String source = s.imageSource(key);
      boolean refresh = update || key.equals(updateImage);
      var previous = Json.obj(images.get(key));
      if (!refresh && !previous.isEmpty()) {
        if (!reference.equals(previous.get("reference"))
            || !source.equals(Json.str(previous, "source", "registry")))
          throw new Problem(
              "runtime_changed",
              "Image-Konfiguration geändert; init --update-image " + key + " ausführen.",
              "profile",
              key);
        var existing =
            p.run(
                List.of(
                    "docker",
                    "image",
                    "inspect",
                    "--format",
                    "{{.Id}}",
                    Json.required(previous, "id")),
                s.root,
                s.timeout,
                null);
        if (existing.exitCode() == 0) continue;
        if (source.equals("local")) throw localImageMissing(key, reference);
        String digest = Json.str(previous, "digest", "");
        if (digest.isEmpty())
          throw new Problem(
              "image_missing",
              "Festgehaltenes Image fehlt und hat keinen Registry-Digest.",
              "profile",
              key);
        p.checked(List.of("docker", "pull", digest), s.root, Math.max(600, s.timeout));
        p.checked(
            List.of(
                "docker", "image", "inspect", "--format", "{{.Id}}", Json.required(previous, "id")),
            s.root,
            s.timeout);
        // Restoring a pinned registry image does not rewrite unrelated legacy lock records.
        continue;
      } else {
        var existing =
            p.run(
                List.of("docker", "image", "inspect", "--format", "{{.Id}}", reference),
                s.root,
                s.timeout,
                null);
        if (source.equals("local") && existing.exitCode() != 0)
          throw localImageMissing(key, reference);
        if (source.equals("registry") && (existing.exitCode() != 0 || refresh))
          p.checked(List.of("docker", "pull", reference), s.root, Math.max(600, s.timeout));
      }
      var inspected =
          Json.read(
              p.checked(
                  List.of(
                      "docker",
                      "image",
                      "inspect",
                      "--format",
                      "{\"id\":{{json .Id}},\"digests\":{{json .RepoDigests}},\"architecture\":{{json .Architecture}}}",
                      reference),
                  s.root,
                  s.timeout));
      var digests = Json.strings(inspected.get("digests"));
      inspected.put("reference", reference);
      inspected.put("source", source);
      inspected.put("digest", digests.isEmpty() ? "" : digests.getFirst());
      images.put(key, inspected);
      Json.atomic(lockFile(), Json.map("format_version", 1, "images", images));
    }
    return images;
  }

  Problem localImageMissing(String key, String reference) {
    return new Problem(
        "local_image_missing",
        "Lokales Image fehlt. Zuerst bauen; danach init --update-image " + key + " ausführen.",
        "profile",
        key,
        "reference",
        reference);
  }

  String project(Map<String, Object> run) {
    return "gis-agent-" + Json.required(run, "id").substring(0, 16);
  }

  Path dir(Map<String, Object> run) {
    return s.state.resolve("runs").resolve(Json.required(run, "id"));
  }

  String service(String profile) {
    return "gretl-" + profile.replace(".", "-");
  }

  List<String> compose(Map<String, Object> run, String... arguments) {
    var command =
        new ArrayList<>(
            List.of(
                "docker",
                "compose",
                "--project-name",
                project(run),
                "-f",
                dir(run).resolve("compose.yaml").toString()));
    command.addAll(List.of(arguments));
    return command;
  }

  String password(Map<String, Object> run) {
    Path file = dir(run).resolve("credentials.json");
    if (!Files.exists(file)) {
      Json.atomic(file, Json.map("password", UUID.randomUUID().toString().replace("-", "")));
      privateFile(file);
    }
    String password = Json.required(Json.read(file), "password");
    p.secret(password);
    return password;
  }

  static void privateFile(Path file) {
    try {
      Files.setPosixFilePermissions(
          file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    } catch (Exception ignored) {
    }
  }

  void configure(Map<String, Object> run) {
    Path directory = dir(run);
    String password = password(run);
    Json.write(directory.resolve(".env"), "GIS_LOCAL_PASSWORD=" + password + "\n");
    privateFile(directory.resolve(".env"));
    var services = Json.map();
    var volumes = Json.map();
    var databaseImage = runImage(run, "postgis");
    for (String db : List.of("edit", "pub")) {
      services.put(
          db + "-db",
          Json.map(
              "image",
              databaseImage.get("id"),
              "platform",
              "linux/" + databaseImage.get("architecture"),
              "environment",
              Json.map("POSTGRES_DB", db, "POSTGRES_PASSWORD", password),
              "ports",
              List.of("127.0.0.1::5432"),
              "volumes",
              List.of(db + "-data:/var/lib/postgresql"),
              "healthcheck",
              Json.map(
                  "test",
                  List.of("CMD-SHELL", "pg_isready -h 127.0.0.1 -U postgres -d " + db),
                  "interval",
                  "2s",
                  "timeout",
                  "5s",
                  "retries",
                  45),
              "labels",
              Json.map("gis.agent.run", run.get("id"))));
      if ("local".equals(databaseImage.get("source")))
        Json.obj(services.get(db + "-db")).put("pull_policy", "never");
      volumes.put(db + "-data", Json.map("labels", Json.map("gis.agent.run", run.get("id"))));
    }
    for (String profile : List.of("2.x", "3.1", "3.2")) {
      var selected = runImage(run, profile);
      String name = service(profile);
      services.put(
          name,
          Json.map(
              "image",
              selected.get("id"),
              "platform",
              "linux/" + selected.get("architecture"),
              "user",
              "0",
              "entrypoint",
              List.of("sleep", "infinity"),
              "init",
              true,
              "environment",
              Json.map("GRADLE_USER_HOME", "/cache"),
              "volumes",
              List.of(directory + ":" + directory, name + "-cache:/cache"),
              "labels",
              Json.map("gis.agent.run", run.get("id"))));
      if ("local".equals(selected.get("source")))
        Json.obj(services.get(name)).put("pull_policy", "never");
      volumes.put(name + "-cache", Json.map("labels", Json.map("gis.agent.run", run.get("id"))));
    }
    // JSON is valid YAML. Keep credential bytes in a private file.
    Json.atomic(
        directory.resolve("compose.yaml"), Json.map("services", services, "volumes", volumes));
    privateFile(directory.resolve("compose.yaml"));
  }

  void ensure(Map<String, Object> run, String profile) {
    if (run.containsKey("runtime_images")
        && !Json.digest(run.get("runtime_images")).equals(Json.digest(images())))
      throw new Problem(
          "runtime_mismatch",
          "Vorgang ist an frühere Images gebunden; nach Werkzeugupdate einen neuen Vorgang erstellen.");
    run.putIfAbsent("runtime_images", new LinkedHashMap<>(images()));
    configure(run);
    p.checked(
        compose(
            run,
            "up",
            "-d",
            "--wait",
            "--wait-timeout",
            "120",
            "edit-db",
            "pub-db",
            service(profile)),
        s.root,
        180);
    if ("true".equals(System.getenv("GIS_CONTAINER_MODE"))) {
      String network = project(run) + "_default", self = System.getenv("HOSTNAME");
      var result =
          p.run(List.of("docker", "network", "connect", network, self), s.root, s.timeout, null);
      if (result.exitCode() != 0 && !result.output().contains("already exists"))
        throw new Problem(
            "network_unavailable",
            "Agentcontainer kann nicht mit seiner lokalen Datenbank verbunden werden.");
    }
    Database.bootstrap(this, run);
  }

  String container(Map<String, Object> run, String service) {
    String id = p.checked(compose(run, "ps", "--all", "-q", service), s.root, s.timeout);
    if (id.isBlank()) throw new Problem("runtime_missing", "Container fehlt.", "service", service);
    String owner =
        p.checked(
            List.of(
                "docker", "inspect", "--format", "{{index .Config.Labels \"gis.agent.run\"}}", id),
            s.root,
            s.timeout);
    if (!run.get("id").equals(owner))
      throw new Problem("runtime_mismatch", "Container gehört zu anderem Vorgang.");
    return id;
  }

  String jdbc(Map<String, Object> run, String db, String application) {
    if (!Set.of("edit", "pub").contains(db))
      throw new Problem("database_unknown", "Nur lokale edit/pub-Datenbanken.");
    String id = container(run, db + "-db"), host = "127.0.0.1", port;
    if ("true".equals(System.getenv("GIS_CONTAINER_MODE"))) {
      host =
          p.checked(
              List.of(
                  "docker",
                  "inspect",
                  "--format",
                  "{{with index .NetworkSettings.Networks \""
                      + project(run)
                      + "_default\"}}{{.IPAddress}}{{end}}",
                  id),
              s.root,
              s.timeout);
      port = "5432";
    } else {
      var mappings =
          Json.read(
              p.checked(
                  List.of("docker", "inspect", "--format", "{{json .NetworkSettings.Ports}}", id),
                  s.root,
                  s.timeout));
      port = Json.required(Json.obj(Json.list(mappings.get("5432/tcp")).getFirst()), "HostPort");
    }
    return "jdbc:postgresql://"
        + host
        + ":"
        + port
        + "/"
        + db
        + "?ApplicationName="
        + application
        + "&connectTimeout=10&sslmode=disable&gssEncMode=disable";
  }

  Path properties(Map<String, Object> run, String attempt) {
    Path directory = dir(run).resolve("attempts").resolve(attempt),
        props = directory.resolve("gradle.properties");
    String pass = password(run),
        application = "gis_" + Json.required(run, "id").substring(0, 8) + "_" + attempt;
    var text = new StringBuilder("gretlEnvironment=development\n");
    for (String db : List.of("Edit", "Pub")) {
      text.append("dbUri")
          .append(db)
          .append("=jdbc:postgresql://")
          .append(db.toLowerCase())
          .append("-db/")
          .append(db.toLowerCase())
          .append("?ApplicationName=")
          .append(application)
          .append("\n");
      for (String suffix : List.of("", "Ddl")) {
        text.append("dbUser")
            .append(db)
            .append(suffix)
            .append("=")
            .append(suffix.isEmpty() ? "dmluser" : "ddluser")
            .append("\n");
        text.append("dbPwd").append(db).append(suffix).append("=").append(pass).append("\n");
      }
    }
    for (var entry : Json.obj(Json.obj(run.get("input")).get("credentials_env")).entrySet()) {
      String key = entry.getKey(), variable = entry.getValue().toString();
      if (!key.matches("[A-Za-z][A-Za-z0-9_.]*")
          || key.startsWith("db")
          || !variable.matches("[A-Za-z_][A-Za-z0-9_]*"))
        throw new Problem("credentials_invalid", "Ungültige Zugangsdaten-Zuordnung.");
      String value = System.getenv(variable);
      if (value == null || value.isBlank())
        throw new Problem(
            "credentials_missing", "Benötigte Umgebungsvariable fehlt.", "variable", variable);
      p.secret(value);
      String encoded =
          value
              .replace("\\", "\\\\")
              .replace("\n", "\\n")
              .replace("\r", "\\r")
              .replace("\t", "\\t")
              .replace(" ", "\\ ");
      p.secret(encoded);
      text.append(key).append("=").append(encoded).append("\n");
    }
    Json.write(props, text.toString());
    privateFile(props);
    return props;
  }

  Processes.Result execute(
      Map<String, Object> run,
      String profile,
      Path project,
      List<String> arguments,
      String attempt) {
    if (run.containsKey("active_execution") || run.containsKey("recovery"))
      throw new Problem("recovery_required", "Vorherige Ausführung zuerst aufklären.");
    ensure(run, profile);
    Path props = properties(run, attempt),
        init = dir(run).resolve("attempts").resolve(attempt).resolve("local.init.gradle");
    String propPath = groovy(props.toString());
    String models = groovy(dir(run).resolve("frozen-models").toString());
    Json.write(
        init,
        "import java.util.Properties\n"
            + "def local = new Properties(); new File('"
            + propPath
            + "').withReader('UTF-8') { local.load(it) }\n"
            + "gradle.beforeProject { pr -> local.each { k,v -> pr.ext.set(k.toString(),v.toString()) } }\n"
            + "gradle.projectsEvaluated { gradle.rootProject.allprojects { pr -> pr.tasks.configureEach { t ->"
            + " if (t.hasProperty('modeldir')) t.modeldir = '"
            + models
            + "';"
            + " } } }\n");
    var command =
        new ArrayList<>(
            List.of(
                "docker",
                "exec",
                "-w",
                project.toString(),
                container(run, service(profile)),
                "gretl",
                "--console=plain",
                "--rerun-tasks",
                "--init-script",
                init.toString()));
    command.addAll(arguments);
    Path log = dir(run).resolve("attempts").resolve(attempt).resolve("gretl.log");
    run.put(
        "active_execution",
        Json.map(
            "attempt",
            attempt,
            "profile",
            profile,
            "started_at",
            Json.now(),
            "log",
            log.toString()));
    Json.atomic(dir(run).resolve("state.json"), run);
    try {
      var result = p.run(command, s.root, s.timeout, log);
      run.remove("active_execution");
      return result;
    } catch (Problem error) {
      if (error.code.equals("command_timeout")) {
        var stopped = p.run(compose(run, "stop", "-t", "5", service(profile)), s.root, 30, null);
        if (stopped.exitCode() == 0) {
          Database.terminate(this, run);
          run.remove("active_execution");
          run.put(
              "last_interruption",
              Json.map("attempt", attempt, "target_may_have_changed", true, "log", log.toString()));
        } else
          run.put("recovery", Json.map("attempt", attempt, "reason", "runner_stop_unconfirmed"));
      } else run.put("recovery", Json.map("attempt", attempt, "reason", error.code));
      throw error;
    } finally {
      try {
        Files.deleteIfExists(props);
      } catch (Exception ignored) {
      }
      Json.atomic(dir(run).resolve("state.json"), run);
    }
  }

  static String groovy(String text) {
    return text.replace("\\", "\\\\").replace("'", "\\'");
  }

  void stop(Map<String, Object> run, boolean removeVolumes) {
    if (run.containsKey("active_execution"))
      throw new Problem("busy", "Aktive Ausführung zuerst aufklären.");
    var args = new ArrayList<>(List.of("down"));
    if (removeVolumes) args.add("--volumes");
    p.checked(compose(run, args.toArray(String[]::new)), s.root, 90);
  }
}
