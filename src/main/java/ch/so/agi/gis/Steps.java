package ch.so.agi.gis;

import java.nio.file.*;
import java.util.*;

final class Steps extends Workflow {
  Steps(Settings s) {
    super(s);
  }

  Steps(Settings s, Processes p) {
    super(s, p);
  }

  @Override
  Map<String, Object> plan(Map<String, Object> run, String action, Map<String, Object> spec) {
    if (run.containsKey("active_execution") || run.containsKey("recovery"))
      throw new Problem("recovery_required", "Ausführung zuerst aufklären.");
    var details = Json.map();
    if (!Set.of("cleanup", "accept_result").contains(action)) {
      for (Object value : Json.obj(run.get("changes")).values()) {
        var change = Json.obj(value);
        if ("models".equals(change.get("repository"))
            && !Boolean.TRUE.equals(change.get("delete"))) {
          var review =
              Json.obj(Json.obj(run.get("model_reviews")).get(change.get("path").toString()));
          if (!Objects.equals(change.get("after_sha"), review.get("sha256")))
            throw new Problem(
                "model_review_required",
                "Geänderten Modellstand zuerst mit INTERLIS-MCP reviewen.",
                "path",
                change.get("path"));
        }
      }
      details.put("repository_changes", run.get("changes"));
    }
    switch (action) {
      case "schema_create", "schema_configure", "schema_recreate", "schema_drop_previous" -> {
        prepareModels(run, Json.map());
        var keys =
            spec.containsKey("schema_keys")
                ? Json.strings(spec.get("schema_keys"))
                : spec.containsKey("schema_key")
                    ? List.of(Json.required(spec, "schema_key"))
                    : schemas(run).stream().map(v -> v.get("key").toString()).toList();
        if (keys.isEmpty()) throw new Problem("schema_unknown", "Schema-Jobs auswählen.");
        if (action.equals("schema_recreate") || action.equals("schema_drop_previous")) {
          if (keys.size() != 1) throw new Problem("schema_unknown", "Genau ein Löschziel angeben.");
          var selected = schema(run, keys.getFirst());
          String db = Json.required(selected, "database"),
              physical = Json.required(selected, "physical_name");
          if (action.equals("schema_drop_previous")) {
            int version = Json.number(selected, "schema_version", 0);
            if (version <= 1)
              throw new Problem("previous_missing", "Kein versionierter Vorgänger.");
            physical = Json.required(selected, "base_name") + "_v" + (version - 1);
          }
          var managed = Json.obj(Json.obj(run.get("managed_schemas")).get(db + ":" + physical));
          if (managed.isEmpty())
            throw new Problem(
                "schema_unmanaged", "Löschziel nicht durch diesen Vorgang verwaltet.");
          var snapshot = Database.inspect(runtime, run, db, physical);
          checkRoleOwnership(snapshot, managed);
          var roles = Json.strings(managed.get("role_names"));
          Database.guardedDrop(runtime, run, db, physical, roles, false);
          details.put(
              "delete_target",
              Json.map(
                  "database",
                  db,
                  "schema",
                  physical,
                  "roles",
                  roles,
                  "snapshot_hash",
                  Json.digest(snapshot),
                  "row_counts",
                  rowCounts(run, db, physical)));
        }
        details.put("schemas", keys.stream().map(k -> schema(run, k)).toList());
        spec = new LinkedHashMap<>(spec);
        spec.put("schema_keys", keys);
      }
      case "data_sql" -> {
        var selected = schema(run, Json.required(spec, "schema_key"));
        requireMatching(run, selected);
        Json.required(spec, "sql");
        details.put("schema", selected);
        details.put("sql", Json.required(spec, "sql"));
        details.put(
            "row_counts",
            rowCounts(
                run,
                Json.required(selected, "database"),
                Json.required(selected, "physical_name")));
      }
      case "data_xtf" -> {
        var selected = schema(run, Json.required(spec, "schema_key"));
        requireMatching(run, selected);
        prepareModels(run, Json.map());
        var artifact = artifact(run, Json.required(spec, "artifact_id"));
        var validation = xtf(run, selected, Path.of(Json.required(artifact, "path")), false);
        if (!Boolean.TRUE.equals(validation.get("valid")))
          throw new Problem("xtf_invalid", "XTF-Prüfung fehlgeschlagen.", "check", validation);
        details.put("schema", selected);
        details.put("artifact", artifact);
        details.put("validation", validation);
      }
      case "job_run" -> {
        prepareModels(run, Json.map());
        var validation = validateJob(run);
        if (!Boolean.TRUE.equals(validation.get("valid")))
          throw new Problem(
              "job_invalid", "GRETL-Konfiguration nicht ausführbar.", "check", validation);
        String mode = Json.str(spec, "mode", "verified");
        if (!Set.of("verified", "diagnosis").contains(mode))
          throw new Problem("invalid_arguments", "mode=verified|diagnosis.");
        if (mode.equals("verified")) {
          var check = Json.obj(Json.obj(run.get("checks")).get("job_test"));
          if (!Boolean.TRUE.equals(check.get("valid"))
              || !fingerprint(run).equals(check.get("fingerprint")))
            throw new Problem(
                "test_required",
                "Bestandener aktueller Fixture-Test erforderlich; Diagnosemodus ausdrücklich wählen.");
        }
        for (var selected : schemas(run)) requireMatching(run, selected);
        details.put("mode", mode);
        details.put("job", job(run));
        details.put("task_configuration", run.get("job_configuration"));
      }
      case "job_test" -> {
        Json.required(spec, "requirements");
        if (Json.list(spec.get("assertions")).isEmpty())
          throw new Problem("expectations_missing", "Fachliche Assertions angeben.");
        for (Object object : Json.list(spec.get("assertions"))) {
          var assertion = Json.obj(object);
          Json.required(assertion, "id");
          Json.required(assertion, "sql");
          if (!Set.of("edit", "pub").contains(Json.required(assertion, "database")))
            throw new Problem("database_unknown", "Assertion braucht edit oder pub.");
        }
        if (Json.bool(spec, "repeatable", false)
            && Json.list(spec.get("comparison_queries")).isEmpty())
          throw new Problem(
              "expectations_missing", "Vergleichsabfragen für Wiederholungsprüfung angeben.");
        prepareModels(run, Json.map());
        var validation = validateJob(run);
        if (!Boolean.TRUE.equals(validation.get("valid")))
          throw new Problem("job_invalid", "GRETL-Konfiguration ungültig.", "check", validation);
        details.put("expectations", spec);
        details.put("schemas", schemas(run));
        details.put("job", job(run));
      }
      case "changes" -> {
        if (Json.obj(run.get("changes")).isEmpty())
          throw new Problem("changes_missing", "Keine Kandidaten.");
        for (Object value : Json.obj(run.get("changes")).values()) {
          var change = Json.obj(value);
          if (change.get("repository").equals("models")
              && !Boolean.TRUE.equals(change.get("delete"))) {
            var review =
                Json.obj(Json.obj(run.get("model_reviews")).get(change.get("path").toString()));
            if (!change.get("after_sha").equals(review.get("sha256")))
              throw new Problem(
                  "model_review_required",
                  "Modellkandidat mit INTERLIS-MCP reviewen.",
                  "path",
                  change.get("path"));
          }
        }
        if (!schemas(run).isEmpty()) prepareModels(run, Json.map());
        if (!job(run).isEmpty() && !Boolean.TRUE.equals(validateJob(run).get("valid")))
          throw new Problem("job_invalid", "Kandidatenjob lässt sich nicht konfigurieren.");
        String delivery = Json.str(spec, "delivery", "local");
        if (!Set.of("local", "pr").contains(delivery))
          throw new Problem("invalid_arguments", "delivery=local|pr.");
        if (delivery.equals("pr")) {
          spec = new LinkedHashMap<>(spec);
          spec.put("_pr_preview", new GitDelivery(this).preview(run, spec));
          details.put("pull_request_diffs", spec.get("_pr_preview"));
        }
        details.put("delivery", delivery);
        details.put("changes", run.get("changes"));
        details.put("jenkins_bootstrap", bootstrapPlan(run));
      }
      case "cleanup" -> {
        details.put("project", runtime.project(run));
        details.put("remove_data", Json.bool(spec, "remove_data", false));
      }
      case "accept_result" -> {
        if (!run.containsKey("last_result"))
          throw new Problem("result_missing", "Noch kein Ergebnis vorhanden.");
        details.put("result", run.get("last_result"));
      }
      default -> throw new Problem("action_unknown", "Unbekannte Etappe.", "action", action);
    }
    String id = UUID.randomUUID().toString().replace("-", "");
    var plan =
        Json.map(
            "id",
            id,
            "action",
            action,
            "spec",
            spec,
            "details",
            details,
            "input_fingerprint",
            fingerprint(run),
            "created_at",
            Json.now());
    plan.put("fingerprint", Json.digest(plan));
    plan.put(
        "report",
        Reports.write(
                dir(run).resolve("reports/plan-" + id + ".html"), "Nächste Etappe: " + action, plan)
            .toString());
    Json.obj(run.get("plans")).put(id, plan);
    return Json.map(
        "plan",
        plan,
        "awaiting_human",
        true,
        "next",
        "Konkreten Plan zeigen; menschliche Antwort vor approve abwarten.");
  }

  @Override
  Map<String, Object> approve(Map<String, Object> run, Map<String, Object> args) {
    var plan = storedPlan(run, Json.required(args, "plan_id"));
    if (plan.containsKey("consumed_at"))
      throw new Problem("plan_consumed", "Plan bereits ausgeführt.");
    if (!Json.required(args, "fingerprint").equals(plan.get("fingerprint"))
        || !fingerprint(run).equals(plan.get("input_fingerprint")))
      throw new Problem("approval_stale", "Prüfstand verändert; neuen Plan zeigen.");
    String statement = Json.required(args, "human_statement").strip();
    plan.put(
        "approval",
        Json.map(
            "time",
            Json.now(),
            "human_statement",
            statement,
            "fingerprint",
            plan.get("fingerprint")));
    Store.event(
        run,
        "human_approval",
        Json.map("plan_id", plan.get("id"), "action", plan.get("action"), "statement", statement));
    return Json.map(
        "status",
        "APPROVED",
        "plan_id",
        plan.get("id"),
        "next",
        "execute führt genau diese Etappe aus.");
  }

  Map<String, Object> storedPlan(Map<String, Object> run, String id) {
    var plan = Json.obj(Json.obj(run.get("plans")).get(id));
    if (plan.isEmpty()) throw new Problem("plan_unknown", "Plan nicht vorhanden.");
    var signed =
        Json.map(
            "id",
            plan.get("id"),
            "action",
            plan.get("action"),
            "spec",
            plan.get("spec"),
            "details",
            plan.get("details"),
            "input_fingerprint",
            plan.get("input_fingerprint"),
            "created_at",
            plan.get("created_at"));
    if (!Json.digest(signed).equals(plan.get("fingerprint")))
      throw new Problem("plan_changed", "Gespeicherter Plan verändert.");
    return plan;
  }

  @Override
  Map<String, Object> execute(Map<String, Object> run, String id) {
    var plan = storedPlan(run, id);
    if (!plan.containsKey("approval"))
      throw new Problem("approval_required", "Menschliche Freigabe fehlt.");
    if (plan.containsKey("consumed_at"))
      throw new Problem("plan_consumed", "Plan bereits verwendet.");
    if (!fingerprint(run).equals(plan.get("input_fingerprint")))
      throw new Problem("approval_stale", "Eingaben verändert; neuen Plan zeigen.");
    if (run.containsKey("active_execution") || run.containsKey("recovery"))
      throw new Problem("recovery_required", "Vorherige Ausführung aufklären.");
    String action = Json.required(plan, "action");
    var spec = Json.obj(plan.get("spec"));
    plan.put("consumed_at", Json.now());
    Store.event(run, "execute_intent", Json.map("plan_id", id, "action", action));
    store.create(run);
    Map<String, Object> result;
    try {
      result =
          switch (action) {
            case "schema_create", "schema_configure" -> {
              var results = new ArrayList<Object>();
              for (String key : Json.strings(spec.get("schema_keys")))
                results.add(executeSchema(run, schema(run, key), action.equals("schema_create")));
              yield Json.map("schemas", results, "valid", true);
            }
            case "schema_recreate", "schema_drop_previous" -> {
              var target = Json.obj(Json.obj(plan.get("details")).get("delete_target"));
              String db = Json.required(target, "database"),
                  physical = Json.required(target, "schema");
              var current = Database.inspect(runtime, run, db, physical);
              if (!Json.digest(current).equals(target.get("snapshot_hash"))
                  || !Json.digest(rowCounts(run, db, physical))
                      .equals(Json.digest(target.get("row_counts"))))
                throw new Problem(
                    "approval_stale", "Schema oder Daten nach Löschplanung verändert.");
              Database.guardedDrop(
                  runtime, run, db, physical, Json.strings(target.get("roles")), true);
              Json.obj(run.get("managed_schemas")).remove(db + ":" + physical);
              yield action.equals("schema_recreate")
                  ? executeSchema(
                      run, schema(run, Json.strings(spec.get("schema_keys")).getFirst()), true)
                  : Json.map(
                      "status", "DROPPED", "database", db, "schema", physical, "valid", true);
            }
            case "data_sql" -> {
              var selected = schema(run, Json.required(spec, "schema_key"));
              requireMatching(run, selected);
              String sql =
                  Json.required(spec, "sql")
                      .replace(
                          "$" + "{dbSchema}",
                          Database.ident(Json.required(selected, "physical_name")));
              int count = Database.dml(runtime, run, Json.required(selected, "database"), sql);
              yield Json.map(
                  "status",
                  "LOADED",
                  "update_count",
                  count,
                  "row_counts",
                  rowCounts(
                      run,
                      Json.required(selected, "database"),
                      Json.required(selected, "physical_name")),
                  "valid",
                  true);
            }
            case "data_xtf" -> {
              var selected = schema(run, Json.required(spec, "schema_key"));
              requireMatching(run, selected);
              yield xtf(
                  run,
                  selected,
                  Path.of(Json.required(artifact(run, Json.required(spec, "artifact_id")), "path")),
                  true);
            }
            case "job_run" -> runJob(run);
            case "job_test" -> testJob(run, spec);
            case "changes" ->
                Json.str(spec, "delivery", "local").equals("pr")
                    ? new GitDelivery(this).pullRequests(run, spec)
                    : new GitDelivery(this).local(run);
            case "cleanup" -> {
              runtime.stop(run, Json.bool(spec, "remove_data", false));
              yield Json.map(
                  "status", "STOPPED", "removed_data", Json.bool(spec, "remove_data", false));
            }
            case "accept_result" -> {
              run.put(
                  "accepted_result",
                  Json.map("plan_id", id, "time", Json.now(), "result", run.get("last_result")));
              yield Json.map("status", "ACCEPTED");
            }
            default -> throw new Problem("action_unknown", "Unbekannte Etappe.");
          };
      run.put(
          "state", Boolean.FALSE.equals(result.get("valid")) ? "step_failed" : "awaiting_human");
    } catch (Problem error) {
      result =
          Json.map(
              "valid",
              false,
              "problem",
              error.result(),
              "target_may_have_changed",
              !Set.of("approval_stale", "approval_required").contains(error.code));
      run.put("state", "step_failed");
    }
    result.put("action", action);
    result.put("plan_id", id);
    result.put("awaiting_human", true);
    result.put(
        "report",
        Reports.write(
                dir(run).resolve("reports/result-" + id + ".html"), "Ergebnis: " + action, result)
            .toString());
    run.put("last_result", result);
    plan.put("result", result);
    Store.event(run, "execute_result", Json.map("plan_id", id, "result", result));
    return result;
  }

  void requireMatching(Map<String, Object> run, Map<String, Object> selected) {
    var inspection = inspect(run, Json.required(selected, "key"));
    if (!"MATCHING".equals(inspection.get("status")))
      throw new Problem(
          "schema_not_matching",
          "Passendes verwaltetes Schema erforderlich.",
          "inspection",
          inspection);
    var managed =
        Json.obj(
            Json.obj(run.get("managed_schemas"))
                .get(selected.get("database") + ":" + selected.get("physical_name")));
    if (run.containsKey("models")
        && !Objects.equals(
            managed.get("model_hash"),
            Json.digest(Json.obj(Json.obj(run.get("models")).get("file_hashes")))))
      throw new Problem(
          "schema_models_changed", "Schema wurde mit einem anderen Modellstand erstellt.");
  }

  Map<String, Object> rowCounts(Map<String, Object> run, String db, String schema) {
    try (var c = Database.connect(runtime, run, db, "postgres")) {
      var result = Json.map();
      for (var table :
          Database.query(
              c,
              "SELECT tablename FROM pg_tables WHERE schemaname=? ORDER BY tablename",
              List.of(schema),
              10000)) {
        String name = table.get("tablename").toString();
        if (name.startsWith("t_ili2db_")) continue;
        result.put(
            name,
            Database.query(
                    c,
                    "SELECT count(*) AS count FROM "
                        + Database.ident(schema)
                        + "."
                        + Database.ident(name),
                    List.of(),
                    1)
                .getFirst()
                .get("count"));
      }
      return result;
    } catch (java.sql.SQLException e) {
      throw new Problem(
          "inspection_failed", "Zeilenzahlen nicht lesbar.", "sql_state", e.getSQLState());
    }
  }

  static void checkRoleOwnership(Map<String, Object> snapshot, Map<String, Object> managed) {
    if (!Boolean.TRUE.equals(snapshot.get("exists")))
      throw new Problem("schema_missing", "Löschziel fehlt.");
    if (!Json.digest(snapshot.get("roles"))
        .equals(Json.digest(Json.obj(managed.get("snapshot")).get("roles"))))
      throw new Problem("roles_changed", "Verwaltete Rollen wurden verändert; Löschung blockiert.");
  }

  Map<String, Object> executeSchema(
      Map<String, Object> run, Map<String, Object> selected, boolean create) {
    String profile = Json.required(selected, "runtime"),
        db = Json.required(selected, "database"),
        physical = Json.required(selected, "physical_name"),
        key = Json.required(selected, "key");
    runtime.ensure(run, profile);
    var before = Database.inspect(runtime, run, db, physical);
    if (create && Boolean.TRUE.equals(before.get("exists"))) {
      if ("MATCHING".equals(inspect(run, key).get("status")))
        return Json.map("status", "ALREADY_PRESENT", "schema_key", key, "valid", true);
      throw new Problem(
          "schema_exists", "Schema existiert; Neuaufbau ausdrücklich planen.", "schema", physical);
    }
    if (!create) requireMatching(run, selected);
    var roleNames =
        List.of(
            physical + selected.get("role_suffix") + "_read",
            physical + selected.get("role_suffix") + "_write");
    if (create)
      try (var c = Database.connect(runtime, run, db, "postgres")) {
        for (String role : roleNames)
          if (!Database.query(c, "SELECT 1 FROM pg_roles WHERE rolname=?", List.of(role), 1)
              .isEmpty())
            throw new Problem(
                "roles_exist", "Gleichnamige Rollen bereits vorhanden.", "role", role);
      } catch (java.sql.SQLException e) {
        throw new Problem("inspection_failed", "Rollen nicht prüfbar.");
      }
    Database.localRoles(runtime, run, db, Legacy.recipients(selected));
    String attempt = "schema-" + UUID.randomUUID().toString().substring(0, 8);
    var tasks =
        new ArrayList<String>(
            List.of(
                "-PtopicName=" + selected.get("topic"),
                "-PschemaDirName=" + selected.get("schema_dir"),
                "-PdbName=" + db));
    if (create) tasks.add("createSchema");
    tasks.add("configureSchema");
    if (Json.obj(selected.get("sql")).containsKey("grants.sql")) tasks.add("grantPrivileges");
    var result =
        runtime.execute(run, profile, work(run, "schema").resolve("shared/schema"), tasks, attempt);
    if (result.exitCode() != 0)
      throw new Problem(
          "schema_failed",
          "Schema-Job fehlgeschlagen; Zustand kann unvollständig sein.",
          "log",
          dir(run).resolve("attempts/" + attempt + "/gretl.log").toString());
    Database.localDml(runtime, run, db, roleNames.get(1));
    var snapshot = Database.inspect(runtime, run, db, physical);
    if (!Boolean.TRUE.equals(snapshot.get("exists")))
      throw new Problem("schema_failed", "Schema nach erfolgreichem Prozess nicht vorhanden.");
    var record =
        Json.map(
            "schema",
            selected,
            "snapshot",
            snapshot,
            "snapshot_hash",
            Json.digest(snapshot),
            "configuration_hash",
            Json.digest(selected),
            "role_names",
            roleNames,
            "model_hash",
            Json.digest(Json.obj(Json.obj(run.get("models")).get("file_hashes"))),
            "attempt",
            attempt,
            "created_at",
            Json.now());
    Json.obj(run.get("schemas")).put(key, record);
    Json.obj(run.computeIfAbsent("managed_schemas", k -> Json.map()))
        .put(db + ":" + physical, record);
    return Json.map(
        "status",
        create ? "CREATED" : "CONFIGURED",
        "schema_key",
        key,
        "physical_name",
        physical,
        "database",
        db,
        "snapshot_hash",
        record.get("snapshot_hash"),
        "valid",
        true);
  }

  Map<String, Object> artifact(Map<String, Object> run, String id) {
    return Json.list(run.get("artifacts")).stream()
        .map(Json::obj)
        .filter(a -> id.equals(a.get("id")))
        .findFirst()
        .orElseThrow(() -> new Problem("artifact_missing", "Artefakt fehlt."));
  }

  Map<String, Object> xtf(
      Map<String, Object> run, Map<String, Object> selected, Path file, boolean importData) {
    String
        attempt =
            (importData ? "import-" : "validate-") + UUID.randomUUID().toString().substring(0, 8),
        profile = Json.required(selected, "runtime");
    Path project = dir(run).resolve("attempts").resolve(attempt).resolve("project");
    String fileExpression =
        (profile.equals("3.2") ? "files" : "file")
            + "('"
            + DockerRuntime.groovy(file.toString())
            + "')";
    String db = Json.required(selected, "database").equals("edit") ? "Edit" : "Pub";
    String script =
        "import ch.so.agi.gretl.tasks.*\napply plugin: 'ch.so.agi.gretl'\ndefaultTasks 'validateData'\n"
            + "task validateData(type: IliValidator) { dataFiles=files('"
            + DockerRuntime.groovy(file.toString())
            + "') }\n";
    if (importData)
      script +=
          "task importData(type: Ili2pgImport) { dependsOn 'validateData'; database=[dbUri"
              + db
              + ",dbUser"
              + db
              + ",dbPwd"
              + db
              + "]; models='"
              + String.join(";", Json.strings(selected.get("models")))
              + "'; dbschema='"
              + selected.get("physical_name")
              + "'; dataFile="
              + fileExpression
              + "; disableValidation=false }\ndefaultTasks 'importData'\n";
    Json.write(project.resolve("build.gradle"), script);
    var result = runtime.execute(run, profile, project, List.of(), attempt);
    return Json.map(
        "valid",
        result.exitCode() == 0,
        "status",
        result.exitCode() == 0 ? (importData ? "IMPORTED" : "VALID") : "INVALID",
        "log",
        project.getParent().resolve("gretl.log").toString(),
        "target_may_have_changed",
        importData && result.exitCode() != 0);
  }

  Map<String, Object> runJob(Map<String, Object> run) {
    var job = job(run);
    String attempt = "job-" + UUID.randomUUID().toString().substring(0, 8);
    var arguments = new ArrayList<>(parameters(run));
    arguments.addAll(Json.strings(Json.obj(run.get("input")).get("tasks")));
    var result =
        runtime.execute(
            run,
            Json.required(job, "runtime"),
            work(run, "gretl").resolve(Json.required(job, "name")),
            arguments,
            attempt);
    var artifacts = new ArrayList<Object>();
    Path build = work(run, "gretl").resolve(Json.required(job, "name")).resolve("build");
    if (Files.isDirectory(build))
      try (var paths = Files.walk(build)) {
        for (Path file : paths.filter(Files::isRegularFile).toList())
          if (Files.size(file) <= 10_000_000) artifacts.add(attach(run, file, "job_output"));
      } catch (Exception e) {
        throw new Problem("artifact_failed", "Jobausgaben nicht archivierbar.");
      }
    var contract = Json.obj(Json.obj(Json.obj(run.get("checks")).get("job_test")).get("contract"));
    var localContract =
        Json.map(
            "assertions",
            Json.list(contract.get("assertions")).stream()
                .map(Json::obj)
                .filter(a -> "local".equals(a.get("scope")))
                .toList());
    var localChecks = result.exitCode() == 0 ? assertions(run, localContract) : new ArrayList<>();
    boolean localPassed =
        localChecks.stream().allMatch(a -> Boolean.TRUE.equals(Json.obj(a).get("passed")));
    return Json.map(
        "valid",
        result.exitCode() == 0 && localPassed,
        "gradle_success",
        result.exitCode() == 0,
        "local_assertions",
        localChecks,
        "business_acceptance",
        "NOT_CONFIRMED",
        "exit_code",
        result.exitCode(),
        "log",
        dir(run).resolve("attempts/" + attempt + "/gretl.log").toString(),
        "artifacts",
        artifacts,
        "target_may_have_changed",
        true);
  }

  Map<String, Object> testJob(Map<String, Object> run, Map<String, Object> spec) {
    String id = UUID.randomUUID().toString().replace("-", "");
    var child =
        Json.map(
            "format_version",
            1,
            "id",
            id,
            "parent_run",
            run.get("id"),
            "test_only",
            true,
            "input",
            run.get("input"),
            "repositories",
            run.get("repositories"),
            "changes",
            Json.map(),
            "artifacts",
            new ArrayList<>(),
            "schemas",
            Json.map(),
            "plans",
            Json.map(),
            "models",
            run.get("models"));
    try {
      for (String repo : List.of("gretl", "schema", "models")) {
        p.checked(
            List.of(
                "git",
                "clone",
                "--no-hardlinks",
                "--",
                work(run, repo).toString(),
                work(child, repo).toString()),
            s.root,
            s.timeout);
        for (Object object : Json.obj(run.get("changes")).values()) {
          var change = Json.obj(object);
          if (!repo.equals(change.get("repository"))) continue;
          Path target = Json.inside(work(child, repo), Json.required(change, "path"));
          if (Boolean.TRUE.equals(change.get("delete"))) Files.deleteIfExists(target);
          else Json.write(target, Json.required(change, "after"));
        }
      }
      Files.createDirectories(dir(child).resolve("frozen-models"));
      for (var file : Json.obj(Json.obj(run.get("models")).get("file_hashes")).entrySet())
        Files.copy(
            dir(run).resolve("frozen-models").resolve(file.getKey()),
            dir(child).resolve("frozen-models").resolve(file.getKey()));
      store.create(child);
      for (var selected : schemas(child)) executeSchema(child, selected, true);
      for (Object object : Json.list(spec.get("fixtures"))) {
        var fixture = Json.obj(object);
        Database.dml(
            runtime, child, Json.required(fixture, "database"), Json.required(fixture, "sql"));
      }
      var first = runJob(child);
      var checks = assertions(child, spec);
      boolean valid =
          Boolean.TRUE.equals(first.get("valid"))
              && checks.stream().allMatch(a -> Boolean.TRUE.equals(Json.obj(a).get("passed")));
      boolean repeatable = Json.bool(spec, "repeatable", false);
      Object comparisonBefore = repeatable ? comparisons(child, spec) : null, second = null;
      if (valid && repeatable) {
        second = runJob(child);
        var comparisonAfter = comparisons(child, spec);
        valid =
            Boolean.TRUE.equals(Json.obj(second).get("valid"))
                && comparisonBefore.equals(comparisonAfter);
        var secondChecks = assertions(child, spec);
        valid &=
            secondChecks.stream().allMatch(a -> Boolean.TRUE.equals(Json.obj(a).get("passed")));
        checks.addAll(secondChecks);
      }
      var check =
          Json.map(
              "valid",
              valid,
              "fixture_run",
              id,
              "requirements",
              spec.get("requirements"),
              "contract",
              spec,
              "assertions",
              checks,
              "first_run",
              first,
              "second_run",
              second,
              "repeatability_checked",
              repeatable,
              "business_acceptance",
              valid ? "CONFIRMED_EXPECTATIONS_PASSED" : "FAILED");
      check.put("fingerprint", fingerprint(run));
      Json.obj(run.computeIfAbsent("checks", k -> Json.map())).put("job_test", check);
      return check;
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(
          "test_failed",
          "Isolierter Test nicht abschliessbar.",
          "exception",
          e.getClass().getSimpleName());
    } finally {
      store.create(child);
      if (Files.isRegularFile(dir(child).resolve("compose.yaml")))
        try {
          runtime.stop(child, true);
        } catch (Problem failure) {
          Json.obj(run.computeIfAbsent("checks", k -> Json.map()))
              .put(
                  "job_test",
                  Json.map("valid", false, "cleanup_failed", failure.result(), "fixture_run", id));
          throw failure;
        }
    }
  }

  ArrayList<Object> assertions(Map<String, Object> run, Map<String, Object> spec) {
    var results = new ArrayList<Object>();
    for (Object object : Json.list(spec.get("assertions"))) {
      var assertion = Json.obj(object);
      try {
        var violations =
            Database.read(
                runtime,
                run,
                Json.required(assertion, "database"),
                Json.required(assertion, "sql"),
                10);
        results.add(
            Json.map(
                "id",
                assertion.get("id"),
                "passed",
                violations.isEmpty(),
                "violations",
                violations));
      } catch (Problem e) {
        results.add(Json.map("id", assertion.get("id"), "passed", false, "problem", e.result()));
      }
    }
    return results;
  }

  List<Object> comparisons(Map<String, Object> run, Map<String, Object> spec) {
    return Json.list(spec.get("comparison_queries")).stream()
        .map(
            object -> {
              var query = Json.obj(object);
              var rows =
                  Database.read(
                      runtime,
                      run,
                      Json.required(query, "database"),
                      Json.required(query, "sql"),
                      1000);
              if (rows.size() == 1000)
                throw new Problem(
                    "comparison_limit",
                    "Vergleich begrenzen oder aggregierte vollständige Abfrage verwenden.");
              return (Object) rows.stream().map(Json::text).sorted().toList();
            })
        .toList();
  }

  Map<String, Object> bootstrapPlan(Map<String, Object> run) {
    var instructions = new ArrayList<Object>();
    for (String repository : List.of("gretl", "schema")) {
      var paths =
          Json.obj(run.get("changes")).values().stream()
              .map(Json::obj)
              .filter(c -> repository.equals(c.get("repository")))
              .map(c -> Json.required(c, "path"))
              .toList();
      if (paths.isEmpty()) continue;
      instructions.add(
          Json.map(
              "repository",
              repository,
              "files",
              paths,
              "generator",
              repository.equals("gretl") ? "gretl-job-generator" : "gretl-job-generator-schema",
              "registration",
              "Jobdefinition muss im Generator-Branch vorhanden sein; bei vorgezogener GRETL-Registrierung Initialisierungs-PR mit leerem build.gradle und nötigem Jenkinsfile bereitstellen.",
              "branch_parameters",
              "BRANCH; Schema-Jobs zusätzlich GRADLE_TASKS",
              "execution",
              "Registrierung und Test-Jenkins-Ausführung durch Menschen."));
    }
    return Json.map(
        "steps",
        instructions,
        "ready_for_local_execution",
        true,
        "jenkins_registration_performed",
        false);
  }

  @Override
  Map<String, Object> reconcile(Map<String, Object> run, Map<String, Object> args) {
    if (!run.containsKey("active_execution") && !run.containsKey("recovery"))
      return Json.map("status", "NO_PENDING_EXECUTION", "last_result", run.get("last_result"));
    if (!Json.bool(args, "runner_stopped", false))
      return Json.map(
          "status",
          "RECOVERY_REQUIRED",
          "active_execution",
          run.get("active_execution"),
          "recovery",
          run.get("recovery"));
    for (String profile : List.of("2.x", "3.1", "3.2"))
      p.checked(runtime.compose(run, "stop", "-t", "5", runtime.service(profile)), s.root, 30);
    Database.terminate(runtime, run);
    if (run.containsKey("recovery")
        && "database_termination_unconfirmed".equals(Json.obj(run.get("recovery")).get("reason")))
      throw new Problem("recovery_required", "DB-Terminierung unbestätigt.");
    Object previous = run.remove("active_execution");
    run.remove("recovery");
    Store.event(
        run,
        "reconcile",
        Json.map("previous_execution", previous, "target_may_have_changed", true));
    return Json.map(
        "status",
        "STOP_CONFIRMED",
        "target_may_have_changed",
        true,
        "next",
        "Logs und Daten prüfen; für Wiederholung neuen Plan erstellen.",
        "awaiting_human",
        true);
  }
}
