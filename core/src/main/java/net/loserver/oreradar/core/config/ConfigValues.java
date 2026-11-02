package net.loserver.oreradar.core.config;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.loserver.oreradar.core.toml.TomlTable;

/** The typed value of every setting of a schema; immutable. */
final class ConfigValues {
    private final Map<Setting<?>, Object> values;

    private ConfigValues(Map<Setting<?>, Object> values) {
        this.values = values;
    }

    static ConfigValues read(TomlTable root, ConfigSchema schema, List<String> warnings) {
        Map<Setting<?>, Object> map = new IdentityHashMap<>();
        for (Setting<?> s : schema.settings()) {
            Object raw = root.get(s.table().split("\\.")) instanceof TomlTable t ? t.get(s.key()) : null;
            map.put(s, raw == null ? s.defaultValue() : s.read(raw, warnings));
        }
        return new ConfigValues(map);
    }

    static ConfigValues defaults(ConfigSchema schema) {
        Map<Setting<?>, Object> map = new IdentityHashMap<>();
        for (Setting<?> s : schema.settings()) map.put(s, s.defaultValue());
        return new ConfigValues(map);
    }

    @SuppressWarnings("unchecked")
    <T> T get(Setting<T> s) {
        Object v = values.get(s);
        if (v == null) throw new IllegalArgumentException(s.path() + " is not part of this config");
        return (T) v;
    }

    <T> ConfigValues with(Setting<T> s, T value) {
        if (!values.containsKey(s)) throw new IllegalArgumentException(s.path() + " is not part of this config");
        Map<Setting<?>, Object> map = new IdentityHashMap<>(values);
        map.put(s, s.sanitize(value));
        return new ConfigValues(map);
    }

    void writeTo(TomlTable root) {
        for (Map.Entry<Setting<?>, Object> e : values.entrySet()) put(root, e.getKey(), e.getValue());
    }

    @SuppressWarnings("unchecked")
    private static <T> void put(TomlTable root, Setting<T> s, Object value) {
        ConfigFile.table(root, s.table()).put(s.key(), s.toToml((T) value));
    }
}
