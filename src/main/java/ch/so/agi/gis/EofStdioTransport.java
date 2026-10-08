package ch.so.agi.gis;

import io.modelcontextprotocol.json.*;
import io.modelcontextprotocol.spec.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import reactor.core.publisher.Mono;

/** SDK transport whose normal shutdown closes stdin before signalling the child. */
final class EofStdioTransport implements McpClientTransport {
  private final List<String> command;
  private final Path directory;
  private final McpJsonMapper mapper;
  private final Consumer<String> errors;
  private volatile Consumer<Throwable> exceptionHandler = e -> {};
  private volatile boolean closing;
  private Process process;
  private BufferedWriter writer;

  EofStdioTransport(
      List<String> command, Path directory, McpJsonMapper mapper, Consumer<String> errors) {
    this.command = List.copyOf(command);
    this.directory = directory;
    this.mapper = mapper;
    this.errors = errors;
  }

  @Override
  public void setExceptionHandler(Consumer<Throwable> handler) {
    exceptionHandler = handler;
  }

  @Override
  public Mono<Void> connect(
      Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    return Mono.fromRunnable(
        () -> {
          try {
            synchronized (this) {
              if (process != null) throw new IllegalStateException("Transport already connected");
              process = new ProcessBuilder(command).directory(directory.toFile()).start();
              writer =
                  new BufferedWriter(
                      new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            }
            Thread.ofPlatform()
                .daemon(true)
                .name("integrator-mcp-stderr")
                .start(
                    () -> {
                      try (var reader =
                          new BufferedReader(
                              new InputStreamReader(
                                  process.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) errors.accept(line);
                      } catch (IOException ignored) {
                      }
                    });
            Thread.ofPlatform()
                .daemon(true)
                .name("integrator-mcp-stdout")
                .start(
                    () -> {
                      try (var reader =
                          new BufferedReader(
                              new InputStreamReader(
                                  process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = readLine(reader)) != null) {
                          var message = McpSchema.deserializeJsonRpcMessage(mapper, line);
                          handler
                              .apply(Mono.just(message))
                              .flatMap(this::sendMessage)
                              .subscribe(ignored -> {}, exceptionHandler);
                        }
                        if (!closing)
                          exceptionHandler.accept(new EOFException("MCP process closed stdout"));
                      } catch (Exception e) {
                        if (!closing) exceptionHandler.accept(e);
                      }
                    });
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        });
  }

  static String readLine(Reader reader) throws IOException {
    var line = new StringBuilder();
    int c;
    while ((c = reader.read()) != -1 && c != '\n') {
      if (line.length() >= 16 * 1024 * 1024) throw new IOException("MCP response exceeds 16 MiB");
      if (c != '\r') line.append((char) c);
    }
    return c == -1 && line.isEmpty() ? null : line.toString();
  }

  @Override
  public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
    return Mono.fromRunnable(
        () -> {
          synchronized (this) {
            if (closing || writer == null) throw new IllegalStateException("MCP transport closed");
            try {
              writer.write(mapper.writeValueAsString(message));
              writer.write('\n');
              writer.flush();
            } catch (IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          }
        });
  }

  @Override
  public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
    try {
      return mapper.readValue(mapper.writeValueAsBytes(value), type);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Override
  public Mono<Void> closeGracefully() {
    return Mono.fromRunnable(
        () -> {
          Process child;
          synchronized (this) {
            if (closing) return;
            closing = true;
            child = process;
            try {
              if (writer != null) writer.close();
            } catch (IOException ignored) {
            }
          }
          if (child == null) return;
          try {
            if (!child.waitFor(5, TimeUnit.SECONDS)) {
              child.destroy();
              if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly();
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            child.destroy();
          }
        });
  }

  synchronized Process process() {
    return process;
  }
}
