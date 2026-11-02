package net.loserver.oreradar.core.toml;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the TOML subset Ore Radar's configs use, and a little more so hand-edited files parse:
 * tables and dotted tables, dotted keys, bare/basic/literal keys, all four string forms with
 * escapes, integers (decimal, hex, octal, binary, underscores), floats (incl. inf and nan),
 * booleans, arrays (nested, multi-line, with comments) and inline tables.
 * Dates and arrays of tables ({@code [[x]]}) are rejected with a clear error.
 *
 * <p>Comments directly above a key or [header] are kept with it, as is a comment after a value.
 * Comments inside arrays and inline tables are skipped.
 */
public final class TomlReader {
    private static final int MAX_DEPTH = 64;
    private static final Pattern DEC_INT = Pattern.compile("[+-]?(0|[1-9](_?[0-9])*)");
    private static final Pattern FLOAT = Pattern.compile(
            "[+-]?(0|[1-9](_?[0-9])*)(\\.[0-9](_?[0-9])*)?([eE][+-]?[0-9](_?[0-9])*)?");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F](_?[0-9a-fA-F])*");
    private static final Pattern OCT = Pattern.compile("[0-7](_?[0-7])*");
    private static final Pattern BIN = Pattern.compile("[01](_?[01])*");
    private static final Pattern DATE_START = Pattern.compile("[0-9]{4}-[0-9]{2}");

    private final String s;
    private final int n;
    private int pos;
    private int line = 1;
    private int lineStart;
    private int depth;

    private TomlReader(String text) {
        s = text;
        n = text.length();
        if (n > 0 && text.charAt(0) == '﻿') {
            pos = 1;
            lineStart = 1;
        }
    }

    public static TomlDocument parse(String text) throws TomlParseException {
        return new TomlReader(text).document();
    }

    /** Reads a UTF-8 file. Malformed UTF-8 is an {@link IOException}, a syntax error a {@link TomlParseException}. */
    public static TomlDocument read(Path file) throws IOException, TomlParseException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(file)))
                .toString();
        return parse(text);
    }

    private TomlDocument document() throws TomlParseException {
        TomlTable root = new TomlTable();
        TomlTable current = root;
        List<String> pending = new ArrayList<>();
        while (true) {
            skipBlank();
            if (pos >= n) break;
            char c = s.charAt(pos);
            if (c == '#') {
                pending.add(comment());
                expectLineEnd();
            } else if (isNewline(c)) {
                newline();
            } else if (c == '[') {
                current = header(root, pending);
                pending = new ArrayList<>();
            } else {
                keyValue(current, pending);
                pending = new ArrayList<>();
            }
        }
        TomlDocument doc = new TomlDocument(root);
        doc.setFooter(pending);
        return doc;
    }

    private TomlTable header(TomlTable root, List<String> comments) throws TomlParseException {
        int hLine = line, hCol = col();
        pos++;
        if (pos < n && s.charAt(pos) == '[') {
            throw errorAt(hLine, hCol, "arrays of tables ([[...]]) are not supported");
        }
        List<String> path = keyPath();
        skipBlank();
        expect(']', "expected ']' to close the table header");
        String trailing = trailingComment();
        expectLineEnd();
        TomlTable t = root;
        for (String k : path) {
            Object v = t.get(k);
            if (v == null) {
                TomlTable created = new TomlTable();
                t.put(k, created);
                t = created;
            } else if (v instanceof TomlTable sub && !sub.inline) {
                t = sub;
            } else {
                throw errorAt(hLine, hCol, "[" + String.join(".", path) + "]: key '" + k + "' already holds a value");
            }
        }
        if (t.explicitHeader) throw errorAt(hLine, hCol, "table [" + String.join(".", path) + "] is defined twice");
        t.explicitHeader = true;
        t.setComments(comments);
        t.setTrailingComment(trailing);
        return t;
    }

    private void keyValue(TomlTable table, List<String> comments) throws TomlParseException {
        int kLine = line, kCol = col();
        List<String> path = keyPath();
        skipBlank();
        expect('=', "expected '=' after the key");
        skipBlank();
        Object value = value();
        String trailing = trailingComment();
        expectLineEnd();
        String key = place(table, path, value, kLine, kCol);
        TomlTable owner = owner(table, path);
        owner.setComments(key, comments);
        owner.setTrailingComment(key, trailing);
    }

    /** Puts value at a dotted path below table, creating intermediate tables; returns the last key. */
    private String place(TomlTable table, List<String> path, Object value, int kLine, int kCol) throws TomlParseException {
        TomlTable t = table;
        for (int i = 0; i < path.size() - 1; i++) {
            String k = path.get(i);
            Object v = t.get(k);
            if (v == null) {
                TomlTable created = new TomlTable();
                created.inline = table.inline;
                t.put(k, created);
                t = created;
            } else if (v instanceof TomlTable sub) {
                t = sub;
            } else {
                throw errorAt(kLine, kCol, "key '" + String.join(".", path.subList(0, i + 1)) + "' already holds a value");
            }
        }
        String last = path.get(path.size() - 1);
        if (t.contains(last)) throw errorAt(kLine, kCol, "duplicate key '" + String.join(".", path) + "'");
        t.put(last, value);
        return last;
    }

    private static TomlTable owner(TomlTable table, List<String> path) {
        TomlTable t = table;
        for (int i = 0; i < path.size() - 1; i++) t = t.table(path.get(i));
        return t;
    }

    private List<String> keyPath() throws TomlParseException {
        List<String> parts = new ArrayList<>(2);
        while (true) {
            skipBlank();
            parts.add(simpleKey());
            skipBlank();
            if (pos < n && s.charAt(pos) == '.') {
                pos++;
            } else {
                return parts;
            }
        }
    }

    private String simpleKey() throws TomlParseException {
        if (pos >= n) throw error("expected a key");
        char c = s.charAt(pos);
        if (c == '"') {
            if (s.startsWith("\"\"\"", pos)) throw error("a key cannot be a multi-line string");
            return basicString();
        }
        if (c == '\'') {
            if (s.startsWith("'''", pos)) throw error("a key cannot be a multi-line string");
            return literalString();
        }
        int start = pos;
        while (pos < n && isBareKeyChar(s.charAt(pos))) pos++;
        if (pos == start) throw error(isNewline(c) ? "expected a key, found the end of the line" : "expected a key, found '" + c + "'");
        return s.substring(start, pos);
    }

    private Object value() throws TomlParseException {
        if (pos >= n || isNewline(s.charAt(pos))) throw error("expected a value");
        char c = s.charAt(pos);
        switch (c) {
            case '"':
                return s.startsWith("\"\"\"", pos) ? multilineBasicString() : basicString();
            case '\'':
                return s.startsWith("'''", pos) ? multilineLiteralString() : literalString();
            case '[':
                return array();
            case '{':
                return inlineTable();
            default:
                if (word("true")) return Boolean.TRUE;
                if (word("false")) return Boolean.FALSE;
                return number();
        }
    }

    private boolean word(String w) {
        int end = pos + w.length();
        if (!s.startsWith(w, pos) || (end < n && isBareKeyChar(s.charAt(end)))) return false;
        pos = end;
        return true;
    }

    private Object number() throws TomlParseException {
        int start = pos, startCol = col();
        while (pos < n && isTokenChar(s.charAt(pos))) pos++;
        String tok = s.substring(start, pos);
        if (tok.isEmpty()) throw error("expected a value, found '" + s.charAt(pos) + "'");
        switch (tok) {
            case "inf":
            case "+inf":
                return Double.POSITIVE_INFINITY;
            case "-inf":
                return Double.NEGATIVE_INFINITY;
            case "nan":
            case "+nan":
            case "-nan":
                return Double.NaN;
            default:
                break;
        }
        if (tok.indexOf(':') >= 0 || DATE_START.matcher(tok).lookingAt()) {
            throw errorAt(line, startCol, "dates and times are not supported");
        }
        try {
            if (tok.startsWith("0x")) return radix(tok, HEX, 16, startCol);
            if (tok.startsWith("0o")) return radix(tok, OCT, 8, startCol);
            if (tok.startsWith("0b")) return radix(tok, BIN, 2, startCol);
            if (DEC_INT.matcher(tok).matches()) return Long.parseLong(tok.replace("_", ""));
            if (FLOAT.matcher(tok).matches()) return Double.parseDouble(tok.replace("_", ""));
        } catch (NumberFormatException e) {
            throw errorAt(line, startCol, "number out of range: " + tok);
        }
        throw errorAt(line, startCol, "invalid value '" + tok + "' (strings need quotes)");
    }

    private Long radix(String tok, Pattern digits, int radix, int startCol) throws TomlParseException {
        String body = tok.substring(2);
        if (!digits.matcher(body).matches()) throw errorAt(line, startCol, "invalid number '" + tok + "'");
        return Long.parseLong(body.replace("_", ""), radix);
    }

    private String basicString() throws TomlParseException {
        int sLine = line, sCol = col();
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= n) throw errorAt(sLine, sCol, "unterminated string");
            char c = s.charAt(pos);
            if (c == '"') {
                pos++;
                return sb.toString();
            }
            if (c == '\\') {
                escape(sb);
            } else if (isNewline(c)) {
                throw errorAt(sLine, sCol, "unterminated string (use \"\"\" for text over several lines)");
            } else {
                checkControl(c);
                sb.append(c);
                pos++;
            }
        }
    }

    private String multilineBasicString() throws TomlParseException {
        int sLine = line, sCol = col();
        pos += 3;
        if (pos < n && isNewline(s.charAt(pos))) newline();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= n) throw errorAt(sLine, sCol, "unterminated string");
            char c = s.charAt(pos);
            if (s.startsWith("\"\"\"", pos)) {
                closeMultiline(sb, '"');
                return sb.toString();
            }
            if (c == '\\') {
                int p = pos + 1;
                while (p < n && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
                if (p < n && isNewline(s.charAt(p))) {
                    // Line-ending backslash: drop the newline and the whitespace that follows.
                    pos = p;
                    while (pos < n) {
                        char w = s.charAt(pos);
                        if (w == ' ' || w == '\t') pos++;
                        else if (isNewline(w)) newline();
                        else break;
                    }
                } else {
                    escape(sb);
                }
            } else if (isNewline(c)) {
                newline();
                sb.append('\n');
            } else {
                checkControl(c);
                sb.append(c);
                pos++;
            }
        }
    }

    private String literalString() throws TomlParseException {
        int sLine = line, sCol = col();
        pos++;
        int start = pos;
        while (true) {
            if (pos >= n || isNewline(s.charAt(pos))) throw errorAt(sLine, sCol, "unterminated string");
            char c = s.charAt(pos);
            if (c == '\'') {
                String out = s.substring(start, pos);
                pos++;
                return out;
            }
            checkControl(c);
            pos++;
        }
    }

    private String multilineLiteralString() throws TomlParseException {
        int sLine = line, sCol = col();
        pos += 3;
        if (pos < n && isNewline(s.charAt(pos))) newline();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= n) throw errorAt(sLine, sCol, "unterminated string");
            char c = s.charAt(pos);
            if (s.startsWith("'''", pos)) {
                closeMultiline(sb, '\'');
                return sb.toString();
            }
            if (isNewline(c)) {
                newline();
                sb.append('\n');
            } else {
                checkControl(c);
                sb.append(c);
                pos++;
            }
        }
    }

    /** At a closing triple quote: up to two more quotes right after it belong to the content. */
    private void closeMultiline(StringBuilder sb, char quote) {
        int q = 3;
        while (q < 5 && pos + q < n && s.charAt(pos + q) == quote) q++;
        for (int i = 3; i < q; i++) sb.append(quote);
        pos += q;
    }

    private void escape(StringBuilder sb) throws TomlParseException {
        int eCol = col();
        pos++;
        if (pos >= n) throw error("unterminated escape");
        char e = s.charAt(pos++);
        switch (e) {
            case 'b' -> sb.append('\b');
            case 't' -> sb.append('\t');
            case 'n' -> sb.append('\n');
            case 'f' -> sb.append('\f');
            case 'r' -> sb.append('\r');
            case 'e' -> sb.append('\u001B');
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case 'u' -> sb.appendCodePoint(hexEscape(4, eCol));
            case 'U' -> sb.appendCodePoint(hexEscape(8, eCol));
            default -> throw errorAt(line, eCol, "invalid escape '\\" + e + "'");
        }
    }

    private int hexEscape(int digits, int eCol) throws TomlParseException {
        if (pos + digits > n) throw errorAt(line, eCol, "incomplete unicode escape");
        int cp = 0;
        for (int i = 0; i < digits; i++) {
            int d = Character.digit(s.charAt(pos + i), 16);
            if (d < 0) throw errorAt(line, eCol, "invalid unicode escape");
            cp = cp * 16 + d;
            if (cp > Character.MAX_CODE_POINT) throw errorAt(line, eCol, "unicode escape out of range");
        }
        if (cp >= 0xD800 && cp <= 0xDFFF) throw errorAt(line, eCol, "unicode escape names a surrogate");
        pos += digits;
        return cp;
    }

    private List<Object> array() throws TomlParseException {
        int aLine = line, aCol = col();
        enter();
        pos++;
        List<Object> out = new ArrayList<>();
        while (true) {
            skipBlankLinesAndComments();
            if (pos >= n) throw errorAt(aLine, aCol, "unterminated array");
            if (s.charAt(pos) == ']') {
                pos++;
                break;
            }
            out.add(value());
            skipBlankLinesAndComments();
            if (pos >= n) throw errorAt(aLine, aCol, "unterminated array");
            char c = s.charAt(pos);
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                break;
            } else {
                throw error("expected ',' or ']' in the array");
            }
        }
        depth--;
        return Collections.unmodifiableList(out);
    }

    private TomlTable inlineTable() throws TomlParseException {
        int tLine = line, tCol = col();
        enter();
        pos++;
        TomlTable t = new TomlTable();
        t.inline = true;
        while (true) {
            skipBlankLinesAndComments();
            if (pos >= n) throw errorAt(tLine, tCol, "unterminated inline table");
            if (s.charAt(pos) == '}') {
                pos++;
                break;
            }
            int kLine = line, kCol = col();
            List<String> path = keyPath();
            skipBlank();
            expect('=', "expected '=' after the key");
            skipBlank();
            place(t, path, value(), kLine, kCol);
            skipBlankLinesAndComments();
            if (pos >= n) throw errorAt(tLine, tCol, "unterminated inline table");
            char c = s.charAt(pos);
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                break;
            } else {
                throw error("expected ',' or '}' in the inline table");
            }
        }
        depth--;
        return t;
    }

    private void enter() throws TomlParseException {
        if (++depth > MAX_DEPTH) throw error("arrays or inline tables nested too deeply");
    }

    private String comment() {
        int start = ++pos;
        while (pos < n && !isNewline(s.charAt(pos))) pos++;
        return s.substring(start, pos);
    }

    private String trailingComment() {
        skipBlank();
        return pos < n && s.charAt(pos) == '#' ? comment() : null;
    }

    private void expectLineEnd() throws TomlParseException {
        skipBlank();
        if (pos >= n) return;
        char c = s.charAt(pos);
        if (!isNewline(c)) throw error("expected the end of the line, found '" + c + "'");
        newline();
    }

    private void expect(char c, String message) throws TomlParseException {
        if (pos >= n || s.charAt(pos) != c) throw error(message);
        pos++;
    }

    private void skipBlank() {
        while (pos < n && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t')) pos++;
    }

    private void skipBlankLinesAndComments() {
        while (pos < n) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t') pos++;
            else if (isNewline(c)) newline();
            else if (c == '#') comment();
            else return;
        }
    }

    /** Consumes one line break: LF, CRLF, or a lone CR. */
    private void newline() {
        if (s.charAt(pos) == '\r' && pos + 1 < n && s.charAt(pos + 1) == '\n') pos++;
        pos++;
        line++;
        lineStart = pos;
    }

    private void checkControl(char c) throws TomlParseException {
        if ((c < 0x20 && c != '\t') || c == 0x7F) throw error(String.format("control character U+%04X in a string (use an escape)", (int) c));
    }

    private int col() {
        return pos - lineStart + 1;
    }

    private TomlParseException error(String message) {
        return new TomlParseException(line, col(), message);
    }

    private static TomlParseException errorAt(int line, int col, String message) {
        return new TomlParseException(line, col, message);
    }

    private static boolean isNewline(char c) {
        return c == '\n' || c == '\r';
    }

    private static boolean isBareKeyChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    private static boolean isTokenChar(char c) {
        return isBareKeyChar(c) || c == '+' || c == '.' || c == ':';
    }
}
