package net.loserver.oreradar.core.toml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.loserver.oreradar.core.util.AtomicFiles;

/**
 * Writes a {@link TomlDocument}: plain keys first, then each sub-table under its own [header],
 * comments in place. Short arrays stay on one line; long ones and arrays of arrays get one element
 * per line. Output uses LF line endings and is UTF-8 when written to a file.
 */
public final class TomlWriter {
    private static final int INLINE_ARRAY_LIMIT = 100;
    private static final String INDENT = "    ";

    private TomlWriter() {}

    public static String write(TomlDocument doc) {
        StringBuilder sb = new StringBuilder();
        writeTable(sb, doc.root(), List.of(), new ArrayList<>(), true);
        if (!doc.footer().isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            comments(sb, doc.footer());
        }
        return sb.toString();
    }

    /** Writes the document to a file atomically. */
    public static void write(TomlDocument doc, Path file) throws IOException {
        AtomicFiles.write(file, write(doc).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeTable(StringBuilder sb, TomlTable t, List<String> keyComments, List<String> path, boolean root) {
        List<String> headerComments = new ArrayList<>(keyComments);
        headerComments.addAll(t.comments());
        if (!root && needsHeader(t, headerComments)) {
            if (sb.length() > 0) sb.append('\n');
            comments(sb, headerComments);
            sb.append('[');
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) sb.append('.');
                sb.append(key(path.get(i)));
            }
            sb.append(']');
            trailing(sb, t.trailingComment());
            sb.append('\n');
        }
        for (String k : t.keySet()) {
            Object v = t.get(k);
            if (v instanceof TomlTable) continue;
            comments(sb, t.comments(k));
            sb.append(key(k)).append(" = ");
            value(sb, v);
            trailing(sb, t.trailingComment(k));
            sb.append('\n');
        }
        for (String k : t.keySet()) {
            if (!(t.get(k) instanceof TomlTable sub)) continue;
            path.add(k);
            writeTable(sb, sub, t.comments(k), path, false);
            path.remove(path.size() - 1);
        }
    }

    /** A table that only holds sub-tables needs no header of its own, unless it carries comments. */
    private static boolean needsHeader(TomlTable t, List<String> comments) {
        if (t.isEmpty() || !comments.isEmpty() || t.trailingComment() != null) return true;
        for (String k : t.keySet()) {
            if (!(t.get(k) instanceof TomlTable)) return true;
        }
        return false;
    }

    private static void comments(StringBuilder sb, List<String> lines) {
        for (String c : lines) sb.append('#').append(c).append('\n');
    }

    private static void trailing(StringBuilder sb, String comment) {
        if (comment != null) sb.append(" #").append(comment);
    }

    private static void value(StringBuilder sb, Object v) {
        if (v instanceof List<?> list && !list.isEmpty()) {
            String flat = inline(v);
            if (flat.length() <= INLINE_ARRAY_LIMIT && !hasNested(list)) {
                sb.append(flat);
            } else {
                sb.append("[\n");
                for (Object o : list) sb.append(INDENT).append(inline(o)).append(",\n");
                sb.append(']');
            }
            return;
        }
        sb.append(inline(v));
    }

    private static boolean hasNested(List<?> list) {
        for (Object o : list) {
            if ((o instanceof List<?> l && !l.isEmpty()) || (o instanceof TomlTable t && !t.isEmpty())) return true;
        }
        return false;
    }

    /** One-line form of any value. */
    public static String inline(Object v) {
        if (v instanceof String str) return quote(str);
        if (v instanceof Double d) return formatDouble(d);
        if (v instanceof Long || v instanceof Boolean) return v.toString();
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(inline(list.get(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof TomlTable t) {
            if (t.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{ ");
            boolean first = true;
            for (String k : t.keySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(key(k)).append(" = ").append(inline(t.get(k)));
            }
            return sb.append(" }").toString();
        }
        return inline(TomlTable.normalize(v));
    }

    static String formatDouble(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        if (d == 0) return 1 / d < 0 ? "-0.0" : "0.0";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return (long) d + ".0";
        return Double.toString(d);
    }

    /** A key as written: bare when it can be, else a quoted string. */
    public static String key(String k) {
        if (k.isEmpty()) return "\"\"";
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            boolean bare = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!bare) return quote(k);
        }
        return k;
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7F) sb.append(String.format("\\u%04X", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
