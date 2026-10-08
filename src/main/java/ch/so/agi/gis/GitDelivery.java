package ch.so.agi.gis;

import java.nio.file.*;
import java.util.*;

final class GitDelivery {
  final Steps w;

  GitDelivery(Steps w) {
    this.w = w;
  }

  Map<String, Object> local(Map<String, Object> run) {
    var changes = Json.obj(run.get("changes")).values().stream().map(Json::obj).toList();
    var originals = new LinkedHashMap<Path, byte[]>();
    for (var change : changes) {
      String repo = Json.required(change, "repository"), relative = Json.required(change, "path");
      var selected = Json.obj(Json.obj(run.get("repositories")).get(repo));
      Path source = w.s.repoPath(repo);
      String head = w.p.checked(List.of("git", "rev-parse", "HEAD"), source, w.s.timeout);
      if (!head.equals(selected.get("commit")))
        throw new Problem(
            "checkout_conflict",
            "Zielcheckout steht auf anderem Commit; vorgesehenen Branch bewusst auschecken.",
            "repository",
            repo,
            "expected_commit",
            selected.get("commit"));
      Path file = Json.inside(source, relative);
      String current = Files.isRegularFile(file) ? Json.sha(file) : "ABSENT";
      if (!current.equals(change.get("source_original_sha")))
        throw new Problem(
            "checkout_conflict",
            "Lokale Datei seit Aufnahme verändert.",
            "repository",
            repo,
            "path",
            relative);
      try {
        originals.put(file, Files.exists(file) ? Files.readAllBytes(file) : null);
      } catch (Exception e) {
        throw new Problem("checkout_conflict", "Datei nicht lesbar.");
      }
    }
    var applied = new ArrayList<Path>();
    try {
      for (var change : changes) {
        Path file =
            Json.inside(
                w.s.repoPath(Json.required(change, "repository")), Json.required(change, "path"));
        if (Boolean.TRUE.equals(change.get("delete"))) Files.deleteIfExists(file);
        else atomicText(file, Json.required(change, "after"));
        applied.add(file);
      }
    } catch (Exception e) {
      for (Path file : applied)
        try {
          if (originals.get(file) == null) Files.deleteIfExists(file);
          else Files.write(file, originals.get(file));
        } catch (Exception ignored) {
        }
      throw new Problem(
          "apply_failed", "Übernahme fehlgeschlagen; bisher geschriebene Dateien zurückgesetzt.");
    }
    return Json.map(
        "valid",
        true,
        "status",
        "APPLIED",
        "files",
        applied.stream().map(Path::toString).toList(),
        "jenkins_bootstrap",
        w.bootstrapPlan(run));
  }

  static void atomicText(Path file, String text) throws Exception {
    Files.createDirectories(file.getParent());
    Path temporary = Files.createTempFile(file.getParent(), "gis-change-", ".tmp");
    try {
      Files.writeString(temporary, text);
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  void candidateFiles(Path checkout, List<Map<String, Object>> changes) {
    for (var change : changes) {
      Path file = Json.inside(checkout, Json.required(change, "path"));
      if (Boolean.TRUE.equals(change.get("delete"))) {
        try {
          Files.deleteIfExists(file);
        } catch (Exception e) {
          throw new Problem("git_failed", "Datei nicht löschbar.");
        }
      } else Json.write(file, Json.required(change, "after"));
      w.p.checked(
          List.of("git", "add", "--", Json.required(change, "path")), checkout, w.s.timeout);
    }
  }

  Map<String, Object> preview(Map<String, Object> run, Map<String, Object> spec) {
    var result = Json.map();
    for (String repo : List.of("models", "schema", "gretl")) {
      var changes =
          Json.obj(run.get("changes")).values().stream()
              .map(Json::obj)
              .filter(c -> repo.equals(c.get("repository")))
              .toList();
      if (changes.isEmpty()) continue;
      String base =
          Json.str(Json.obj(spec.get("base_refs")), repo, Json.str(w.s.repo(repo), "ref", "main"));
      if (base.startsWith("-") || base.isBlank())
        throw new Problem("invalid_ref", "Ungültiger Zielbranch.");
      Path checkout =
          w.dir(run)
              .resolve("pr-previews")
              .resolve(repo + "-" + Json.digest(changes).substring(0, 12));
      if (!Files.exists(checkout))
        w.p.checked(
            List.of(
                "git",
                "clone",
                "--no-hardlinks",
                "--",
                w.work(run, repo).toString(),
                checkout.toString()),
            w.s.root,
            w.s.timeout);
      candidateFiles(checkout, changes);
      w.p.checked(
          List.of("git", "fetch", Json.required(w.s.repo(repo), "url"), base),
          checkout,
          w.s.timeout);
      String baseCommit =
          w.p.checked(List.of("git", "rev-parse", "FETCH_HEAD^{commit}"), checkout, w.s.timeout);
      String tree = w.p.checked(List.of("git", "write-tree"), checkout, w.s.timeout);
      String diff =
          w.p.checked(
              List.of("git", "diff", "--cached", "--no-ext-diff", baseCommit, "--"),
              checkout,
              w.s.timeout);
      var files =
          w.p.checked(
              List.of("git", "diff", "--cached", "--name-only", baseCommit, "--"),
              checkout,
              w.s.timeout);
      if (diff.isBlank())
        throw new Problem(
            "changes_missing", "Kein Unterschied zum PR-Zielbranch.", "repository", repo);
      result.put(
          repo,
          Json.map(
              "base",
              base,
              "base_commit",
              baseCommit,
              "tree",
              tree,
              "files",
              files.lines().toList(),
              "diff",
              diff));
    }
    return result;
  }

  Map<String, Object> pullRequests(Map<String, Object> run, Map<String, Object> spec) {
    var previews = Json.obj(spec.get("_pr_preview"));
    if (previews.isEmpty())
      throw new Problem("pr_preview_required", "Vollständigen PR-Diff zuerst planen.");
    var results = new ArrayList<Object>();
    var records = Json.obj(run.computeIfAbsent("pull_requests", key -> Json.map()));
    for (String repo : List.of("models", "schema", "gretl")) {
      var changes =
          Json.obj(run.get("changes")).values().stream()
              .map(Json::obj)
              .filter(c -> repo.equals(c.get("repository")))
              .toList();
      if (changes.isEmpty()) continue;
      var approved = Json.obj(previews.get(repo));
      String base = Json.required(approved, "base");
      String revision = Json.digest(Json.map("changes", changes, "preview", approved)),
          key = repo + ":" + revision;
      var record = Json.obj(records.get(key));
      if (record.containsKey("url")) {
        results.add(record);
        continue;
      }
      String branch =
          "codex/gis-" + Json.required(run, "id").substring(0, 12) + "-" + revision.substring(0, 8);
      Path checkout = w.dir(run).resolve("delivery").resolve(repo + "-" + revision.substring(0, 8));
      if (!Files.exists(checkout)) {
        w.p.checked(
            List.of(
                "git",
                "clone",
                "--no-hardlinks",
                "--",
                w.work(run, repo).toString(),
                checkout.toString()),
            w.s.root,
            w.s.timeout);
        w.p.checked(
            List.of("git", "remote", "set-url", "origin", Json.required(w.s.repo(repo), "url")),
            checkout,
            w.s.timeout);
        w.p.checked(List.of("git", "switch", "-c", branch), checkout, w.s.timeout);
      }
      candidateFiles(checkout, changes);
      if (!Json.required(approved, "tree")
          .equals(w.p.checked(List.of("git", "write-tree"), checkout, w.s.timeout)))
        throw new Problem(
            "pr_tree_changed", "PR-Dateistand unterscheidet sich vom gezeigten Diff.");
      w.p.checked(List.of("git", "fetch", "origin", base), checkout, w.s.timeout);
      if (!Json.required(approved, "base_commit")
          .equals(
              w.p.checked(
                  List.of("git", "rev-parse", "FETCH_HEAD^{commit}"), checkout, w.s.timeout)))
        throw new Problem(
            "pr_base_changed", "Zielbranch verändert; neuen vollständigen Diff prüfen.");
      for (String setting : List.of("user.name", "user.email")) {
        String identity = Json.str(Json.obj(w.s.values.get("git")), setting.substring(5), "");
        if (identity.isBlank()) {
          var value =
              w.p.run(List.of("git", "config", "--get", setting), w.s.root, w.s.timeout, null);
          if (value.exitCode() == 0) identity = value.output().strip();
        }
        if (identity.isBlank())
          throw new Problem(
              "git_identity_missing", "Git-Identität konfigurieren.", "setting", setting);
        w.p.checked(List.of("git", "config", setting, identity), checkout, w.s.timeout);
      }
      var pending =
          w.p.run(List.of("git", "diff", "--cached", "--quiet"), checkout, w.s.timeout, null);
      if (pending.exitCode() == 1)
        w.p.checked(
            List.of(
                "git",
                "commit",
                "-m",
                Json.str(spec, "title", "GIS-Themenintegration") + " (" + repo + ")"),
            checkout,
            w.s.timeout);
      else if (pending.exitCode() != 0) throw new Problem("git_failed", "Git-Index nicht prüfbar.");
      record =
          Json.map(
              "repository",
              repo,
              "branch",
              branch,
              "base",
              base,
              "base_commit",
              approved.get("base_commit"),
              "head",
              w.p.checked(List.of("git", "rev-parse", "HEAD"), checkout, w.s.timeout),
              "revision",
              revision,
              "phase",
              "pushing");
      records.put(key, record);
      w.store.create(run);
      w.p.checked(List.of("git", "push", "-u", "origin", branch), checkout, w.s.timeout);
      record.put("phase", "pr_submitting");
      w.store.create(run);
      var existing =
          Json.list(
              parseList(
                  w.p.checked(
                      List.of(
                          "gh",
                          "pr",
                          "list",
                          "--repo",
                          Json.required(w.s.repo(repo), "url"),
                          "--head",
                          branch,
                          "--state",
                          "all",
                          "--json",
                          "url,baseRefName"),
                      checkout,
                      w.s.timeout)));
      String url;
      if (existing.size() > 1)
        throw new Problem("submission_unknown", "Mehrere PRs für den Vorgangsbranch.");
      if (existing.size() == 1) {
        if (!base.equals(Json.obj(existing.getFirst()).get("baseRefName")))
          throw new Problem("pr_base_changed", "Vorhandener PR hat anderen Zielbranch.");
        url = Json.required(Json.obj(existing.getFirst()), "url");
      } else {
        Path body = checkout.resolve(".git/gis-pr-body.md");
        Json.write(
            body,
            Json.str(spec, "description", "Geprüfte Legacy-GIS-Kandidaten.")
                + "\n\nVorgang: "
                + run.get("id")
                + "\n\nValidierung: "
                + Json.pretty(run.get("checks"))
                + "\n\nZusammengehörige Änderungen: "
                + Json.pretty(w.bootstrapPlan(run))
                + "\n");
        url =
            w.p
                .checked(
                    List.of(
                        "gh",
                        "pr",
                        "create",
                        "--repo",
                        Json.required(w.s.repo(repo), "url"),
                        "--base",
                        base,
                        "--head",
                        branch,
                        "--title",
                        Json.str(spec, "title", "GIS-Themenintegration") + " (" + repo + ")",
                        "--body-file",
                        body.toString()),
                    checkout,
                    w.s.timeout)
                .strip();
      }
      record.put("url", url);
      record.put("phase", "created");
      w.store.create(run);
      results.add(record);
    }
    return Json.map(
        "valid",
        true,
        "pull_requests",
        results,
        "merge",
        "human",
        "attach_artifacts",
        "Jeden erzeugten PR in Codex mit attach_artifact anhängen.");
  }

  static Object parseList(String text) {
    try {
      return Json.MAPPER.readValue(text, List.class);
    } catch (Exception e) {
      throw new Problem("git_response_invalid", "PR-Antwort ungültig.");
    }
  }
}
