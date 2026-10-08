package ch.so.agi.gis;

import ch.interlis.ili2c.Ili2c;
import ch.interlis.ili2c.ModelScan;
import ch.interlis.ilirepository.IliManager;
import java.nio.file.*;
import java.util.*;

final class Models {
  final Settings s;

  Models(Settings s) {
    this.s = s;
  }

  Map<String, Object> prepare(
      Path modelRepo,
      Path schemaRepo,
      Path directory,
      List<String> requested,
      Map<String, Object> primaryFiles) {
    if (requested.isEmpty()) throw new Problem("models_missing", "INTERLIS-Modellnamen angeben.");
    Path destination = directory.resolve("frozen-models"), roots = directory.resolve("root-models");
    try {
      if (Files.exists(roots)) deleteTree(roots);
      Files.createDirectories(roots);
      var parentDirs = new TreeSet<String>();
      var candidates = new ArrayList<Path>();
      for (Path source :
          List.of(
              modelRepo.resolve("models"),
              modelRepo.resolve("models-ext"),
              schemaRepo.resolve("topics"))) {
        if (!Files.isDirectory(source)) continue;
        try (var paths = Files.walk(source)) {
          paths
              .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".ili"))
              .forEach(
                  f -> {
                    candidates.add(f);
                    parentDirs.add(f.getParent().toString());
                  });
        }
      }
      var rootHashes = Json.map();
      var localInputs = new TreeMap<String, String>();
      for (Path candidate : candidates) localInputs.put(candidate.toString(), Json.sha(candidate));
      String localInputsHash = Json.digest(localInputs);
      for (String model : requested) {
        if (!model.matches("[A-Za-z][A-Za-z0-9_]*"))
          throw new Problem("model_invalid", "Ungültiger Modellname.");
        Path selected;
        if (primaryFiles.containsKey(model)) {
          String relative = Json.required(primaryFiles, model);
          selected = Json.inside(modelRepo, relative);
        } else {
          var matching =
              candidates.stream()
                  .filter(f -> f.getFileName().toString().equals(model + ".ili"))
                  .sorted(Comparator.comparing(f -> f.toString().contains("/mirror/") ? 1 : 0))
                  .toList();
          if (matching.isEmpty())
            throw new Problem(
                "model_not_found",
                "Hauptmodell nicht lokal gefunden; primary_files angeben.",
                "model",
                model);
          selected = matching.getFirst();
          if (matching.stream()
                  .filter(f -> !f.toString().contains("/mirror/"))
                  .map(Json::sha)
                  .distinct()
                  .count()
              > 1)
            throw new Problem(
                "model_ambiguous",
                "Mehrere lokale Modellstände; primary_files zuordnen.",
                "model",
                model,
                "candidates",
                matching.stream().map(Path::toString).toList());
        }
        byte[] bytes = Files.readAllBytes(selected);
        Files.write(roots.resolve(selected.getFileName()), bytes);
        rootHashes.put(model, Json.hash(bytes));
      }
      Path evidence = directory.resolve("models.json");
      if (Files.isRegularFile(evidence)) {
        var old = Json.read(evidence);
        if (rootHashes.equals(old.get("root_hashes"))
            && s.modelRepositories().equals(old.get("repositories"))
            && localInputsHash.equals(old.get("local_inputs_hash"))) {
          verify(directory, old);
          return old;
        }
      }
      var manager = new IliManager();
      manager.setCache(directory.resolve("model-cache").toFile());
      var requests = new ArrayList<String>();
      var http =
          java.net.http.HttpClient.newBuilder()
              .connectTimeout(java.time.Duration.ofSeconds(15))
              .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
              .build();
      manager.setResolver(
          new ch.interlis.ilirepository.IliResolver() {
            @Override
            public boolean resolvesUri(String uri) {
              return uri.startsWith("http://") || uri.startsWith("https://");
            }

            @Override
            public java.io.InputStream resolveIliFile(String uri, String filename)
                throws java.io.IOException {
              var url =
                  java.net
                      .URI
                      .create(uri.endsWith("/") ? uri : uri + "/")
                      .resolve(filename == null ? "" : filename);
              requests.add(url.toString());
              try {
                var response =
                    http.send(
                        java.net.http.HttpRequest.newBuilder(url)
                            .timeout(java.time.Duration.ofSeconds(40))
                            .GET()
                            .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                try (var input = response.body()) {
                  if (response.statusCode() == 404)
                    throw new java.io.FileNotFoundException(url.toString());
                  if (response.statusCode() != 200)
                    throw new java.io.IOException(
                        "Model repository HTTP " + response.statusCode() + ": " + url);
                  byte[] bytes = input.readNBytes(10_000_001);
                  if (bytes.length > 10_000_000)
                    throw new java.io.IOException("Model exceeds 10 MB");
                  return new java.io.ByteArrayInputStream(bytes);
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("Model resolution interrupted", e);
              }
            }
          });
      var repositories = new ArrayList<>(List.of(roots.toString()));
      // Model directories are explicit: no dependence on a user's ili2c settings.
      repositories.addAll(parentDirs);
      repositories.addAll(s.modelRepositories());
      manager.setRepositories(repositories.toArray(String[]::new));
      var config = manager.getConfig(new ArrayList<>(requested), 0.0d);
      Path temporary = Files.createTempDirectory(directory, "frozen-models-");
      var fileHashes = Json.map();
      var origins = Json.map();
      var seenModels = new HashSet<String>();
      var entries = config.iteratorFileEntry();
      while (entries.hasNext()) {
        var entry = (ch.interlis.ili2c.config.FileEntry) entries.next();
        Path source = Path.of(entry.getFilename());
        String name = source.getFileName().toString();
        if (fileHashes.containsKey(name))
          throw new Problem("model_ambiguous", "Doppelte Modell-Dateinamen.", "filename", name);
        var scan = ModelScan.scanIliFile(source.toFile());
        if (scan == null)
          throw new Problem("model_invalid", "Modell nicht lesbar.", "filename", name);
        var iterator = scan.iteratorModel();
        while (iterator.hasNext()) {
          String defined = ((ch.interlis.ili2c.modelscan.IliModel) iterator.next()).getName();
          if (!seenModels.add(defined))
            throw new Problem("model_ambiguous", "Modell mehrfach definiert.", "model", defined);
        }
        Files.copy(source, temporary.resolve(name));
        entry.setFilename(temporary.resolve(name).toString());
        fileHashes.put(name, Json.sha(source));
        origins.put(name, source.toString());
      }
      config.setAutoCompleteModelList(false);
      if (Ili2c.runCompiler(config) == null)
        throw new Problem("model_invalid", "Modellabschluss kompiliert nicht.");
      if (Files.exists(destination)) deleteTree(destination);
      Files.move(temporary, destination);
      var result =
          Json.map(
              "models",
              requested,
              "root_hashes",
              rootHashes,
              "local_inputs_hash",
              localInputsHash,
              "file_hashes",
              fileHashes,
              "origins",
              origins,
              "repositories",
              s.modelRepositories(),
              "requests",
              requests,
              "compiler",
              "ili2c 5.6.8",
              "compiled",
              true);
      Json.atomic(evidence, result);
      return result;
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(
          "model_preparation_failed",
          "Modellabhängigkeiten nicht vollständig auflösbar.",
          "exception",
          e.getClass().getSimpleName(),
          "detail",
          String.valueOf(e.getMessage()));
    }
  }

  static void verify(Path directory, Map<String, Object> evidence) {
    for (var file : Json.obj(evidence.get("file_hashes")).entrySet()) {
      Path path = Json.inside(directory.resolve("frozen-models"), file.getKey());
      if (!Files.isRegularFile(path) || !file.getValue().equals(Json.sha(path)))
        throw new Problem(
            "model_evidence_changed", "Eingefrorenes Modell verändert.", "file", file.getKey());
    }
  }

  static void deleteTree(Path path) throws java.io.IOException {
    try (var paths = Files.walk(path)) {
      for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(item);
    }
  }
}
