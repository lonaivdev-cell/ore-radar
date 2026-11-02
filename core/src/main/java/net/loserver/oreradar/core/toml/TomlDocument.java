package net.loserver.oreradar.core.toml;

import java.util.List;

/** A parsed TOML file: the root table plus the comment lines after the last key. */
public final class TomlDocument {
    private final TomlTable root;
    private List<String> footer = List.of();

    public TomlDocument() {
        this(new TomlTable());
    }

    public TomlDocument(TomlTable root) {
        this.root = root;
    }

    public TomlTable root() {
        return root;
    }

    /** Comment lines at the end of the file that precede no key or table. */
    public List<String> footer() {
        return footer;
    }

    public void setFooter(List<String> lines) {
        footer = List.copyOf(lines);
    }
}
