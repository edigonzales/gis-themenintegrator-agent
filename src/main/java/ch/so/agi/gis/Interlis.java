package ch.so.agi.gis;

import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;

final class Interlis implements AutoCloseable {
  final Settings s;
  final DockerRuntime runtime;
  McpSyncClient client;

  Interlis(Settings s, DockerRuntime runtime) {
    this.s = s;
    this.runtime = runtime;
  }

  synchronized void connect() {
    if (client != null) return;
    try {
      McpClientTransport transport;
      if (Json.str(s.interlis(), "transport", "stdio").equals("http")) {
        URI uri = URI.create(Json.required(s.interlis(), "url"));
        if (uri.getUserInfo() != null || !Set.of("http", "https").contains(uri.getScheme()))
          throw new Problem("configuration_invalid", "Ungültige MCP-URL.");
        transport =
            HttpClientStreamableHttpTransport.builder(
                    uri.getScheme() + "://" + uri.getRawAuthority())
                .endpoint(uri.getRawPath())
                .openConnectionOnStartup(false)
                .build();
      } else {
        transport =
            new EofStdioTransport(
                List.of(
                    "docker",
                    "run",
                    "--rm",
                    "-i",
                    "-e",
                    "SPRING_PROFILES_ACTIVE=stdio",
                    Json.required(runtime.image("interlis"), "id")),
                s.root,
                new JacksonMcpJsonMapper(new tools.jackson.databind.json.JsonMapper()),
                line -> {});
      }
      client =
          McpClient.sync(transport)
              .requestTimeout(Duration.ofSeconds(s.timeout))
              .initializationTimeout(Duration.ofSeconds(60))
              .build();
      client.initialize();
    } catch (Problem e) {
      close();
      throw e;
    } catch (Exception e) {
      close();
      throw new Problem(
          "interlis_unavailable",
          "INTERLIS-MCP nicht erreichbar.",
          "exception",
          e.getClass().getSimpleName());
    }
  }

  synchronized Map<String, Object> tools() {
    connect();
    return Json.map(
        "tools",
        client.listTools().tools().stream()
            .map(
                t ->
                    Json.map(
                        "name",
                        t.name(),
                        "description",
                        t.description(),
                        "input_schema",
                        t.inputSchema()))
            .toList());
  }

  synchronized Map<String, Object> call(String tool, Map<String, Object> arguments) {
    connect();
    if (!client.listTools().tools().stream().anyMatch(t -> t.name().equals(tool)))
      throw new Problem("tool_unknown", "INTERLIS-Werkzeug nicht vorhanden.", "tool", tool);
    try {
      var result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
      Object structured = result.structuredContent();
      if (structured == null) {
        String text =
            result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce("", (a, b) -> a + b + "\n");
        structured = Json.read(text);
      }
      var response = Json.obj(structured);
      if (Boolean.TRUE.equals(result.isError()))
        throw new Problem(
            "interlis_error", "INTERLIS-MCP meldet einen Fehler.", "response", response);
      return response;
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      close();
      throw new Problem("interlis_unconfirmed", "Werkzeugaufruf nicht bestätigt.", "tool", tool);
    }
  }

  @Override
  public synchronized void close() {
    if (client != null) {
      try {
        client.closeGracefully();
      } catch (Exception ignored) {
      }
      client = null;
    }
  }
}
