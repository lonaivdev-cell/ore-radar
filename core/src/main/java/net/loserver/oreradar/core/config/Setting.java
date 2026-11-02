package net.loserver.oreradar.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import net.loserver.oreradar.core.designation.Designation;
import net.loserver.oreradar.core.designation.PackGroupFile;
import net.loserver.oreradar.core.toml.TomlTable;
import net.loserver.oreradar.core.toml.TomlWriter;

/**
 * One key of a config file: where it lives, its default, its constraint and its one-line comment.
 * Reading never fails: a value of the wrong type falls back to the default and an out-of-range
 * value is clamped, each with a warning. The subclasses expose their constraints so a settings
 * screen can be generated from the schema.
 */
public abstract class Setting<T> {
    private final String table;
    private final String key;
    private final T defaultValue;
    private final String description;

    Setting(String table, String key, T defaultValue, String description) {
        this.table = Objects.requireNonNull(table);
        this.key = Objects.requireNonNull(key);
        this.defaultValue = Objects.requireNonNull(defaultValue);
        this.description = Objects.requireNonNull(description);
    }

    /** Dotted path of the table holding the key, e.g. {@code features.beep}. */
    public final String table() {
        return table;
    }

    public final String key() {
        return key;
    }

    /** {@code table.key} */
    public final String path() {
        return table + "." + key;
    }

    public final T defaultValue() {
        return defaultValue;
    }

    /** What the key means, without the constraint or default. */
    public final String description() {
        return description;
    }

    /** Converts a TOML value, falling back or clamping with a warning; never null. */
    public abstract T read(Object raw, List<String> warnings);

    public abstract Object toToml(T value);

    /** The range or allowed values, "" when free-form. */
    public abstract String constraint();

    /** Applies the same checks a value from the file gets, for values set from code. */
    public final T sanitize(T value) {
        return read(toToml(value), new ArrayList<>());
    }

    /** The comment written above the key: description, constraint and default on one line. */
    public String comment() {
        String c = constraint();
        String def = TomlWriter.inline(toToml(defaultValue));
        StringBuilder sb = new StringBuilder(description);
        boolean showDefault = def.length() <= 40;
        if (!c.isEmpty() || showDefault) {
            sb.append(" (");
            if (!c.isEmpty()) sb.append(c);
            if (!c.isEmpty() && showDefault) sb.append("; ");
            if (showDefault) sb.append("default ").append(def);
            sb.append(')');
        }
        return sb.toString();
    }

    T fallback(Object raw, String expected, List<String> warnings) {
        warnings.add(path() + ": expected " + expected + ", found " + describe(raw) + "; using " + TomlWriter.inline(toToml(defaultValue)));
        return defaultValue;
    }

    static String describe(Object raw) {
        if (raw == null) return "nothing";
        if (raw instanceof TomlTable) return "a table";
        if (raw instanceof List<?>) return "an array";
        return TomlWriter.inline(raw);
    }

    /** A whole number in [min, max]. */
    public static final class Int extends Setting<Integer> {
        private final int min;
        private final int max;

        public Int(String table, String key, int defaultValue, int min, int max, String description) {
            super(table, key, defaultValue, description);
            if (min > max || defaultValue < min || defaultValue > max) throw new IllegalArgumentException(key);
            this.min = min;
            this.max = max;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        @Override
        public Integer read(Object raw, List<String> warnings) {
            long v;
            if (raw instanceof Long l) {
                v = l;
            } else if (raw instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e18) {
                v = d.longValue();
            } else {
                return fallback(raw, "a whole number", warnings);
            }
            if (v < min || v > max) {
                int c = (int) Math.max(min, Math.min(max, v));
                warnings.add(path() + " = " + v + " is outside " + constraint() + "; using " + c);
                return c;
            }
            return (int) v;
        }

        @Override
        public Object toToml(Integer value) {
            return value.longValue();
        }

        @Override
        public String constraint() {
            return min + ".." + max;
        }
    }

    /** A decimal number in [min, max]. */
    public static final class Real extends Setting<Double> {
        private final double min;
        private final double max;

        public Real(String table, String key, double defaultValue, double min, double max, String description) {
            super(table, key, defaultValue, description);
            if (!(min <= max) || defaultValue < min || defaultValue > max) throw new IllegalArgumentException(key);
            this.min = min;
            this.max = max;
        }

        public double min() {
            return min;
        }

        public double max() {
            return max;
        }

        @Override
        public Double read(Object raw, List<String> warnings) {
            double v;
            if (raw instanceof Double d && !d.isNaN()) v = d;
            else if (raw instanceof Long l) v = l;
            else return fallback(raw, "a number", warnings);
            if (v < min || v > max) {
                double c = Math.max(min, Math.min(max, v));
                warnings.add(path() + " = " + TomlWriter.inline(v) + " is outside " + constraint() + "; using " + TomlWriter.inline(c));
                return c;
            }
            return v;
        }

        @Override
        public Object toToml(Double value) {
            return value;
        }

        @Override
        public String constraint() {
            return TomlWriter.inline(min) + ".." + TomlWriter.inline(max);
        }
    }

    public static final class Bool extends Setting<Boolean> {
        public Bool(String table, String key, boolean defaultValue, String description) {
            super(table, key, defaultValue, description);
        }

        @Override
        public Boolean read(Object raw, List<String> warnings) {
            return raw instanceof Boolean b ? b : fallback(raw, "true or false", warnings);
        }

        @Override
        public Object toToml(Boolean value) {
            return value;
        }

        @Override
        public String constraint() {
            return "";
        }
    }

    /** A string that must pass a check, such as a colour or a resource id. */
    public static final class Text extends Setting<String> {
        private final Predicate<String> valid;
        private final String expected;

        /** @param expected what a valid value looks like, for the comment and warnings; "" for anything */
        public Text(String table, String key, String defaultValue, Predicate<String> valid, String expected, String description) {
            super(table, key, defaultValue, description);
            if (!valid.test(defaultValue)) throw new IllegalArgumentException(key);
            this.valid = valid;
            this.expected = expected;
        }

        public boolean isValid(String s) {
            return valid.test(s);
        }

        @Override
        public String read(Object raw, List<String> warnings) {
            return raw instanceof String s && valid.test(s) ? s : fallback(raw, expected.isEmpty() ? "a string" : expected, warnings);
        }

        @Override
        public Object toToml(String value) {
            return value;
        }

        @Override
        public String constraint() {
            return expected;
        }
    }

    /** One of an enum's constants, matched ignoring case and written in upper case. */
    public static final class Choice<E extends Enum<E>> extends Setting<E> {
        private final Class<E> type;

        public Choice(String table, String key, E defaultValue, String description) {
            super(table, key, defaultValue, description);
            this.type = defaultValue.getDeclaringClass();
        }

        public List<E> values() {
            return List.of(type.getEnumConstants());
        }

        @Override
        public E read(Object raw, List<String> warnings) {
            if (raw instanceof String s) {
                for (E e : type.getEnumConstants()) {
                    if (e.name().equals(s.trim().toUpperCase(Locale.ROOT))) return e;
                }
            }
            return fallback(raw, "one of " + constraint(), warnings);
        }

        @Override
        public Object toToml(E value) {
            return value.name();
        }

        @Override
        public String constraint() {
            E[] all = type.getEnumConstants();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < all.length; i++) {
                if (i > 0) sb.append(i == all.length - 1 ? " or " : ", ");
                sb.append(all[i].name());
            }
            return sb.toString();
        }
    }

    /** An array of strings, each parsed to an element; invalid entries are dropped with a warning. */
    public static final class ListOf<E> extends Setting<List<E>> {
        private final Function<String, E> parser;
        private final Function<E, String> formatter;

        /** @param parser returns null for an invalid entry */
        public ListOf(String table, String key, List<E> defaultValue, Function<String, E> parser, Function<E, String> formatter,
                      String description) {
            super(table, key, List.copyOf(defaultValue), description);
            this.parser = parser;
            this.formatter = formatter;
        }

        /** The element an entry parses to, or null if it is invalid. */
        public E parse(String entry) {
            return parser.apply(entry);
        }

        @Override
        public List<E> read(Object raw, List<String> warnings) {
            if (!(raw instanceof List<?> list)) return fallback(raw, "an array of strings", warnings);
            List<E> out = new ArrayList<>(list.size());
            for (Object o : list) {
                E e = o instanceof String s ? parser.apply(s) : null;
                if (e == null) warnings.add(path() + ": ignoring invalid entry " + describe(o));
                else out.add(e);
            }
            return Collections.unmodifiableList(out);
        }

        @Override
        public Object toToml(List<E> value) {
            List<Object> out = new ArrayList<>(value.size());
            for (E e : value) out.add(formatter.apply(e));
            return out;
        }

        @Override
        public String constraint() {
            return "";
        }
    }

    /**
     * An array of groups, each an array of designations. A group written as one comma-separated
     * string (older Vein Mining style) is accepted too.
     */
    public static final class Groups extends Setting<List<List<Designation>>> {
        public Groups(String table, String key, String description) {
            super(table, key, List.of(), description);
        }

        @Override
        public List<List<Designation>> read(Object raw, List<String> warnings) {
            if (!(raw instanceof List<?> list)) return fallback(raw, "an array of arrays", warnings);
            List<List<Designation>> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add(PackGroupFile.parseGroup(list.get(i), path() + " group " + (i + 1), warnings));
            }
            return Collections.unmodifiableList(out);
        }

        @Override
        public Object toToml(List<List<Designation>> value) {
            List<Object> out = new ArrayList<>(value.size());
            for (List<Designation> g : value) {
                List<Object> group = new ArrayList<>(g.size());
                for (Designation d : g) group.add(d.toString());
                out.add(group);
            }
            return out;
        }

        @Override
        public String constraint() {
            return "";
        }
    }
}
