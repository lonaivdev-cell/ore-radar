package net.loserver.oreradar.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import net.loserver.oreradar.core.toml.TomlTable;

/**
 * The layout of a config file: its tables in order, each with a comment and its settings. A
 * {@link NamedTables} node is a table of user-named sub-tables, such as {@code [categories.<id>]}.
 */
public final class ConfigSchema {
    public sealed interface Node permits Table, NamedTables {
        String path();

        List<String> comment();
    }

    /** A fixed table. The settings' {@link Setting#table()} must equal the path. */
    public record Table(String path, List<String> comment, List<Setting<?>> settings) implements Node {
        public Table {
            comment = List.copyOf(comment);
            settings = List.copyOf(settings);
            for (Setting<?> s : settings) {
                if (!s.table().equals(path)) throw new IllegalArgumentException(s.path() + " is not in [" + path + "]");
            }
        }
    }

    /**
     * A table whose sub-tables the user names. Each member has {@code memberKeys}; a missing key
     * takes its value from {@code memberDefaults(id)}. When the whole table is missing, it is
     * written as {@code defaults}, followed by the {@code examples} lines as comments (more members
     * the user can uncomment).
     */
    public record NamedTables(String path, List<String> comment, List<String> memberKeys,
                              TomlTable defaults, Function<String, TomlTable> memberDefaults, List<String> examples) implements Node {
        public NamedTables {
            comment = List.copyOf(comment);
            memberKeys = List.copyOf(memberKeys);
            examples = List.copyOf(examples);
        }
    }

    private final List<String> fileComment;
    private final List<Node> nodes;

    /** @param fileComment lines written at the top of a new file, above the first table */
    public ConfigSchema(List<String> fileComment, List<Node> nodes) {
        this.fileComment = List.copyOf(fileComment);
        this.nodes = List.copyOf(nodes);
    }

    public List<String> fileComment() {
        return fileComment;
    }

    public List<Node> nodes() {
        return nodes;
    }

    /** Every setting in file order, e.g. to build a settings screen. */
    public List<Setting<?>> settings() {
        List<Setting<?>> out = new ArrayList<>();
        for (Node n : nodes) {
            if (n instanceof Table t) out.addAll(t.settings());
        }
        return Collections.unmodifiableList(out);
    }

    /** True if some node lives at this exact dotted path. */
    boolean isNode(String path) {
        for (Node n : nodes) {
            if (n.path().equals(path)) return true;
        }
        return false;
    }
}
