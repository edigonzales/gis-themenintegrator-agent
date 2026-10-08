package ch.so.agi.gis;

import java.nio.file.*;
import java.util.*;
import org.tomlj.*;

final class Settings {
  final Path root, state, file;
  final Map<String, Object> values;
  final int timeout;

  Settings(Path file, Map<String, Object> values) {
    this.file = file.toAbsolutePath().normalize();
    this.values = values;
    root = this.file.getParent().resolve(Json.str(values, "root", "..")).normalize();
    state = path(Json.str(values, "state_dir", ".gis-agent"));
    timeout = Json.number(values, "timeout_seconds", 300);
    if (timeout < 1 || timeout > 7200)
      throw new Problem("configuration_invalid", "timeout_seconds: 1–7200.");
  }

  static Settings load(String location) {
    Path file = Path.of(location == null ? "config/local.toml" : location).toAbsolutePath();
    if (!Files.exists(file)) {
      Path example = file.getParent().resolve("local.example.toml");
      if (!Files.exists(example))
        throw new Problem("configuration_missing", "Konfiguration fehlt.", "path", file.toString());
      return parse(file, Json.contents(example));
    }
    return parse(file, Json.contents(file));
  }

  static Settings parse(Path file, String text) {
    var parsed = Toml.parse(text);
    if (parsed.hasErrors())
      throw new Problem("configuration_invalid", "Ungültige TOML-Konfiguration.");
    return new Settings(file, Json.obj(convert(parsed)));
  }

  static Object convert(Object value) {
    if (value instanceof TomlTable table) {
      var map = Json.map();
      table.keySet().forEach(key -> map.put(key, convert(table.get(List.of(key)))));
      return map;
    }
    if (value instanceof TomlArray array) {
      var list = new ArrayList<>();
      for (int i = 0; i < array.size(); i++) list.add(convert(array.get(i)));
      return list;
    }
    return value;
  }

  Path path(String value) {
    return root.resolve(value).normalize().toAbsolutePath();
  }

  Map<String, Object> repo(String name) {
    var repos = Json.obj(values.get("repositories"));
    if (!Set.of("gretl", "schema", "models").contains(name) || !repos.containsKey(name))
      throw new Problem("repository_unknown", "Repo muss gretl, schema oder models sein.");
    return Json.obj(repos.get(name));
  }

  Path repoPath(String name) {
    return path(Json.required(repo(name), "path"));
  }

  Map<String, Object> images() {
    return Json.obj(values.get("images"));
  }

  String image(String profile) {
    return Json.required(images(), profile);
  }

  Map<String, Object> interlis() {
    return Json.obj(values.get("interlis"));
  }

  String runtime(Map<String, String> properties) {
    String label = properties.getOrDefault("nodeLabel", "");
    return switch (label) {
      case "" -> Json.str(values, "default_runtime", "2.x");
      case "gretl-3.1" -> "3.1";
      case "gretl-3.2" -> "3.2";
      default -> throw new Problem("runtime_unknown", "Unbekanntes nodeLabel.", "label", label);
    };
  }

  List<String> modelRepositories() {
    return Json.strings(values.get("model_repositories"));
  }
}
