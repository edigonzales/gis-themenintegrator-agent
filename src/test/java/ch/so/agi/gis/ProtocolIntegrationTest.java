package ch.so.agi.gis;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class ProtocolIntegrationTest {
  @Test
  void actualInterlisAuthoringProducesReviewedModelCandidate() throws Exception {
    var fixture = new Fixture(root);
    try (var w = new Steps(fixture.s)) {
      var run = fixture.start(w, false);
      String id = Json.required(run, "id"), name = "GisAgent_AuthorFixture";
      var spec =
          Json.map(
              "name",
              name,
              "uri",
              "https://example.invalid/gis-agent",
              "version",
              "2026-10-08",
              "iliVersion",
              "2.3",
              "language",
              "en",
              "topics",
              List.of(
                  Json.map(
                      "name",
                      "Data",
                      "classes",
                      List.of(
                          Json.map(
                              "name",
                              "Item",
                              "attributes",
                              List.of(
                                  Json.map(
                                      "name",
                                      "code",
                                      "mandatory",
                                      true,
                                      "typeSpec",
                                      Json.map(
                                          "baseType",
                                          Json.map("kind", "TEXT", "length", 40)))))))));
      Map<String, Object> authored;
      try {
        authored =
            w.call(
                "model",
                Json.map(
                    "run_id",
                    id,
                    "tool",
                    "authorIliModel",
                    "arguments",
                    Json.map("spec", spec, "ruleProfile", "CORE", "modelPurpose", "CAPTURE"),
                    "relative_path",
                    "models/DEMO/" + name + ".ili"));
      } catch (Problem error) {
        throw new AssertionError(Json.pretty(error.result()), error);
      }
      assertTrue((Boolean) Json.obj(authored.get("response")).get("complete"));
      var prepared = w.call("models_prepare", Json.map("run_id", id, "models", List.of(name)));
      assertEquals(true, prepared.get("compiled"));
      assertTrue(
          Files.isRegularFile(w.work(run, "models").resolve("models/DEMO/" + name + ".ili")));
      assertFalse(
          Files.exists(fixture.s.repoPath("models").resolve("models/DEMO/" + name + ".ili")));
    }
  }

  @TempDir Path root;

  @Test
  void cliMcpParityConcurrentRepliesAndEofShutdown() throws Exception {
    var fixture = new Fixture(root);
    String javaCommand = Path.of(System.getProperty("java.home"), "bin/java").toString();
    String jar =
        Path.of(System.getProperty("gis.root"), "build/libs/gis-integrator.jar").toString();
    var command = List.of(javaCommand, "-jar", jar, "--config", fixture.s.file.toString(), "serve");
    var errors = new StringBuffer();
    var transport =
        new EofStdioTransport(
            command,
            fixture.root,
            new JacksonMcpJsonMapper(new tools.jackson.databind.json.JsonMapper()),
            line -> errors.append(line).append("\n"));
    var client =
        McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(30))
            .initializationTimeout(Duration.ofSeconds(30))
            .build();
    try {
      client.initialize();
      assertEquals(
          Operations.ALL.keySet(),
          client.listTools().tools().stream()
              .map(McpSchema.Tool::name)
              .collect(java.util.stream.Collectors.toSet()));
      var start =
          client.callTool(
              new McpSchema.CallToolRequest(
                  "start", Json.map("workflow", "isolated_protocol_fixture")));
      assertFalse(Boolean.TRUE.equals(start.isError()));
      String id = Json.required(Json.obj(start.structuredContent()), "run_id");
      var status = client.callTool(new McpSchema.CallToolRequest("status", Json.map("run_id", id)));
      var cli =
          fixture.p.checked(
              List.of(
                  javaCommand,
                  "-jar",
                  jar,
                  "--config",
                  fixture.s.file.toString(),
                  "call",
                  "status",
                  "--json",
                  Json.text(Json.map("run_id", id))),
              fixture.root,
              30);
      assertEquals(Json.digest(status.structuredContent()), Json.digest(Json.read(cli)));
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var futures = new ArrayList<Future<McpSchema.CallToolResult>>();
        for (int i = 0; i < 12; i++)
          futures.add(
              executor.submit(
                  () ->
                      client.callTool(
                          new McpSchema.CallToolRequest("status", Json.map("run_id", id)))));
        for (var future : futures)
          assertFalse(Boolean.TRUE.equals(future.get(30, TimeUnit.SECONDS).isError()));
      }
      var failure =
          client.callTool(
              new McpSchema.CallToolRequest(
                  "approve",
                  Json.map(
                      "run_id",
                      id,
                      "plan_id",
                      "missing",
                      "fingerprint",
                      "f",
                      "human_statement",
                      "ISOLATED SOFTWARE TEST ONLY")));
      assertTrue(Boolean.TRUE.equals(failure.isError()));
      assertEquals("plan_unknown", Json.obj(failure.structuredContent()).get("error"));
    } finally {
      client.closeGracefully();
    }
    assertTrue(transport.process().waitFor(10, TimeUnit.SECONDS), errors.toString());
    assertEquals(0, transport.process().exitValue(), errors.toString());
  }
}
