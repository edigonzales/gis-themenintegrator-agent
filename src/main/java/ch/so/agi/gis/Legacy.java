package ch.so.agi.gis;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

final class Legacy {
  static Map<String, String> properties(Path path) {
    var result = new TreeMap<String, String>();
    if (!Files.isRegularFile(path)) return result;
    try (var reader = Files.newBufferedReader(path)) {
      var properties = new Properties();
      properties.load(reader);
      properties.forEach((k, v) -> result.put(k.toString(), v.toString()));
      return result;
    } catch (Exception e) {
      throw new Problem(
          "properties_invalid", "Properties-Datei nicht lesbar.", "path", path.toString());
    }
  }

  static List<Map<String, Object>> schemas(Settings s, Path repo, Object selectors) {
    var result = new ArrayList<Map<String, Object>>();
    for (Object selector : Json.list(selectors)) {
      String topic = null, folder = null, dbOverride = null;
      if (selector instanceof String name) {
        if (name.contains("/")) {
          String[] parts = name.split("/");
          if (parts.length != 2) throw new Problem("schema_unknown", "Thema/Schemaordner angeben.");
          topic = parts[0];
          folder = parts[1];
        } else {
          var matches = new ArrayList<Path>();
          try (var paths = Files.walk(repo.resolve("topics"))) {
            paths
                .filter(p -> p.getFileName().toString().equals("schema.properties"))
                .forEach(
                    p -> {
                      String generated =
                          "schema_"
                              + p.getParent().getParent().getFileName()
                              + p.getParent().getFileName().toString().replaceFirst("schema", "");
                      if (generated.equals(name)) matches.add(p);
                    });
          } catch (Exception e) {
            throw new Problem("schema_unknown", "Schema-Jobs nicht lesbar.");
          }
          if (matches.size() != 1)
            throw new Problem(
                "schema_unknown", "Schema-Job nicht eindeutig gefunden.", "job", name);
          folder = matches.getFirst().getParent().getFileName().toString();
          topic = matches.getFirst().getParent().getParent().getFileName().toString();
        }
      } else {
        var object = Json.obj(selector);
        topic = Json.required(object, "topic");
        folder = Json.required(object, "schema_dir");
        dbOverride = Json.str(object, "database", null);
      }
      if (!topic.matches("[a-z][a-z0-9_]*") || !folder.matches("schema[a-z0-9_]*"))
        throw new Problem("schema_unknown", "Ungültiges Thema oder Schemaordner.");
      Path directory = Json.inside(repo, "topics/" + topic + "/" + folder);
      if (!Files.isRegularFile(directory.resolve("schema.properties")))
        throw new Problem(
            "schema_unknown", "schema.properties fehlt.", "topic", topic, "schema_dir", folder);
      var properties = properties(directory.resolve("schema.properties"));
      var jobs = properties(directory.resolve("job.properties"));
      String databases = properties.get("databases");
      if (databases == null || properties.get("models") == null)
        throw new Problem("schema_invalid", "databases/models fehlen.");
      int version = 0;
      if (properties.containsKey("schemaVersion"))
        try {
          version = Integer.parseInt(properties.get("schemaVersion"));
        } catch (Exception e) {
          throw new Problem("schema_invalid", "Ungültige schemaVersion.");
        }
      if (version < 0) throw new Problem("schema_invalid", "Negative schemaVersion.");
      String base =
          properties.getOrDefault("schemaName", topic + folder.replaceFirst("schema", ""));
      String physical = base + (version == 0 ? "" : "_v" + version);
      Database.ident(physical);
      for (String db : (dbOverride == null ? databases : dbOverride).split(",")) {
        db = db.strip();
        if (!Set.of("edit", "pub").contains(db))
          throw new Problem("database_unsupported", "V1 unterstützt edit/pub.", "database", db);
        String suffix = databases.split(",").length > 1 ? "_" + db + "db" : "";
        Path grants =
            Json.inside(
                repo,
                "topics/"
                    + topic
                    + "/"
                    + folder.replaceFirst("schema", "privileges")
                    + "/grants"
                    + suffix
                    + ".sql");
        var sql = Json.map();
        for (String name : List.of("create_views.sql", "postscript.sql", "stdcols.sql"))
          if (Files.isRegularFile(directory.resolve(name)))
            sql.put(name, Json.contents(directory.resolve(name)));
        if (Files.isRegularFile(grants)) sql.put("grants.sql", Json.contents(grants));
        result.add(
            Json.map(
                "key",
                topic + "/" + folder + ":" + db,
                "topic",
                topic,
                "schema_dir",
                folder,
                "database",
                db,
                "physical_name",
                physical,
                "base_name",
                base,
                "schema_version",
                version,
                "role_suffix",
                suffix,
                "runtime",
                s.runtime(jobs),
                "properties",
                properties,
                "job_properties",
                jobs,
                "sql",
                sql,
                "models",
                Arrays.stream(properties.get("models").split(";"))
                    .map(String::strip)
                    .filter(v -> !v.isEmpty())
                    .toList()));
      }
    }
    return result;
  }

  static Map<String, Object> job(Settings s, Path repo, String name) {
    if (name == null || name.isBlank()) return Json.map();
    if (!name.matches("[a-zA-Z][a-zA-Z0-9_-]*"))
      throw new Problem("job_invalid", "Ungültiger GRETL-Jobname.");
    Path directory = Json.inside(repo, name), build = directory.resolve("build.gradle");
    if (!Files.isRegularFile(build) && !Files.isRegularFile(directory.resolve("settings.gradle")))
      throw new Problem("job_unknown", "GRETL-Job fehlt.", "job", name);
    var properties = properties(directory.resolve("job.properties"));
    var files = Json.map();
    var modelNames = new TreeSet<String>();
    try (var paths = Files.walk(directory)) {
      for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
        String relative = directory.relativize(file).toString();
        if (relative.startsWith("build/") || relative.startsWith(".gradle/")) continue;
        if (file.toString().endsWith(".sql")
            || file.toString().endsWith(".gradle")
            || file.toString().endsWith(".properties")
            || file.getFileName().toString().equals("Jenkinsfile")) {
          String text = Json.contents(file);
          files.put(relative, text);
          var matcher = Pattern.compile("\\bmodels\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(text);
          while (matcher.find()) modelNames.addAll(Arrays.asList(matcher.group(1).split(";")));
        }
      }
    } catch (Exception e) {
      throw new Problem("job_unreadable", "Jobdateien nicht lesbar.");
    }
    return Json.map(
        "name",
        name,
        "runtime",
        s.runtime(properties),
        "job_properties",
        properties,
        "files",
        files,
        "models",
        new ArrayList<>(modelNames));
  }

  static Set<String> recipients(Map<String, Object> schema) {
    var recipients = new TreeSet<String>();
    for (Object text : Json.obj(schema.get("sql")).values()) {
      var matcher =
          Pattern.compile("(?im)^\\s*GRANT\\s+[^;]+?\\s+TO\\s+([^;]+);").matcher(text.toString());
      while (matcher.find())
        for (String role : matcher.group(1).split(",")) {
          String name = role.strip().replace("\"", "");
          if (name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) recipients.add(name);
        }
    }
    return recipients;
  }

  static String writeProperties(Map<String, Object> values) {
    var result = new StringBuilder();
    values.forEach(
        (key, value) -> {
          if (!key.matches("[A-Za-z][A-Za-z0-9_.]*")
              || value.toString().contains("\n")
              || value.toString().contains("\r"))
            throw new Problem("properties_invalid", "Ungültiger Properties-Wert.");
          result.append(key).append(" = ").append(value).append("\n");
        });
    return result.toString();
  }
}
