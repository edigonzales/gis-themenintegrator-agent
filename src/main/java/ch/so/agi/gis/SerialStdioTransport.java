package ch.so.agi.gis;

import io.modelcontextprotocol.json.*;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.*;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import reactor.core.publisher.Mono;

/** Serialize SDK 2.0.1 STDIO sends; concurrent tryEmitNext otherwise loses replies. */
final class SerialStdioTransport extends StdioServerTransportProvider {
  SerialStdioTransport(McpJsonMapper mapper, InputStream input, OutputStream output) {
    super(mapper, input, output);
  }

  @Override
  public void setSessionFactory(McpServerSession.Factory factory) {
    super.setSessionFactory(transport -> factory.create(new Sender(transport)));
  }

  static final class Sender implements McpServerTransport {
    final McpServerTransport delegate;
    final ExecutorService writer =
        Executors.newSingleThreadExecutor(
            task -> {
              var thread = new Thread(task, "gis-mcp-writer");
              thread.setDaemon(true);
              return thread;
            });

    Sender(McpServerTransport delegate) {
      this.delegate = delegate;
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
      return Mono.create(
          sink -> {
            try {
              writer.execute(
                  () -> {
                    try {
                      delegate.sendMessage(message).block(Duration.ofSeconds(10));
                      sink.success();
                    } catch (Exception e) {
                      sink.error(e);
                    }
                  });
            } catch (RejectedExecutionException e) {
              sink.error(e);
            }
          });
    }

    @Override
    public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
      return delegate.unmarshalFrom(value, type);
    }

    @Override
    public Mono<Void> closeGracefully() {
      return delegate.closeGracefully().doFinally(signal -> writer.shutdown());
    }

    @Override
    public void close() {
      writer.shutdownNow();
      delegate.close();
    }
  }
}
