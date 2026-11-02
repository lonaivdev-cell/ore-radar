package net.loserver.oreradar.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import net.loserver.oreradar.core.toml.TomlDocument;
import net.loserver.oreradar.core.toml.TomlParseException;
import net.loserver.oreradar.core.toml.TomlReader;
import net.loserver.oreradar.core.toml.TomlTable;
import net.loserver.oreradar.core.toml.TomlWriter;
import net.loserver.oreradar.core.util.AtomicFiles;

/**
 * Loads and saves config files against a schema without losing what the user wrote: values,
 * comments and unknown keys survive, and the file is rewritten only when it is missing, lacks keys
 * or is migrated. A key the user has keeps the user's comment; a key added gets the schema's.
 */
final class ConfigFile {
    /** The schema overlaid with a user file, in schema order; unknown keys at the end of their table. */
    record Merged(TomlDocument doc, List<String> missing, List<String> unknown) {}

    /** Turns an old file layout into a current one; returns null when the file needs no migration. */
    interface Migration {
        TomlDocument migrate(TomlDocument parsed, Path file, List<String> warnings) throws IOException;
    }

    private ConfigFile() {}

    static <C> LoadResult<C> load(Path file, ConfigSchema schema, C previous, BiFunction<TomlTable, List<String>, C> build,
                                  Migration migration) {
        List<String> warnings = new ArrayList<>();
        boolean exists = Files.exists(file);
        boolean migrated = false;
        TomlDocument user = new TomlDocument();
        if (exists) {
            try {
                user = TomlReader.read(file);
            } catch (TomlParseException e) {
                warnings.add(file.getFileName() + ", " + e.getMessage() + "; keeping the previous settings");
                return new LoadResult<>(previous, warnings, false, true);
            } catch (IOException e) {
                warnings.add("could not read " + file + " (" + e + "); keeping the previous settings");
                return new LoadResult<>(previous, warnings, false, true);
            }
            if (migration != null) {
                try {
                    TomlDocument m = migration.migrate(user, file, warnings);
                    if (m != null) {
                        user = m;
                        migrated = true;
                    }
                } catch (IOException e) {
                    warnings.add("could not migrate " + file + " (" + e + "); keeping the previous settings");
                    return new LoadResult<>(previous, warnings, false, true);
                }
            }
        }
        Merged merged = merge(user, schema);
        for (String key : merged.unknown()) warnings.add("unknown key " + key + " is kept in the file but ignored");
        C config = build.apply(merged.doc().root(), warnings);
        boolean written = false;
        if (!exists || migrated || !merged.missing().isEmpty()) {
            try {
                TomlWriter.write(merged.doc(), file);
                written = true;
            } catch (IOException e) {
                warnings.add("could not write " + file + " (" + e + ")");
            }
        }
        return new LoadResult<>(config, warnings, written, false);
    }

    /**
     * Writes values into the file, keeping its comments and unknown keys. A file that no longer
     * parses is first copied to a .bak, then replaced.
     */
    static void save(Path file, ConfigSchema schema, Consumer<TomlTable> fill) throws IOException {
        TomlDocument user = new TomlDocument();
        if (Files.exists(file)) {
            try {
                user = TomlReader.read(file);
            } catch (TomlParseException e) {
                AtomicFiles.backup(file);
            }
        }
        Merged merged = merge(user, schema);
        fill.accept(merged.doc().root());
        TomlWriter.write(merged.doc(), file);
    }

    static Merged merge(TomlDocument user, ConfigSchema schema) {
        TomlDocument out = new TomlDocument();
        out.setFooter(user.footer());
        List<String> missing = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        TomlTable uroot = user.root();
        boolean first = true;
        // Comment lines below a table's last key are read back as the comment of whatever follows,
        // so the examples of named tables just written go above the next node, or into the footer.
        List<String> examples = List.of();
        for (ConfigSchema.Node node : schema.nodes()) {
            TomlTable o = table(out.root(), node.path());
            Object uv = uroot.get(node.path().split("\\."));
            TomlTable u = uv instanceof TomlTable t ? t : null;
            if (u != null) {
                o.setComments(u.comments());
                o.setTrailingComment(u.trailingComment());
            } else {
                List<String> c = new ArrayList<>();
                if (first) for (String line : schema.fileComment()) c.add(commentLine(line));
                c.addAll(examples);
                for (String line : node.comment()) c.add(commentLine(line));
                o.setComments(c);
            }
            first = false;
            examples = List.of();
            if (node instanceof ConfigSchema.Table spec) {
                mergeTable(spec, u, o, missing, unknown, schema);
            } else if (mergeNamed((ConfigSchema.NamedTables) node, uv, o, missing, unknown)) {
                examples = ((ConfigSchema.NamedTables) node).examples().stream().map(ConfigFile::commentLine).toList();
            }
        }
        if (out.footer().isEmpty()) out.setFooter(examples);
        Set<String> roots = new HashSet<>();
        for (ConfigSchema.Node node : schema.nodes()) roots.add(node.path().split("\\.")[0]);
        for (String k : uroot.keySet()) {
            if (roots.contains(k)) continue;
            out.root().copyEntry(uroot, k);
            unknown.add(k);
        }
        return new Merged(out, missing, unknown);
    }

    private static void mergeTable(ConfigSchema.Table spec, TomlTable u, TomlTable o, List<String> missing, List<String> unknown,
                                   ConfigSchema schema) {
        Set<String> known = new HashSet<>();
        for (Setting<?> s : spec.settings()) {
            known.add(s.key());
            if (u != null && u.contains(s.key())) {
                o.copyEntry(u, s.key());
            } else {
                o.put(s.key(), toToml(s), List.of(commentLine(s.comment())));
                missing.add(s.path());
            }
        }
        if (u == null) return;
        for (String k : u.keySet()) {
            if (known.contains(k) || schema.isNode(spec.path() + "." + k)) continue;
            o.copyEntry(u, k);
            unknown.add(spec.path() + "." + k);
        }
    }

    /** @return true if the table was missing and its defaults were written */
    private static boolean mergeNamed(ConfigSchema.NamedTables spec, Object uv, TomlTable o, List<String> missing, List<String> unknown) {
        if (!(uv instanceof TomlTable u)) {
            // Absent, or not even a table: write the defaults.
            TomlTable defaults = spec.defaults();
            for (String id : defaults.keySet()) o.copyEntry(defaults, id);
            missing.add(spec.path());
            return true;
        }
        for (String id : u.keySet()) {
            if (!(u.get(id) instanceof TomlTable member)) {
                o.copyEntry(u, id);
                unknown.add(spec.path() + "." + id);
                continue;
            }
            TomlTable om = new TomlTable();
            o.put(id, om, u.comments(id));
            om.setComments(member.comments());
            om.setTrailingComment(member.trailingComment());
            TomlTable defaults = spec.memberDefaults().apply(id);
            for (String k : spec.memberKeys()) {
                if (member.contains(k)) {
                    om.copyEntry(member, k);
                } else {
                    om.put(k, defaults.get(k));
                    missing.add(spec.path() + "." + id + "." + k);
                }
            }
            for (String k : member.keySet()) {
                if (spec.memberKeys().contains(k)) continue;
                om.copyEntry(member, k);
                unknown.add(spec.path() + "." + id + "." + k);
            }
        }
        return false;
    }

    private static <T> Object toToml(Setting<T> s) {
        return s.toToml(s.defaultValue());
    }

    /** The table at a dotted path below root, created (in order) where missing. */
    static TomlTable table(TomlTable root, String dottedPath) {
        TomlTable t = root;
        for (String k : dottedPath.split("\\.")) t = t.getOrCreateTable(k);
        return t;
    }

    /** Schema text as a stored comment: "# text" in the file. */
    static String commentLine(String text) {
        return text.isEmpty() ? "" : " " + text;
    }
}
