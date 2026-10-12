package exchange.api;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.Map;
import java.util.NavigableMap;
import java.util.LinkedList;

/**
 * Lightweight JSON serialisation helpers.
 *
 * <p>This utility class avoids pulling in an external JSON library.
 * It is intentionally minimal: it covers only the object shapes produced
 * by the exchange API and validates that no unsafe characters are emitted.
 */
final class JsonUtil {

    private JsonUtil() {}

    // ── Primitive escaping ─────────────────────────────────────────────────────

    static String escapeString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ── Response helpers ───────────────────────────────────────────────────────

    static void writeJson(jakarta.servlet.http.HttpServletResponse resp, int status, String json)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
        try (PrintWriter pw = resp.getWriter()) {
            pw.write(json);
        }
    }

    static void writeError(jakarta.servlet.http.HttpServletResponse resp, int status, String message)
            throws IOException {
        writeJson(resp, status, "{\"error\":" + escapeString(message) + "}");
    }

    static void writeOk(jakarta.servlet.http.HttpServletResponse resp, String json)
            throws IOException {
        writeJson(resp, 200, json);
    }

    // ── Body reading ───────────────────────────────────────────────────────────

    static String readBody(jakarta.servlet.http.HttpServletRequest req) throws IOException {
        int len = req.getContentLength();
        if (len < 0) len = 8192;
        StringBuilder sb = new StringBuilder(len);
        try (var reader = req.getReader()) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    // ── Minimal JSON parser for request bodies ─────────────────────────────────

    /**
     * Extracts the string value of a JSON field from a flat (non-nested) JSON object.
     * Returns {@code null} if the field is absent or its value is JSON {@code null}.
     */
    static String extractString(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx += key.length();
        // skip whitespace and colon
        while (idx < json.length() && (json.charAt(idx) == ':' || json.charAt(idx) == ' ')) idx++;
        if (idx >= json.length()) return null;
        if (json.charAt(idx) == 'n') return null; // null value
        if (json.charAt(idx) != '"') return null;
        idx++; // skip opening quote
        StringBuilder val = new StringBuilder();
        while (idx < json.length()) {
            char c = json.charAt(idx++);
            if (c == '\\' && idx < json.length()) {
                char esc = json.charAt(idx++);
                switch (esc) {
                    case '"'  -> val.append('"');
                    case '\\' -> val.append('\\');
                    case 'n'  -> val.append('\n');
                    case 'r'  -> val.append('\r');
                    case 't'  -> val.append('\t');
                    default   -> { val.append('\\'); val.append(esc); }
                }
            } else if (c == '"') {
                break;
            } else {
                val.append(c);
            }
        }
        return val.toString();
    }

    /** Extracts a numeric (non-quoted) value as a string, or {@code null} if absent. */
    static String extractNumber(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx += key.length();
        while (idx < json.length() && (json.charAt(idx) == ':' || json.charAt(idx) == ' ')) idx++;
        if (idx >= json.length()) return null;
        if (json.charAt(idx) == 'n') return null;
        // For quoted numbers (e.g. "100.00") defer to extractString
        if (json.charAt(idx) == '"') return extractString(json, field);
        int start = idx;
        while (idx < json.length()) {
            char c = json.charAt(idx);
            if (c == ',' || c == '}' || c == ' ' || c == '\n' || c == '\r') break;
            idx++;
        }
        String num = json.substring(start, idx).strip();
        return num.isEmpty() ? null : num;
    }
}
