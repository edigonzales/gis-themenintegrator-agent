package ch.so.agi.gis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

public final class Json {
  static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  public static LinkedHashMap<String, Object> map(Object... pairs) {
    var m = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i].toString(), pairs[i + 1]);
    return m;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> obj(Object v) {
    if (v == null) return new LinkedHashMap<>();
    if (!(v instanceof Map)) throw new Problem("invalid_arguments", "JSON-Objekt erforderlich.");
    return (Map<String, Object>) v;
  }

  @SuppressWarnings("unchecked")
  public static List<Object> list(Object v) {
    if (v == null) return new ArrayList<>();
    if (!(v instanceof List)) throw new Problem("invalid_arguments", "JSON-Liste erforderlich.");
    return (List<Object>) v;
  }

  public static String str(Map<String, Object> m, String k, String d) {
    var v = m.get(k);
    return v == null ? d : v.toString();
  }

  public static String required(Map<String, Object> m, String k) {
    var s = str(m, k, "");
    if (s.isBlank()) throw new Problem("missing_argument", "Pflichtangabe fehlt.", "field", k);
    return s;
  }

  public static boolean bool(Map<String, Object> m, String k, boolean d) {
    return m.containsKey(k) ? Boolean.TRUE.equals(m.get(k)) : d;
  }

  public static int number(Map<String, Object> m, String k, int d) {
    return m.get(k) instanceof Number n ? n.intValue() : d;
  }

  public static Map<String, Object> read(String s) {
    try {
      return MAPPER.readValue(s, new TypeReference<>() {});
    } catch (IOException e) {
      throw new Problem("invalid_json", "Ungültiges JSON.");
    }
  }

  public static Map<String, Object> read(Path p) {
    try {
      return read(Files.readString(p));
    } catch (IOException e) {
      throw new Problem("file_missing", "Datei nicht lesbar.", "path", p.toString());
    }
  }

  public static String text(Object v) {
    try {
      return MAPPER.writeValueAsString(v);
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static String pretty(Object v) {
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(v);
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static String now() {
    return Instant.now().toString();
  }

  public static String hash(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sha(Path p) {
    try (var in = Files.newInputStream(p)) {
      var md = MessageDigest.getInstance("SHA-256");
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) md.update(b, 0, n);
      return HexFormat.of().formatHex(md.digest());
    } catch (Exception e) {
      throw new Problem("file_missing", "Datei nicht lesbar.", "path", p.toString());
    }
  }

  public static String digest(Object o) {
    return hash(text(o).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public static void atomic(Path p, Object o) {
    Path tmp = null;
    try {
      Files.createDirectories(p.toAbsolutePath().getParent());
      tmp = Files.createTempFile(p.getParent(), "state-", ".tmp");
      try (var ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
        var b = ByteBuffer.wrap(pretty(o).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        while (b.hasRemaining()) ch.write(b);
        ch.force(true);
      }
      Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new Problem("state_write_failed", "Vorgang konnte nicht atomar gesichert werden.");
    } finally {
      if (tmp != null)
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
        }
    }
  }

  public static Path inside(Path root, String relative) {
    Path p = root.resolve(relative).normalize().toAbsolutePath();
    if (!p.startsWith(root.toAbsolutePath().normalize()) || Path.of(relative).isAbsolute())
      throw new Problem("unsafe_path", "Pfad liegt ausserhalb des erlaubten Verzeichnisses.");
    try {
      Path ancestor = p;
      while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
      if (!ancestor.toRealPath().startsWith(root.toRealPath()))
        throw new Problem("unsafe_path", "Symlink liegt ausserhalb des erlaubten Verzeichnisses.");
    } catch (IOException e) {
      throw new Problem("unsafe_path", "Pfad kann nicht aufgelöst werden.");
    }
    return p;
  }

  public static void write(Path p, String s) {
    try {
      Files.createDirectories(p.getParent());
      Files.writeString(p, s);
    } catch (IOException e) {
      throw new Problem("file_write_failed", "Datei kann nicht geschrieben werden.");
    }
  }

  public static String contents(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      throw new Problem("file_missing", "Datei fehlt.", "path", p.toString());
    }
  }

  public static List<String> strings(Object v) {
    return list(v).stream()
        .map(
            value -> {
              if (!(value instanceof String s))
                throw new Problem("invalid_arguments", "Liste darf nur Zeichenketten enthalten.");
              return s;
            })
        .toList();
  }
}
