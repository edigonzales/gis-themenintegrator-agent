package ch.so.agi.gis;

import java.util.Map;

public final class Problem extends RuntimeException {
  public final String code;
  public final Map<String, Object> details;

  public Problem(String code, String message, Object... fields) {
    super(message);
    this.code = code;
    this.details = Json.map(fields);
  }

  public Map<String, Object> result() {
    var result = Json.map("error", code, "message", getMessage());
    result.putAll(details);
    return result;
  }
}
