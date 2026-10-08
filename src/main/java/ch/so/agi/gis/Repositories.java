package ch.so.agi.gis;

import java.nio.file.*;
import java.util.*;

final class Repositories {
  final Settings s;
  final Processes p;

  Repositories(Settings s, Processes p) {
    this.s = s;
    this.p = p;
  }

  static String canonicalUrl(String url) {
    return url.replaceFirst("^git@github.com:", "https://github.com/")
        .replaceFirst("\\.git$", "")
        .replaceFirst("/$", "");
  }

  Map<String, Object> ensure(String name) {
    Path path = s.repoPath(name);
    var configuration = s.repo(name);
    if (!Files.exists(path)) {
      try {
        Files.createDirectories(path.getParent());
      } catch (Exception e) {
        throw new Problem("repository_missing", "Elternverzeichnis fehlt.");
      }
      p.checked(
          List.of("git", "clone", "--", Json.required(configuration, "url"), path.toString()),
          s.root,
          Math.max(300, s.timeout));
    }
    String top = p.checked(List.of("git", "rev-parse", "--show-toplevel"), path, s.timeout);
    try {
      if (!Files.isSameFile(Path.of(top), path))
        throw new Problem(
            "repository_mismatch", "Pfad ist kein eigener Checkout.", "repository", name);
    } catch (java.io.IOException e) {
      throw new Problem("repository_mismatch", "Checkout-Pfad nicht prüfbar.", "repository", name);
    }
    String url = p.checked(List.of("git", "config", "--get", "remote.origin.url"), path, s.timeout);
    if (!canonicalUrl(url).equals(canonicalUrl(Json.required(configuration, "url"))))
      throw new Problem(
          "repository_mismatch",
          "Checkout hat einen anderen Ursprung.",
          "repository",
          name,
          "origin",
          url);
    return Json.map(
        "path",
        path.toString(),
        "origin",
        url,
        "branch",
        p.checked(List.of("git", "branch", "--show-current"), path, s.timeout),
        "working_tree_changes",
        p.checked(List.of("git", "status", "--short"), path, s.timeout));
  }

  Map<String, Object> snapshot(String name, String ref, Path output, List<String> workingPaths) {
    if (ref.isBlank() || ref.startsWith("-"))
      throw new Problem("invalid_ref", "Ungültige Git-Referenz.");
    Path source = s.repoPath(name);
    p.checked(
        List.of(
            "git",
            "clone",
            "--no-hardlinks",
            "--no-checkout",
            "--",
            source.toString(),
            output.toString()),
        s.root,
        s.timeout);
    // Resolve local branches, remote branches and commit IDs without changing the source checkout.
    String commit = null;
    for (String candidate : List.of(ref, "refs/remotes/origin/" + ref, "refs/heads/" + ref)) {
      var result =
          p.run(
              List.of("git", "rev-parse", "--verify", candidate + "^{commit}"),
              source,
              s.timeout,
              null);
      if (result.exitCode() == 0) {
        commit = result.output().strip();
        break;
      }
    }
    if (commit == null) {
      p.checked(
          List.of("git", "fetch", Json.required(s.repo(name), "url"), ref), output, s.timeout);
      commit = p.checked(List.of("git", "rev-parse", "FETCH_HEAD^{commit}"), output, s.timeout);
    } else {
      p.checked(List.of("git", "fetch", source.toString(), commit), output, s.timeout);
    }
    p.checked(List.of("git", "checkout", "--detach", commit), output, s.timeout);
    p.checked(
        List.of("git", "remote", "set-url", "origin", Json.required(s.repo(name), "url")),
        output,
        s.timeout);
    var included = Json.map();
    for (String relative : workingPaths) {
      businessPath(name, relative);
      Path from = Json.inside(source, relative), to = Json.inside(output, relative);
      if (!Files.isRegularFile(from))
        throw new Problem("input_missing", "Ausgewählte Arbeitsdatei fehlt.", "path", relative);
      Json.write(to, Json.contents(from));
      included.put(relative, Json.sha(from));
    }
    return Json.map(
        "ref",
        ref,
        "commit",
        commit,
        "source",
        source.toString(),
        "origin",
        Json.required(s.repo(name), "url"),
        "included_working_files",
        included);
  }

  static void businessPath(String repo, String relative) {
    Path path = Path.of(relative);
    if (path.isAbsolute()
        || path.normalize().startsWith("..")
        || relative.contains("\\")
        || relative.contains("/.git")
        || relative.startsWith("."))
      throw new Problem("unsafe_path", "Unzulässiger Repository-Pfad.");
    boolean allowed =
        switch (repo) {
          case "models" ->
              relative.startsWith("models/")
                  && (relative.endsWith(".ili") || relative.endsWith(".uml"));
          case "schema" -> relative.startsWith("topics/") && path.getNameCount() >= 4;
          case "gretl" ->
              path.getNameCount() >= 2
                  && !Set.of("docker-compose", "shared").contains(path.getName(0).toString());
          default -> false;
        };
    if (!allowed)
      throw new Problem(
          "unsafe_path",
          "Nur fachliche Modell-/Jobdateien sind Kandidaten.",
          "repository",
          repo,
          "path",
          relative);
  }

  static Map<String, String> hashes(Path root) {
    var hashes = new TreeMap<String, String>();
    if (!Files.exists(root)) return hashes;
    try (var paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .filter(
              p -> {
                for (Path segment : root.relativize(p))
                  if (Set.of(".git", ".gradle", "build").contains(segment.toString())) return false;
                return true;
              })
          .forEach(p -> hashes.put(root.relativize(p).toString(), Json.sha(p)));
      return hashes;
    } catch (Exception e) {
      throw new Problem("snapshot_failed", "Dateistand kann nicht erfasst werden.");
    }
  }
}
