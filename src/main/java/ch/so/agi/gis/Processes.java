package ch.so.agi.gis;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

class Processes {
  record Result(int exitCode, String output) {}

  private final Set<String> secrets = Collections.synchronizedSet(new HashSet<>());

  void secret(String text) {
    if (text != null && !text.isEmpty()) secrets.add(text);
  }

  String redact(String text) {
    synchronized (secrets) {
      for (String secret : secrets) text = text.replace(secret, "[REDACTED]");
    }
    return text;
  }

  Result run(List<String> command, Path cwd, int seconds, Path log) {
    Path output = null;
    try {
      output = Files.createTempFile("gis-process-", ".log");
      var builder =
          new ProcessBuilder(command)
              .directory(cwd.toFile())
              .redirectErrorStream(true)
              .redirectOutput(output.toFile());
      // A local runner never inherits workstation production credentials.
      builder.environment().keySet().removeIf(k -> k.startsWith("ORG_GRADLE_PROJECT_"));
      Process process = builder.start();
      boolean done = process.waitFor(seconds, TimeUnit.SECONDS);
      if (!done) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
      String content = redact(Files.readString(output));
      if (log != null) Json.write(log, content);
      if (!done)
        throw new Problem(
            "command_timeout",
            "Externes Werkzeug hat das Zeitlimit erreicht.",
            "log",
            log == null ? "" : log.toString());
      return new Result(process.exitValue(), content);
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(
          "command_unavailable",
          "Werkzeug nicht ausführbar.",
          "tool",
          command.getFirst(),
          "exception",
          e.getClass().getSimpleName());
    } finally {
      if (output != null)
        try {
          Files.deleteIfExists(output);
        } catch (Exception ignored) {
        }
    }
  }

  String checked(List<String> command, Path cwd, int timeout) {
    var result = run(command, cwd, timeout, null);
    if (result.exitCode() != 0)
      throw new Problem(
          "command_failed",
          "Werkzeug meldet einen Fehler.",
          "tool",
          command.getFirst(),
          "returncode",
          result.exitCode(),
          "output",
          result.output().substring(0, Math.min(3000, result.output().length())));
    return result.output().strip();
  }
}
