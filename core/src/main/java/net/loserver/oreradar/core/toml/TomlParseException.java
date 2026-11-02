package net.loserver.oreradar.core.toml;

/** A TOML syntax error at a 1-based line and column (columns count UTF-16 chars). */
public final class TomlParseException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int line;
    private final int column;
    private final String reason;

    public TomlParseException(int line, int column, String reason) {
        super("line " + line + ", column " + column + ": " + reason);
        this.line = line;
        this.column = column;
        this.reason = reason;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public String reason() {
        return reason;
    }
}
