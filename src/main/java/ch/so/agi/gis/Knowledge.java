package ch.so.agi.gis;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

final class Knowledge {
  static final String URL = "https://gretl.app/reference.html";
  final Settings s;
  final Processes p;

  Knowledge(Settings s, Processes p) {
    this.s = s;
    this.p = p;
  }

  Path cache() {
    return s.state.resolve("knowledge/reference.json");
  }

  Map<String, Object> refresh(boolean update) {
    if (Files.isRegularFile(cache()) && !update) {
      var cached = Json.read(cache());
      verify(cached);
      return cached;
    }
    try {
      var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(60)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new Problem(
            "reference_unavailable",
            "GRETL-Referenz nicht erreichbar.",
            "http_status",
            response.statusCode());
      var tasks = extract(response.body());
      if (!tasks.containsKey("Db2Db") || !tasks.containsKey("Ili2pgImportSchema"))
        throw new Problem("reference_invalid", "GRETL-Taskabschnitte fehlen.");
      var cached =
          Json.map(
              "url",
              URL,
              "retrieved_at",
              Json.now(),
              "sha256",
              Json.hash(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              "tasks",
              tasks,
              "tasks_hash",
              Json.digest(tasks));
      Json.write(s.state.resolve("knowledge/reference.html"), response.body());
      Json.atomic(cache(), cached);
      return cached;
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(
          "reference_unavailable",
          "GRETL-Referenz kann nicht geladen werden.",
          "exception",
          e.getClass().getSimpleName());
    }
  }

  static Map<String, Object> extract(String html) {
    var doc = Jsoup.parse(html);
    var tasks = Json.map();
    for (Element heading : doc.select("h3")) {
      String name = heading.text().replaceAll("\\s*\\(.*\\)$", "").strip();
      var fragments = new ArrayList<String>();
      for (Element element = heading.nextElementSibling();
          element != null && !Set.of("h2", "h3").contains(element.tagName());
          element = element.nextElementSibling())
        fragments.add(element.tagName().equals("pre") ? element.wholeText() : element.text());
      tasks.put(
          name, Json.map("url", URL + "#" + heading.id(), "text", String.join("\n\n", fragments)));
    }
    return tasks;
  }

  void verify(Map<String, Object> cached) {
    if (!Json.digest(cached.get("tasks")).equals(cached.get("tasks_hash")))
      throw new Problem("reference_invalid", "Referenzcache ist verändert.");
  }

  Map<String, Object> reference(String task) {
    var cached = refresh(false);
    var entry = Json.obj(cached.get("tasks")).get(task);
    if (entry == null)
      throw new Problem("task_unknown", "Task nicht in der GRETL-Referenz.", "task", task);
    return Json.map(
        "task",
        task,
        "reference",
        entry,
        "retrieved_at",
        cached.get("retrieved_at"),
        "documentation_sha256",
        cached.get("sha256"),
        "applies_to",
        "3.x; tatsächliche Laufzeit zusätzlich prüfen");
  }

  Map<String, Object> examples(Path repo, String task, int limit, String commit) {
    if (!task.matches("[A-Za-z][A-Za-z0-9]*"))
      throw new Problem("invalid_arguments", "Taskname erforderlich.");
    var found = new ArrayList<Object>();
    try (var paths = Files.walk(repo)) {
      for (Path file :
          paths
              .filter(f -> f.toString().endsWith(".gradle") && Files.isRegularFile(f))
              .sorted()
              .toList()) {
        if (repo.relativize(file).toString().startsWith(".git")
            || file.toString().contains("/build/")) continue;
        String text = Json.contents(file);
        if (!text.matches("(?s).*\\b" + task + "\\b.*")) continue;
        found.add(
            Json.map(
                "path",
                repo.relativize(file).toString(),
                "commit",
                commit,
                "sha256",
                Json.sha(file),
                "source",
                text.substring(0, Math.min(20000, text.length()))));
        if (found.size() >= Math.clamp(limit, 1, 20)) break;
      }
    } catch (Exception e) {
      throw new Problem("examples_unavailable", "GRETL-Beispiele nicht lesbar.");
    }
    return Json.map("task", task, "examples", found);
  }
}
