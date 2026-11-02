package net.loserver.oreradar.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal strict JSON reader for datapack files such as {@code groups.json}, and a writer for
 * core's own JSON files ({@code oreradar-client-data.json}). The reader turns objects into
 * {@code Map<String, Object>} (key order kept), arrays into {@code List<Object>}, numbers into
 * {@code Double}, plus {@code String}, {@code Boolean} and {@code null}; the writer takes the same
 * shapes back, with any {@code Number}.
 */
public final class Json {
    private static final int MAX_DEPTH = 64;

    /** A syntax error at a 1-based line and column. */
    public static final class JsonException extends Exception {
        private static final long serialVersionUID = 1L;

        public JsonException(int line, int column, String reason) {
            super("line " + line + ", column " + column + ": " + reason);
        }
    }

    private final String s;
    private int pos;
    private int depth;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) throws JsonException {
        Json p = new Json(text);
        if (text.startsWith("﻿")) p.pos = 1;
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos < text.length()) throw p.error("unexpected text after the value");
        return v;
    }

    /**
     * Writes a value as JSON, indented by two spaces, ending in a newline.
     *
     * @throws IllegalArgumentException for a type the reader would not produce, a non-string map
     *                                  key or a non-finite number
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, Object v, int indent) {
        if (v == null || v instanceof Boolean) {
            out.append(v);
        } else if (v instanceof String str) {
            quote(out, str);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) throw new IllegalArgumentException("JSON has no " + d);
            // Whole numbers without ".0", so counts read as counts.
            out.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : n.toString());
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            String sep = "\n";
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String key)) throw new IllegalArgumentException("JSON keys are strings, not " + e.getKey());
                out.append(sep).append("  ".repeat(indent + 1));
                quote(out, key);
                out.append(": ");
                write(out, e.getValue(), indent + 1);
                sep = ",\n";
            }
            out.append('\n').append("  ".repeat(indent)).append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            String sep = "\n";
            for (Object item : l) {
                out.append(sep).append("  ".repeat(indent + 1));
                write(out, item, indent + 1);
                sep = ",\n";
            }
            out.append('\n').append("  ".repeat(indent)).append(']');
        } else {
            throw new IllegalArgumentException("cannot write " + v.getClass().getName() + " as JSON");
        }
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private Object value() throws JsonException {
        if (pos >= s.length()) throw error("unexpected end of input");
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            default:
                if (literal("true")) return Boolean.TRUE;
                if (literal("false")) return Boolean.FALSE;
                if (literal("null")) return null;
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() throws JsonException {
        enter();
        pos++;
        Map<String, Object> out = new LinkedHashMap<>();
        ws();
        if (peek() == '}') {
            pos++;
            depth--;
            return out;
        }
        while (true) {
            ws();
            if (peek() != '"') throw error("expected a key");
            String key = string();
            ws();
            if (peek() != ':') throw error("expected ':'");
            pos++;
            ws();
            if (out.containsKey(key)) throw error("duplicate key \"" + key + "\"");
            out.put(key, value());
            ws();
            char c = peek();
            pos++;
            if (c == '}') break;
            if (c != ',') throw error("expected ',' or '}'");
        }
        depth--;
        return out;
    }

    private List<Object> array() throws JsonException {
        enter();
        pos++;
        List<Object> out = new ArrayList<>();
        ws();
        if (peek() == ']') {
            pos++;
            depth--;
            return out;
        }
        while (true) {
            ws();
            out.add(value());
            ws();
            char c = peek();
            pos++;
            if (c == ']') break;
            if (c != ',') throw error("expected ',' or ']'");
        }
        depth--;
        return Collections.unmodifiableList(out);
    }

    private String string() throws JsonException {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) throw error("unterminated string");
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c < 0x20) throw error("control character in a string");
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) throw error("unterminated escape");
            char e = s.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > s.length()) throw error("incomplete \\u escape");
                    int cp = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos++), 16);
                        if (d < 0) throw error("invalid \\u escape");
                        cp = cp * 16 + d;
                    }
                    sb.append((char) cp);
                }
                default -> throw error("invalid escape '\\" + e + "'");
            }
        }
    }

    private Double number() throws JsonException {
        int start = pos;
        if (peek() == '-') pos++;
        if (peek() == '0') {
            pos++;
        } else if (!digits()) {
            throw error("invalid number");
        }
        if (peek() == '.') {
            pos++;
            if (!digits()) throw error("invalid number");
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') pos++;
            if (!digits()) throw error("invalid number");
        }
        return Double.parseDouble(s.substring(start, pos));
    }

    private boolean digits() {
        int start = pos;
        while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
        return pos > start;
    }

    private boolean literal(String w) {
        if (!s.startsWith(w, pos)) return false;
        pos += w.length();
        return true;
    }

    private void enter() throws JsonException {
        if (++depth > MAX_DEPTH) throw error("nested too deeply");
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private void ws() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            pos++;
        }
    }

    private JsonException error(String reason) {
        int line = 1, lineStart = 0;
        for (int i = 0; i < Math.min(pos, s.length()); i++) {
            if (s.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new JsonException(line, pos - lineStart + 1, reason);
    }
}
