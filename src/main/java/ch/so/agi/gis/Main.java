package ch.so.agi.gis;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.*;
import java.util.*;

public final class Main {
  public static void main(String[] argv) {
    int result = execute(argv);
    if (result != 0) System.exit(result);
  }

  static int execute(String[] argv) {
    try {
      var args = new ArrayList<>(Arrays.asList(argv));
      String config = null;
      int index = args.indexOf("--config");
      if (index >= 0) {
        if (index + 1 >= args.size()) throw new Problem("invalid_arguments", "--config PATH.");
        config = args.remove(index + 1);
        args.remove(index);
      }
      if (args.isEmpty() || args.getFirst().equals("--help")) {
        print(
            Json.map(
                "commands",
                List.of(
                    "init [--update-tools] [--update-docs] [--skip-smoke]",
                    "doctor",
                    "serve",
                    "schema OP",
                    "call OP --json JSON|--args-file PATH"),
                "operations",
                Operations.ALL.keySet()));
        return 0;
      }
      String command = args.removeFirst();
      if (command.equals("schema")) {
        if (args.size() != 1 || !Operations.ALL.containsKey(args.getFirst()))
          throw new Problem("invalid_arguments", "schema OP.");
        var op = Operations.ALL.get(args.getFirst());
        print(
            Json.map(
                "operation",
                args.getFirst(),
                "description",
                op.description(),
                "inputSchema",
                op.schema()));
        return 0;
      }
      Settings s = Settings.load(config);
      if (command.equals("init")) {
        if (args.stream()
            .anyMatch(a -> !Set.of("--update-tools", "--update-docs", "--skip-smoke").contains(a)))
          throw new Problem("invalid_arguments", "Unbekannte Init-Option.");
        var result =
            new Setup(s)
                .init(
                    args.contains("--update-tools"),
                    args.contains("--update-docs"),
                    args.contains("--skip-smoke"));
        print(result);
        return Boolean.TRUE.equals(result.get("ready")) ? 0 : 1;
      }
      if (command.equals("doctor")) {
        var result = new Setup(s).doctor();
        print(result);
        return Boolean.TRUE.equals(result.get("ready")) ? 0 : 1;
      }
      if (command.equals("serve")) {
        serve(s);
        return 0;
      }
      if (!command.equals("call") || args.isEmpty())
        throw new Problem("invalid_arguments", "call OP.");
      String operation = args.removeFirst(), raw = "{}";
      if (!args.isEmpty()) {
        if (args.size() != 2)
          throw new Problem("invalid_arguments", "--json JSON oder --args-file PATH.");
        String flag = args.removeFirst(), value = args.removeFirst();
        raw =
            switch (flag) {
              case "--json" -> value;
              case "--args-file" ->
                  value.equals("-")
                      ? new String(
                          System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                      : Json.contents(Path.of(value));
              default -> throw new Problem("invalid_arguments", "Unbekanntes Argument.");
            };
      }
      try (var workflow = new Steps(s)) {
        var result = workflow.call(operation, Json.read(raw));
        print(result);
        return Boolean.FALSE.equals(result.get("valid")) ? 1 : 0;
      }
    } catch (Problem e) {
      print(e.result());
      return 2;
    } catch (Exception e) {
      print(
          Json.map(
              "error",
              "operation_failed",
              "exception",
              e.getClass().getSimpleName(),
              "message",
              "Operation fehlgeschlagen."));
      return 1;
    }
  }

  static void print(Object value) {
    System.out.println(Json.pretty(value));
  }

  static void serve(Settings s) throws Exception {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    var ended = new java.util.concurrent.CountDownLatch(1);
    var input =
        new java.io.FilterInputStream(System.in) {
          @Override
          public int read() throws java.io.IOException {
            int v = super.read();
            if (v < 0) ended.countDown();
            return v;
          }

          @Override
          public int read(byte[] b, int off, int len) throws java.io.IOException {
            int v = super.read(b, off, len);
            if (v < 0) ended.countDown();
            return v;
          }
        };
    var mapper = new JacksonMcpJsonMapper(new tools.jackson.databind.json.JsonMapper());
    var transport = new SerialStdioTransport(mapper, input, System.out);
    var workflow = new Steps(s);
    var toolSpecifications = new ArrayList<McpServerFeatures.SyncToolSpecification>();
    Operations.ALL.forEach(
        (name, op) ->
            toolSpecifications.add(
                McpServerFeatures.SyncToolSpecification.builder()
                    .tool(
                        McpSchema.Tool.builder()
                            .name(name)
                            .description(op.description())
                            .inputSchema(op.schema())
                            .build())
                    .callHandler(
                        (exchange, request) -> {
                          synchronized (workflow) {
                            try {
                              var result = workflow.call(name, request.arguments());
                              return McpSchema.CallToolResult.builder()
                                  .structuredContent(result)
                                  .addTextContent(Json.text(result))
                                  .isError(Boolean.FALSE.equals(result.get("valid")))
                                  .build();
                            } catch (Problem e) {
                              return McpSchema.CallToolResult.builder()
                                  .structuredContent(e.result())
                                  .addTextContent(Json.text(e.result()))
                                  .isError(true)
                                  .build();
                            }
                          }
                        })
                    .build()));
    var server =
        McpServer.sync(transport)
            .serverInfo("gis-themenintegrator", "0.1.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            .instructions(
                "Legacy-GIS-Vorgänge in eigenen Docker-Datenbanken. Nach jeder fachlichen Etappe Menschen fragen. approve darf nur tatsächliche menschliche Antworten protokollieren. execute führt genau eine freigegebene Etappe aus. Logs und Prozesserfolg ersetzen keine fachliche Abnahme.")
            .tools(toolSpecifications)
            .build();
    var closing = new java.util.concurrent.atomic.AtomicBoolean();
    Runnable close =
        () -> {
          if (closing.compareAndSet(false, true)) {
            server.close();
            workflow.close();
          }
        };
    Runtime.getRuntime().addShutdownHook(new Thread(close));
    ended.await();
    close.run();
  }
}
