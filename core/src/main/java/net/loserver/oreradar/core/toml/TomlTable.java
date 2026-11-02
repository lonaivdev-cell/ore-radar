package net.loserver.oreradar.core.toml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An ordered TOML table. Values are {@code String}, {@code Long}, {@code Double}, {@code Boolean},
 * an unmodifiable {@code List} of values, or a nested {@code TomlTable}. Each key carries the comment
 * lines written above it and an optional comment after the value, so a file can be rewritten without
 * losing what the user wrote. A comment is stored as the text after {@code #}.
 *
 * <p>{@link #equals} compares keys and values only, never comments or formatting.
 */
public final class TomlTable {
    private static final class Entry {
        Object value;
        List<String> comments = List.of();
        String trailingComment;

        Entry(Object value) {
            this.value = value;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private List<String> comments = List.of();
    private String trailingComment;
    /** Reader bookkeeping: declared by its own [header], or written inline as { ... }. */
    boolean explicitHeader;
    boolean inline;

    public Object get(String key) {
        Entry e = entries.get(key);
        return e == null ? null : e.value;
    }

    /** The value at a path of keys (e.g. "features", "beep", "enabled"), or null. */
    public Object get(String... path) {
        TomlTable t = this;
        for (int i = 0; i < path.length - 1; i++) {
            t = t.table(path[i]);
            if (t == null) return null;
        }
        return t.get(path[path.length - 1]);
    }

    /** The sub-table under this key, or null if absent or not a table. */
    public TomlTable table(String key) {
        return get(key) instanceof TomlTable t ? t : null;
    }

    /** The sub-table under this key, created (at the end) if absent. Fails if the key holds a non-table. */
    public TomlTable getOrCreateTable(String key) {
        Object v = get(key);
        if (v instanceof TomlTable t) return t;
        if (v != null) throw new IllegalStateException("key " + key + " holds a value, not a table");
        TomlTable t = new TomlTable();
        put(key, t);
        return t;
    }

    public boolean contains(String key) {
        return entries.containsKey(key);
    }

    /** Keys in file order; unmodifiable. */
    public Set<String> keySet() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Sets a value. An existing key keeps its place and its comments; a new key goes at the end. */
    public void put(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Object v = normalize(value);
        Entry e = entries.get(key);
        if (e == null) entries.put(key, new Entry(v));
        else e.value = v;
    }

    /** Sets a value and the comment lines above it. */
    public void put(String key, Object value, List<String> comments) {
        put(key, value);
        setComments(key, comments);
    }

    public Object remove(String key) {
        Entry e = entries.remove(key);
        return e == null ? null : e.value;
    }

    /** Comment lines above the key (text after '#'), empty if none or if the key is absent. */
    public List<String> comments(String key) {
        Entry e = entries.get(key);
        return e == null ? List.of() : e.comments;
    }

    public void setComments(String key, List<String> lines) {
        Entry e = entries.get(key);
        if (e == null) throw new IllegalArgumentException("no key " + key);
        e.comments = List.copyOf(lines);
    }

    /** The comment after the value on the same line, or null. */
    public String trailingComment(String key) {
        Entry e = entries.get(key);
        return e == null ? null : e.trailingComment;
    }

    public void setTrailingComment(String key, String comment) {
        Entry e = entries.get(key);
        if (e == null) throw new IllegalArgumentException("no key " + key);
        e.trailingComment = comment;
    }

    /** Comment lines above this table's [header]. */
    public List<String> comments() {
        return comments;
    }

    public void setComments(List<String> lines) {
        comments = List.copyOf(lines);
    }

    /** The comment after this table's [header] on the same line, or null. */
    public String trailingComment() {
        return trailingComment;
    }

    public void setTrailingComment(String comment) {
        trailingComment = comment;
    }

    /** Copies one key (value, comments and trailing comment) from another table, replacing any here. */
    public void copyEntry(TomlTable from, String key) {
        Entry src = from.entries.get(key);
        if (src == null) throw new IllegalArgumentException("no key " + key);
        Entry e = new Entry(src.value);
        e.comments = src.comments;
        e.trailingComment = src.trailingComment;
        entries.put(key, e);
    }

    /** Converts Java values to the TOML model: Integer to Long, Float to Double, lists copied. */
    static Object normalize(Object value) {
        if (value instanceof String || value instanceof Long || value instanceof Double
                || value instanceof Boolean || value instanceof TomlTable) {
            return value;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float f) return f.doubleValue();
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(normalize(o));
            return Collections.unmodifiableList(out);
        }
        throw new IllegalArgumentException("not a TOML value: " + (value == null ? "null" : value.getClass().getName()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TomlTable t) || t.entries.size() != entries.size()) return false;
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Entry other = t.entries.get(e.getKey());
            if (other == null || !e.getValue().value.equals(other.value)) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (Map.Entry<String, Entry> e : entries.entrySet()) h += e.getKey().hashCode() ^ e.getValue().value.hashCode();
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue().value);
        }
        return sb.append('}').toString();
    }
}
