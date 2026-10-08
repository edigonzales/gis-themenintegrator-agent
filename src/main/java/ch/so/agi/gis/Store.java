package ch.so.agi.gis;

import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

final class Store {
  final Settings s;

  Store(Settings s) {
    this.s = s;
  }

  Path dir(String id) {
    if (!id.matches("[a-f0-9]{32}")) throw new Problem("run_unknown", "Ungültige Vorgangskennung.");
    return s.state.resolve("runs").resolve(id);
  }

  synchronized <T> T with(String id, java.util.function.Function<Map<String, Object>, T> action) {
    Path directory = dir(id);
    if (!Files.isRegularFile(directory.resolve("state.json")))
      throw new Problem("run_unknown", "Vorgang fehlt.");
    try (var channel =
        FileChannel.open(
            directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException e) {
        lock = null;
      }
      if (lock == null) throw new Problem("busy", "Vorgang wird bereits bearbeitet.");
      try (var held = lock) {
        var run = Json.read(directory.resolve("state.json"));
        try {
          return action.apply(run);
        } finally {
          Json.atomic(directory.resolve("state.json"), run);
        }
      }
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(
          "state_unavailable",
          "Vorgang kann nicht gesperrt werden.",
          "exception",
          e.getClass().getSimpleName());
    }
  }

  void create(Map<String, Object> run) {
    Json.atomic(dir(Json.required(run, "id")).resolve("state.json"), run);
  }

  static void event(Map<String, Object> run, String action, Object details) {
    var events = Json.list(run.computeIfAbsent("events", k -> new ArrayList<>()));
    events.add(Json.map("time", Json.now(), "action", action, "details", details));
  }
}
