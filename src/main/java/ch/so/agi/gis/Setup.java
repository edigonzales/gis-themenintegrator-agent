package ch.so.agi.gis;

import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

final class Setup {
  final Settings s;
  final Processes p;
  final DockerRuntime runtime;

  Setup(Settings s) {
    this.s = s;
    p = new Processes();
    runtime = new DockerRuntime(s, p);
  }

  Map<String, Object> init(boolean updateTools, boolean updateDocs, boolean skipSmoke) {
    return init(updateTools, null, updateDocs, skipSmoke);
  }

  Map<String, Object> init(
      boolean updateTools, String updateImage, boolean updateDocs, boolean skipSmoke) {
    try {
      Files.createDirectories(s.state);
      try (var channel =
          FileChannel.open(
              s.state.resolve("init.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
        var lock = channel.tryLock();
        if (lock == null) throw new Problem("init_busy", "Initialisierung läuft bereits.");
        try (lock) {
          Path journal = s.state.resolve("init.json");
          var report =
              Files.isRegularFile(journal) ? Json.read(journal) : Json.map("steps", Json.map());
          try {
            p.checked(List.of("git", "--version"), s.root, s.timeout);
            p.checked(
                List.of("docker", "version", "--format", "{{.Server.Version}}"), s.root, s.timeout);
            p.checked(List.of("docker", "compose", "version"), s.root, s.timeout);
            if (!Files.exists(s.file))
              Json.write(s.file, Json.contents(s.file.getParent().resolve("local.example.toml")));
            step(
                report,
                journal,
                "prerequisites",
                Json.map(
                    "java",
                    Runtime.version().toString(),
                    "git",
                    true,
                    "docker",
                    true,
                    "compose",
                    true));
            var repos = Json.map();
            for (String repo : List.of("gretl", "schema", "models"))
              repos.put(repo, new Repositories(s, p).ensure(repo));
            step(report, journal, "repositories", repos);
            var selected = runtime.pin(updateTools, updateImage);
            step(report, journal, "images", selected);
            var reference = new Knowledge(s, p).refresh(updateDocs);
            step(
                report,
                journal,
                "reference",
                Json.map(
                    "url",
                    reference.get("url"),
                    "sha256",
                    reference.get("sha256"),
                    "task_count",
                    Json.obj(reference.get("tasks")).size()));
            harness();
            step(
                report,
                journal,
                "harness",
                Json.map(
                    "codex",
                    s.root.resolve(".codex/config.toml").toString(),
                    "opencode",
                    s.root.resolve("opencode.json").toString()));
            try (var w = new Steps(s)) {
              var tools = w.interlis.tools();
              var names =
                  Json.list(tools.get("tools")).stream()
                      .map(Json::obj)
                      .map(t -> t.get("name").toString())
                      .toList();
              if (!names.containsAll(
                  List.of("authorIliModel", "applyIliModelChanges", "reviewIliModel")))
                throw new Problem(
                    "interlis_tools_missing", "INTERLIS-MCP hat nicht die benötigten Werkzeuge.");
              step(report, journal, "interlis", Json.map("valid", true, "tools", names));
              String smokeFingerprint =
                  Json.digest(
                      Json.map(
                          "agent_jar",
                          Files.isRegularFile(s.root.resolve("build/libs/gis-integrator.jar"))
                              ? Json.sha(s.root.resolve("build/libs/gis-integrator.jar"))
                              : "development",
                          "images",
                          selected,
                          "schema_shared",
                          Repositories.hashes(s.repoPath("schema").resolve("shared"))));
              var old = Json.obj(Json.obj(report.get("steps")).get("smoke"));
              if (!skipSmoke
                  && (!Boolean.TRUE.equals(old.get("valid"))
                      || !smokeFingerprint.equals(old.get("fingerprint")))) {
                var smoke = Smoke.run(w);
                smoke.put("fingerprint", smokeFingerprint);
                smoke.put(
                    "agent_jar",
                    Files.isRegularFile(s.root.resolve("build/libs/gis-integrator.jar"))
                        ? Json.sha(s.root.resolve("build/libs/gis-integrator.jar"))
                        : "development");
                step(report, journal, "smoke", smoke);
              } else if (skipSmoke)
                step(
                    report,
                    journal,
                    "smoke",
                    Json.map("valid", false, "skipped", true, "reason", "--skip-smoke"));
            }
            report.put(
                "ready",
                Boolean.TRUE.equals(
                    Json.obj(Json.obj(report.get("steps")).get("smoke")).get("valid")));
            report.put("updated_at", Json.now());
            report.remove("problem");
            Json.atomic(journal, report);
            return Json.map(
                "ready",
                report.get("ready"),
                "report",
                journal.toString(),
                "steps",
                report.get("steps"),
                "next",
                "Codex/OpenCode in diesem Repo starten und GIS-Themenintegrator verwenden.");
          } catch (Problem error) {
            report.put("ready", false);
            report.put("problem", error.result());
            Json.atomic(journal, report);
            throw new Problem(
                "init_incomplete",
                "Init kann nach Korrektur wiederholt werden.",
                "cause",
                error.result(),
                "report",
                journal.toString());
          }
        }
      }
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(
          "init_failed",
          "Initialisierung fehlgeschlagen.",
          "exception",
          e.getClass().getSimpleName());
    }
  }

  void step(Map<String, Object> report, Path journal, String name, Object result) {
    Json.obj(report.get("steps")).put(name, result);
    report.put("last_completed_step", name);
    Json.atomic(journal, report);
  }

  void harness() {
    if (!"true".equals(System.getenv("GIS_CONTAINER_MODE"))) {
      Json.write(
          s.root.resolve(".gis-agent/tools/java.path"),
          Path.of(System.getProperty("java.home"), "bin/java").toString());
    }
    String launcher = s.root.resolve("bin/gis-agent").toString();
    String toml =
        "[mcp_servers.gis_integrator]\ncommand = "
            + Json.text(launcher)
            + "\nargs = [\"--config\", "
            + Json.text(s.file.toString())
            + ", \"serve\"]\nstartup_timeout_sec = 60\ntool_timeout_sec = 1200\n";
    writeOwned(s.root.resolve(".codex/config.toml"), toml);
    writeOwned(
        s.root.resolve("opencode.json"),
        Json.pretty(
            Json.map(
                "$schema",
                "https://opencode.ai/config.json",
                "instructions",
                List.of("AGENTS.md"),
                "mcp",
                Json.map(
                    "gis_integrator",
                    Json.map(
                        "type",
                        "local",
                        "command",
                        List.of(launcher, "--config", s.file.toString(), "serve"),
                        "enabled",
                        true,
                        "timeout",
                        1200000)))));
  }

  void writeOwned(Path path, String content) {
    Path record = s.state.resolve("harness-owned.json");
    var owned = Files.isRegularFile(record) ? Json.read(record) : Json.map();
    if (Files.isRegularFile(path)
        && !Json.sha(path).equals(owned.get(path.toString()))
        && !Json.contents(path).equals(content))
      throw new Problem(
          "harness_conflict",
          "Vorhandene Harness-Konfiguration verändert; bewussten Abgleich vornehmen.",
          "path",
          path.toString());
    Json.write(path, content);
    owned.put(path.toString(), Json.sha(path));
    Json.atomic(record, owned);
  }

  Map<String, Object> doctor() {
    var checks = Json.map("java_25", Runtime.version().feature() == 25);
    for (String tool : List.of("git", "docker"))
      try {
        checks.put(
            tool,
            p.checked(
                tool.equals("docker")
                    ? List.of(tool, "version", "--format", "{{.Server.Version}}")
                    : List.of(tool, "--version"),
                s.root,
                s.timeout));
      } catch (Problem e) {
        checks.put(tool, e.result());
      }
    for (String repo : List.of("gretl", "schema", "models")) {
      try {
        Path path = s.repoPath(repo);
        String origin =
            p.checked(List.of("git", "config", "--get", "remote.origin.url"), path, s.timeout);
        checks.put(
            repo,
            Json.map(
                "valid",
                Repositories.canonicalUrl(origin)
                    .equals(Repositories.canonicalUrl(Json.required(s.repo(repo), "url"))),
                "path",
                path.toString()));
      } catch (Problem e) {
        checks.put(repo, e.result());
      }
    }
    try {
      checks.put("compose", p.checked(List.of("docker", "compose", "version"), s.root, s.timeout));
    } catch (Problem error) {
      checks.put("compose", error.result());
    }
    try {
      for (String key : s.images().keySet()) checks.put("image:" + key, runtime.image(key));
    } catch (Problem e) {
      checks.put("images", e.result());
    }
    try (var w = new Steps(s)) {
      checks.put("interlis", w.interlis.tools());
    } catch (Problem e) {
      checks.put("interlis", e.result());
    }
    checks.put(
        "init",
        Files.isRegularFile(s.state.resolve("init.json"))
            ? Json.read(s.state.resolve("init.json"))
            : Json.map("ready", false));
    var init = Json.obj(checks.get("init"));
    var smoke = Json.obj(Json.obj(init.get("steps")).get("smoke"));
    boolean ready =
        Boolean.TRUE.equals(checks.get("java_25"))
            && Boolean.TRUE.equals(init.get("ready"))
            && !checks.containsKey("images");
    for (Object value : checks.values())
      if (value instanceof Map<?, ?> map
          && (map.containsKey("error") || Boolean.FALSE.equals(map.get("valid")))) ready = false;
    if (Files.isRegularFile(s.root.resolve("build/libs/gis-integrator.jar"))
        && !Json.sha(s.root.resolve("build/libs/gis-integrator.jar"))
            .equals(smoke.get("agent_jar"))) ready = false;
    checks.put("ready", ready);
    return checks;
  }
}
