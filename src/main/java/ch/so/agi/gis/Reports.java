package ch.so.agi.gis;

import java.nio.file.Path;

final class Reports {
  static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  static Path write(Path file, String title, Object body) {
    Json.write(
        file,
        "<!doctype html><html lang=\"de\"><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>"
            + escape(title)
            + "</title><style>body{font:16px system-ui;max-width:1100px;margin:3rem auto;padding:1rem;background:#fafafa;color:#182b35}pre{white-space:pre-wrap;overflow-wrap:anywhere;padding:1rem;background:white;border:1px solid #ccd7dd;border-radius:8px}h1{font-size:1.6rem}</style><h1>"
            + escape(title)
            + "</h1><pre>"
            + escape(Json.pretty(body))
            + "</pre></html>");
    return file;
  }
}
