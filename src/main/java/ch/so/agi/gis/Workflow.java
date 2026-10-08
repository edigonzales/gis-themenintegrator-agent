package ch.so.agi.gis;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

abstract class Workflow implements AutoCloseable {
  final Settings s;
  final Processes p;
  final Store store;
  final DockerRuntime runtime;
  final Interlis interlis;

  Workflow(Settings s) {
    this(s, new Processes());
  }

  Workflow(Settings s, Processes p) {
    this.s = s;
    this.p = p;
    store = new Store(s);
    runtime = new DockerRuntime(s, p);
    interlis = new Interlis(s, runtime);
  }

  Map<String, Object> call(String operation, Map<String, Object> args) {
    Operations.validate(operation, args);
    if (operation.equals("start")) return start(args);
    if (operation.equals("interlis_tools")) return interlis.tools();
    if (operation.equals("task_reference"))
      return new Knowledge(s, p).reference(Json.required(args, "task"));
    if (operation.equals("find_job_examples") && !args.containsKey("run_id"))
      return new Knowledge(s, p)
          .examples(
              s.repoPath("gretl"),
              Json.required(args, "task"),
              Json.number(args, "limit", 5),
              p.checked(List.of("git", "rev-parse", "HEAD"), s.repoPath("gretl"), s.timeout));
    String id = Json.required(args, "run_id");
    return store.with(
        id,
        run -> {
          Map<String, Object> result =
              switch (operation) {
                case "context" -> context(run);
                case "configure" -> {
                  for (String key :
                      List.of("schema_jobs", "gretl_job", "tasks", "parameters", "credentials_env"))
                    if (args.containsKey(key)) Json.obj(run.get("input")).put(key, args.get(key));
                  Store.event(run, "configure", args);
                  yield context(run);
                }
                case "status" -> status(run);
                case "stage_change" ->
                    stage(
                        run,
                        Json.required(args, "repository"),
                        Json.required(args, "relative_path"),
                        args.containsKey("source_path")
                            ? Json.contents(Path.of(Json.required(args, "source_path")))
                            : Json.str(args, "content", ""),
                        Json.bool(args, "delete", false));
                case "prepare_schema" -> prepareSchema(run, args);
                case "prepare_job" -> prepareJob(run, args);
                case "models_prepare" -> prepareModels(run, args);
                case "model" -> model(run, args);
                case "schema_inspect" -> inspect(run, Json.required(args, "schema_key"));
                case "db_query" ->
                    Json.map(
                        "rows",
                        Database.read(
                            runtime,
                            run,
                            Json.required(args, "database"),
                            Json.required(args, "sql"),
                            Json.number(args, "limit", 100)));
                case "job_validate" -> validateJob(run);
                case "find_job_examples" ->
                    new Knowledge(s, p)
                        .examples(
                            work(run, "gretl"),
                            Json.required(args, "task"),
                            Json.number(args, "limit", 5),
                            Json.required(
                                Json.obj(Json.obj(run.get("repositories")).get("gretl")),
                                "commit"));
                case "attach" ->
                    attach(run, Path.of(Json.required(args, "path")), Json.required(args, "role"));
                case "plan" -> plan(run, Json.required(args, "action"), Json.obj(args.get("spec")));
                case "approve" -> approve(run, args);
                case "execute" -> execute(run, Json.required(args, "plan_id"));
                case "reconcile" -> reconcile(run, args);
                default -> throw new Problem("operation_unknown", "Unbekannte Operation.");
              };
          result.putIfAbsent("run_id", id);
          return result;
        });
  }

  Path dir(Map<String, Object> run) {
    return store.dir(Json.required(run, "id"));
  }

  Path work(Map<String, Object> run, String repository) {
    s.repo(repository);
    String name =
        switch (repository) {
          case "gretl" -> "gretljobs";
          case "schema" -> "schema-jobs";
          default -> "sogis-interlis-repository";
        };
    Path legacy = dir(run).resolve("work").resolve(repository),
        canonical = dir(run).resolve("work").resolve(name);
    return Files.isDirectory(legacy) && !Files.exists(canonical) ? legacy : canonical;
  }

  Map<String, Object> start(Map<String, Object> args) {
    String id = UUID.randomUUID().toString().replace("-", "");
    var run =
        Json.map(
            "format_version",
            1,
            "id",
            id,
            "created_at",
            Json.now(),
            "workflow",
            Json.str(args, "workflow", "diagnosis"),
            "input",
            new LinkedHashMap<>(args),
            "repositories",
            Json.map(),
            "changes",
            Json.map(),
            "artifacts",
            new ArrayList<>(),
            "plans",
            Json.map(),
            "schemas",
            Json.map(),
            "events",
            new ArrayList<>());
    store.create(run);
    try {
      var repos = new Repositories(s, p);
      var refs = Json.obj(args.get("refs"));
      var included = Json.obj(args.get("working_files"));
      for (String name : List.of("gretl", "schema", "models")) {
        repos.ensure(name);
        var snapshot =
            repos.snapshot(
                name,
                Json.str(refs, name, Json.str(s.repo(name), "ref", "main")),
                work(run, name),
                Json.strings(included.get(name)));
        Json.obj(run.get("repositories")).put(name, snapshot);
        for (String relative : Json.strings(included.get(name)))
          recordChange(run, name, relative, false);
      }
      run.put("state", "prepared");
      Store.event(run, "start", Json.map("repositories", run.get("repositories")));
      store.create(run);
      return Json.map(
          "run_id",
          id,
          "state",
          "prepared",
          "awaiting_human",
          true,
          "next",
          "context; Branchzuordnung und nächste Etappe gemeinsam prüfen");
    } catch (Problem e) {
      run.put("state", "preparation_failed");
      run.put("error", e.result());
      store.create(run);
      throw e;
    }
  }

  List<Map<String, Object>> schemas(Map<String, Object> run) {
    return Legacy.schemas(s, work(run, "schema"), Json.obj(run.get("input")).get("schema_jobs"));
  }

  Map<String, Object> schema(Map<String, Object> run, String key) {
    return schemas(run).stream()
        .filter(v -> key.equals(v.get("key")))
        .findFirst()
        .orElseThrow(
            () -> new Problem("schema_unknown", "schema_key nicht im Vorgang.", "schema_key", key));
  }

  Map<String, Object> job(Map<String, Object> run) {
    return Legacy.job(s, work(run, "gretl"), Json.str(Json.obj(run.get("input")), "gretl_job", ""));
  }

  Map<String, Object> context(Map<String, Object> run) {
    var result =
        Json.map(
            "repositories",
            run.get("repositories"),
            "schemas",
            schemas(run),
            "gretl_job",
            job(run),
            "changes",
            run.get("changes"),
            "model_primary_files",
            run.get("model_primary_files"),
            "awaiting_human",
            true);
    if (!job(run).isEmpty()) {
      var candidates = new ArrayList<String>();
      var selected =
          schemas(run).stream()
              .map(v -> v.get("physical_name").toString())
              .collect(java.util.stream.Collectors.toSet());
      String text = Json.text(job(run).get("files"));
      try (var paths = Files.walk(work(run, "schema").resolve("topics"))) {
        for (Path file :
            paths.filter(f -> f.getFileName().toString().equals("schema.properties")).toList()) {
          String selector =
              file.getParent().getParent().getFileName() + "/" + file.getParent().getFileName();
          for (var candidate : Legacy.schemas(s, work(run, "schema"), List.of(selector))) {
            String physical = Json.required(candidate, "physical_name");
            if (!selected.contains(physical) && text.contains(physical)) candidates.add(selector);
          }
        }
      } catch (Exception ignored) {
      }
      result.put("additional_schema_candidates", candidates.stream().distinct().sorted().toList());
    }
    result.put(
        "report",
        Reports.write(dir(run).resolve("reports/context.html"), "Legacy-Vorgang prüfen", result)
            .toString());
    return result;
  }

  Map<String, Object> status(Map<String, Object> run) {
    return Json.map(
        "state",
        run.get("state"),
        "repositories",
        run.get("repositories"),
        "changes",
        run.get("changes"),
        "plans",
        run.get("plans"),
        "schemas",
        run.get("schemas"),
        "artifacts",
        run.get("artifacts"),
        "checks",
        run.get("checks"),
        "last_result",
        run.get("last_result"),
        "active_execution",
        run.get("active_execution"),
        "recovery",
        run.get("recovery"),
        "events",
        run.get("events"),
        "awaiting_human",
        true);
  }

  String gitBlob(Map<String, Object> run, String repository, String relative) {
    String commit =
        Json.required(Json.obj(Json.obj(run.get("repositories")).get(repository)), "commit");
    var result =
        p.run(
            List.of("git", "show", commit + ":" + relative),
            work(run, repository),
            s.timeout,
            null);
    return result.exitCode() == 0 ? result.output() : null;
  }

  void recordChange(Map<String, Object> run, String repository, String relative, boolean deleted) {
    String before = gitBlob(run, repository, relative);
    Path path = Json.inside(work(run, repository), relative);
    var old = Json.obj(Json.obj(run.get("changes")).get(repository + ":" + relative));
    var snapshot = Json.obj(Json.obj(run.get("repositories")).get(repository));
    String baselineHash =
        before == null
            ? "ABSENT"
            : Json.hash(before.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String sourceHash =
        Json.str(
            old,
            "source_original_sha",
            Json.str(Json.obj(snapshot.get("included_working_files")), relative, baselineHash));
    Json.obj(run.get("changes"))
        .put(
            repository + ":" + relative,
            Json.map(
                "repository",
                repository,
                "path",
                relative,
                "before",
                before,
                "before_sha",
                before == null
                    ? "ABSENT"
                    : Json.hash(before.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "after",
                deleted ? null : Json.contents(path),
                "after_sha",
                deleted ? "ABSENT" : Json.sha(path),
                "source_original_sha",
                sourceHash,
                "delete",
                deleted));
  }

  Map<String, Object> stage(
      Map<String, Object> run, String repository, String relative, String content, boolean delete) {
    Repositories.businessPath(repository, relative);
    if (content.length() > 5_000_000) throw new Problem("input_too_large", "Kandidat zu gross.");
    Path path = Json.inside(work(run, repository), relative);
    if (delete) {
      try {
        Files.deleteIfExists(path);
      } catch (Exception e) {
        throw new Problem("file_write_failed", "Datei nicht löschbar.");
      }
    } else Json.write(path, content);
    recordChange(run, repository, relative, delete);
    Store.event(run, "stage_change", Json.map("repository", repository, "path", relative));
    return Json.map(
        "status",
        "STAGED",
        "change",
        Json.obj(run.get("changes")).get(repository + ":" + relative),
        "awaiting_human",
        true);
  }

  Map<String, Object> prepareSchema(Map<String, Object> run, Map<String, Object> args) {
    String topic = Json.required(args, "topic"), folder = Json.required(args, "schema_dir");
    if (!topic.matches("[a-z][a-z0-9_]*") || !folder.matches("schema[a-z0-9_]*"))
      throw new Problem("schema_invalid", "Ungültiger Themen-/Schemaordnername.");
    var properties = new LinkedHashMap<String, Object>(Json.obj(args.get("properties")));
    Json.required(properties, "models");
    Json.required(properties, "databases");
    // Actual xy_template defaults; caller-supplied values win.
    var defaults =
        Legacy.properties(
            work(run, "schema").resolve("topics/xy_template/schema/schema.properties"));
    for (var entry : defaults.entrySet())
      if (!Set.of("models", "schemaComment").contains(entry.getKey()))
        properties.putIfAbsent(entry.getKey(), entry.getValue());
    properties.putIfAbsent("schemaComment", "");
    String prefix = "topics/" + topic + "/" + folder + "/";
    stage(run, "schema", prefix + "schema.properties", Legacy.writeProperties(properties), false);
    stage(
        run,
        "schema",
        prefix + "job.properties",
        Legacy.writeProperties(
            Json.map(
                "nodeLabel",
                "gretl-3.2",
                "authorization.permissions",
                Json.required(args, "authorization_permissions"))),
        false);
    if (args.containsKey("grants_sql"))
      stage(
          run,
          "schema",
          "topics/" + topic + "/" + folder.replaceFirst("schema", "privileges") + "/grants.sql",
          Json.required(args, "grants_sql"),
          false);
    var selectors =
        Json.list(
            Json.obj(run.get("input")).computeIfAbsent("schema_jobs", k -> new ArrayList<>()));
    String selector = topic + "/" + folder;
    if (!selectors.contains(selector)) selectors.add(selector);
    return Json.map("status", "STAGED", "schemas", schemas(run), "awaiting_human", true);
  }

  Map<String, Object> prepareJob(Map<String, Object> run, Map<String, Object> args) {
    String name = Json.required(args, "name");
    if (!name.matches("[a-z][a-z0-9_]*")) throw new Problem("job_invalid", "Ungültiger Jobname.");
    var files = Json.obj(args.get("files"));
    if (!files.containsKey("build.gradle") && !files.containsKey("settings.gradle"))
      throw new Problem("job_invalid", "build.gradle oder settings.gradle erforderlich.");
    var props = new LinkedHashMap<>(Json.obj(args.get("job_properties")));
    Json.required(props, "authorization.permissions");
    props.putIfAbsent("nodeLabel", "gretl-3.2");
    props.putIfAbsent("logRotator.numToKeep", 30);
    for (var entry : files.entrySet())
      stage(run, "gretl", name + "/" + entry.getKey(), entry.getValue().toString(), false);
    stage(run, "gretl", name + "/job.properties", Legacy.writeProperties(props), false);
    Json.obj(run.get("input")).put("gretl_job", name);
    return Json.map("status", "STAGED", "job", job(run), "awaiting_human", true);
  }

  Map<String, Object> prepareModels(Map<String, Object> run, Map<String, Object> args) {
    var names = new TreeSet<String>();
    for (var schema : schemas(run)) names.addAll(Json.strings(schema.get("models")));
    names.addAll(Json.strings(job(run).get("models")));
    if (run.containsKey("job_configuration"))
      for (Object task : Json.list(Json.obj(run.get("job_configuration")).get("tasks"))) {
        String models = Json.str(Json.obj(task), "models", "");
        names.addAll(Arrays.asList(models.split(";")));
      }
    names.addAll(Json.strings(args.get("models")));
    names.remove("");
    if (args.containsKey("primary_files"))
      run.put("model_primary_files", args.get("primary_files"));
    var evidence =
        new Models(s)
            .prepare(
                work(run, "models"),
                work(run, "schema"),
                dir(run),
                new ArrayList<>(names),
                Json.obj(run.get("model_primary_files")));
    run.put("models", evidence);
    return evidence;
  }

  Map<String, Object> model(Map<String, Object> run, Map<String, Object> args) {
    String tool = Json.required(args, "tool");
    var arguments = Json.obj(args.get("arguments"));
    var result = interlis.call(tool, arguments);
    Path artifact = dir(run).resolve("model-reviews").resolve(UUID.randomUUID() + ".json");
    Json.atomic(artifact, result);
    var review = result.containsKey("afterReview") ? Json.obj(result.get("afterReview")) : result;
    boolean compiler =
        Boolean.TRUE.equals(review.get("compilerValid"))
            || "PASSED"
                .equals(Json.obj(Json.obj(result.get("evidence")).get("compiler")).get("status"));
    boolean valid = compiler && Boolean.TRUE.equals(review.get("validForAutomatedRules"));
    if (args.containsKey("relative_path")) {
      if (!Set.of("authorIliModel", "applyIliModelChanges", "reviewIliModel", "reviewIliChange")
          .contains(tool))
        throw new Problem("model_tool_invalid", "Werkzeug erzeugt keinen Modellkandidaten.");
      String text =
          Json.str(
              result,
              "updatedModelText",
              Json.str(result, "candidateModelText", Json.str(arguments, "modelText", "")));
      if (!valid
          || text.isBlank()
          || result.containsKey("complete") && !Boolean.TRUE.equals(result.get("complete")))
        throw new Problem(
            "model_review_failed",
            "Modellkandidat hat keine vollständigen technischen Nachweise.",
            "response",
            result);
      stage(run, "models", Json.required(args, "relative_path"), text, false);
      var reviews = Json.obj(run.computeIfAbsent("model_reviews", k -> Json.map()));
      reviews.put(
          Json.required(args, "relative_path"),
          Json.map(
              "sha256",
              Json.hash(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              "review",
              result,
              "artifact",
              artifact.toString()));
    }
    return Json.map("response", result, "artifact", artifact.toString(), "awaiting_human", true);
  }

  Map<String, Object> attach(Map<String, Object> run, Path source, String role) {
    if (!Files.isRegularFile(source)) throw new Problem("input_missing", "Originaldatei fehlt.");
    String hash = Json.sha(source), name = source.getFileName().toString();
    Path target = dir(run).resolve("artifacts").resolve(hash).resolve(name);
    try {
      Files.createDirectories(target.getParent());
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      throw new Problem("input_missing", "Datei nicht kopierbar.");
    }
    var artifact =
        Json.map(
            "id",
            hash,
            "sha256",
            hash,
            "original_path",
            source.toAbsolutePath().toString(),
            "path",
            target.toString(),
            "name",
            name,
            "role",
            role);
    Json.list(run.get("artifacts")).add(artifact);
    return artifact;
  }

  String fingerprint(Map<String, Object> run) {
    var files = Json.map();
    for (String name : List.of("gretl", "schema", "models"))
      files.put(name, Repositories.hashes(work(run, name)));
    for (Object object : Json.list(run.get("artifacts"))) {
      var artifact = Json.obj(object);
      if (!artifact.get("sha256").equals(Json.sha(Path.of(Json.required(artifact, "path")))))
        throw new Problem("artifact_changed", "Originalartefakt verändert.");
    }
    if (run.containsKey("models")) Models.verify(dir(run), Json.obj(run.get("models")));
    return Json.digest(
        Json.map(
            "input",
            run.get("input"),
            "repositories",
            run.get("repositories"),
            "files",
            files,
            "models",
            run.get("models"),
            "artifacts",
            Json.list(run.get("artifacts")).stream()
                .map(Json::obj)
                .filter(a -> !"job_output".equals(a.get("role")))
                .toList(),
            "settings",
            s.values,
            "images",
            runtime.images(),
            "model_reviews",
            run.get("model_reviews")));
  }

  Map<String, Object> inspect(Map<String, Object> run, String key) {
    var schema = schema(run, key);
    String db = Json.required(schema, "database"), name = Json.required(schema, "physical_name");
    var available = p.run(runtime.compose(run, "ps", "-q", db + "-db"), s.root, s.timeout, null);
    if (available.exitCode() != 0 || available.output().isBlank())
      return Json.map("status", "NOT_STARTED", "schema", schema);
    var actual = Database.inspect(runtime, run, db, name);
    var recorded = Json.obj(Json.obj(run.get("schemas")).get(key));
    String status =
        !Boolean.TRUE.equals(actual.get("exists"))
            ? "MISSING"
            : recorded.isEmpty()
                ? "UNMANAGED"
                : Objects.equals(recorded.get("snapshot_hash"), Json.digest(actual))
                        && Objects.equals(recorded.get("configuration_hash"), Json.digest(schema))
                    ? "MATCHING"
                    : "DRIFTED";
    Path report =
        dir(run).resolve("reports/inspection-" + Json.digest(key).substring(0, 12) + ".json");
    Json.atomic(report, actual);
    return Json.map(
        "status",
        status,
        "schema",
        schema,
        "snapshot_hash",
        Json.digest(actual),
        "snapshot",
        actual,
        "report",
        report.toString());
  }

  Map<String, Object> validateJob(Map<String, Object> run) {
    var job = job(run);
    if (job.isEmpty()) throw new Problem("job_unknown", "GRETL-Job angeben.");
    String attempt = "configure-" + UUID.randomUUID().toString().substring(0, 8);
    Path context = dir(run).resolve("attempts").resolve(attempt).resolve("task-context.json");
    Path adapter = context.getParent().resolve("context.init.gradle");
    Json.write(
        adapter,
        "import groovy.json.JsonOutput\n"
            + "def emit = { items -> def unwrap = { v -> if(v instanceof org.gradle.api.provider.Provider) v=v.orNull; if(v instanceof org.gradle.api.file.FileCollection) return v.files.collect { it.absolutePath }; return v==null?null:v.toString() };"
            + " def tasks=items.collect { t -> def item=[path:t.path,type:t.class.simpleName]; ['models','dbschema','modeldir','dataFile','dataFiles','sqlFiles'].each { key -> if(t.hasProperty(key)) item[key]=unwrap(t.property(key)) }; return item };"
            + " new File('"
            + DockerRuntime.groovy(context.toString())
            + "').text=JsonOutput.toJson([tasks:tasks,defaultTasks:gradle.rootProject.defaultTasks]) }\n"
            + "gradle.projectsEvaluated { emit(gradle.rootProject.allprojects.collectMany { it.tasks.toList() }) }\n"
            + "gradle.taskGraph.whenReady { graph -> emit(graph.allTasks) }\n");
    var arguments = new ArrayList<>(List.of("--init-script", adapter.toString(), "--dry-run"));
    arguments.addAll(parameters(run));
    arguments.addAll(Json.strings(Json.obj(run.get("input")).get("tasks")));
    var result =
        runtime.execute(
            run,
            Json.required(job, "runtime"),
            work(run, "gretl").resolve(Json.required(job, "name")),
            arguments,
            attempt);
    var check =
        Json.map(
            "valid",
            result.exitCode() == 0 && Files.isRegularFile(context),
            "log",
            context.getParent().resolve("gretl.log").toString());
    if (Boolean.TRUE.equals(check.get("valid"))) {
      var configuration = Json.read(context);
      var executed = new LinkedHashSet<String>();
      var matcher =
          Pattern.compile("(?m)^(:[A-Za-z0-9_:.-]+)\\s+SKIPPED\\s*$").matcher(result.output());
      while (matcher.find()) executed.add(matcher.group(1));
      if (!executed.isEmpty())
        configuration.put(
            "tasks",
            Json.list(configuration.get("tasks")).stream()
                .map(Json::obj)
                .filter(t -> executed.contains(t.get("path")))
                .toList());
      run.put("job_configuration", configuration);
      check.put("configuration", configuration);
      if (Json.list(configuration.get("tasks")).isEmpty()) check.put("valid", false);
    }
    if (Boolean.TRUE.equals(check.get("valid"))) check.put("fingerprint", fingerprint(run));
    Json.obj(run.computeIfAbsent("checks", k -> Json.map())).put("job_configuration", check);
    return check;
  }

  List<String> parameters(Map<String, Object> run) {
    var arguments = new ArrayList<String>();
    for (var entry : Json.obj(Json.obj(run.get("input")).get("parameters")).entrySet()) {
      if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9_.]*")
          || entry.getKey().startsWith("db")
          || entry.getKey().matches("(?i).*(pwd|password|secret|token|credential).*")
          || Set.of("gretlEnvironment", "modeldir", "ilivalidatorModeldir")
              .contains(entry.getKey()))
        throw new Problem(
            "parameter_invalid",
            "Technische Verbindungen/Modellstände werden vom Runner gesetzt.",
            "parameter",
            entry.getKey());
      arguments.add("-P" + entry.getKey() + "=" + entry.getValue());
    }
    return arguments;
  }

  abstract Map<String, Object> plan(
      Map<String, Object> run, String action, Map<String, Object> spec);

  abstract Map<String, Object> approve(Map<String, Object> run, Map<String, Object> args);

  abstract Map<String, Object> execute(Map<String, Object> run, String id);

  abstract Map<String, Object> reconcile(Map<String, Object> run, Map<String, Object> args);

  @Override
  public void close() {
    interlis.close();
  }
}
