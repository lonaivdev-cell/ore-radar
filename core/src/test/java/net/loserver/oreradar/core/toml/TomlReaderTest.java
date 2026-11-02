package net.loserver.oreradar.core.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TomlReaderTest {

    private static TomlTable parse(String s) throws TomlParseException {
        return TomlReader.parse(s).root();
    }

    @Test
    void scalars() throws Exception {
        TomlTable t = parse("""
                a = 42
                b = -17
                c = +3
                d = 1_000_000
                e = 0xDEAD_beef
                f = 0o755
                g = 0b1101
                h = 3.25
                i = -1e-3
                j = 6.02E23
                k = inf
                l = -inf
                m = nan
                n = true
                o = false
                p = 0
                q = 2.0
                """);
        assertEquals(42L, t.get("a"));
        assertEquals(-17L, t.get("b"));
        assertEquals(3L, t.get("c"));
        assertEquals(1_000_000L, t.get("d"));
        assertEquals(0xDEADBEEFL, t.get("e"));
        assertEquals(493L, t.get("f"));
        assertEquals(13L, t.get("g"));
        assertEquals(3.25, t.get("h"));
        assertEquals(-0.001, t.get("i"));
        assertEquals(6.02e23, t.get("j"));
        assertEquals(Double.POSITIVE_INFINITY, t.get("k"));
        assertEquals(Double.NEGATIVE_INFINITY, t.get("l"));
        assertTrue(Double.isNaN((Double) t.get("m")));
        assertEquals(true, t.get("n"));
        assertEquals(false, t.get("o"));
        assertEquals(0L, t.get("p"));
        assertEquals(2.0, t.get("q"));
    }

    @Test
    void strings() throws Exception {
        TomlTable t = parse("""
                basic = "tab\\there \\"quoted\\" back\\\\slash \\u00e9 \\U0001F600"
                literal = 'C:\\path\\no "escapes"'
                empty = ""
                unicode = "ãçé 日本 ⛏"
                ml = \"\"\"
                first
                  second\"\"\"
                trimmed = \"\"\"one \\
                      two\"\"\"
                quotes = \"\"\"a "" b""\"\"\"
                mll = '''
                raw \\n line'''
                """);
        assertEquals("tab\there \"quoted\" back\\slash é \uD83D\uDE00", t.get("basic"));
        assertEquals("C:\\path\\no \"escapes\"", t.get("literal"));
        assertEquals("", t.get("empty"));
        assertEquals("ãçé 日本 ⛏", t.get("unicode"));
        assertEquals("first\n  second", t.get("ml"));
        assertEquals("one two", t.get("trimmed"));
        assertEquals("a \"\" b\"\"", t.get("quotes"));
        assertEquals("raw \\n line", t.get("mll"));
    }

    @Test
    void tablesDottedHeadersAndKeys() throws Exception {
        TomlTable t = parse("""
                top = 1
                [radar]
                maxRadius = 96
                [features.outlines]
                enabled = true
                [ categories . "ores" ]
                name = "Ores"
                [categories.'stone-1']
                name = "Stone"
                [x]
                a.b.c = 5
                "quoted key" = 1
                'lit.key' = 2
                """);
        assertEquals(1L, t.get("top"));
        assertEquals(96L, t.get("radar", "maxRadius"));
        assertEquals(true, t.get("features", "outlines", "enabled"));
        assertEquals("Ores", t.get("categories", "ores", "name"));
        assertEquals("Stone", t.get("categories", "stone-1", "name"));
        assertEquals(5L, t.get("x", "a", "b", "c"));
        assertEquals(1L, t.get("x", "quoted key"));
        assertEquals(2L, t.get("x", "lit.key"));
        assertEquals(List.of("ores", "stone-1"), List.copyOf(t.table("categories").keySet()));
    }

    @Test
    void arraysIncludingNestedMultiLineAndComments() throws Exception {
        TomlTable t = parse("""
                a = []
                b = [1, 2, 3,]
                c = [["#c:ores/zinc", "create:zinc_ore"], ["a:b"]]
                d = [
                    "x", # first
                    # a comment line inside the array
                    "y",

                    "z"   ,
                ]
                e = [ [ 1, [2, 3] ], "mixed", true ]
                """);
        assertEquals(List.of(), t.get("a"));
        assertEquals(List.of(1L, 2L, 3L), t.get("b"));
        assertEquals(List.of(List.of("#c:ores/zinc", "create:zinc_ore"), List.of("a:b")), t.get("c"));
        assertEquals(List.of("x", "y", "z"), t.get("d"));
        assertEquals(List.of(List.of(1L, List.of(2L, 3L)), "mixed", true), t.get("e"));
    }

    @Test
    void inlineTables() throws Exception {
        TomlTable t = parse("""
                [categories]
                ores = { name = "Ores", icon = "minecraft:diamond_ore", blocks = ["#c:ores"] }
                empty = {}
                nested = { a.b = 1, c = { d = 2 } }
                """);
        assertEquals("Ores", t.get("categories", "ores", "name"));
        assertEquals(List.of("#c:ores"), t.get("categories", "ores", "blocks"));
        assertTrue(t.table("categories").table("empty").isEmpty());
        assertEquals(1L, t.get("categories", "nested", "a", "b"));
        assertEquals(2L, t.get("categories", "nested", "c", "d"));
    }

    @Test
    void commentsAttachToTheNextKeyOrHeader() throws Exception {
        TomlDocument doc = TomlReader.parse("""
                # file header
                # second line

                [radar]  # header trailing
                # about maxRadius
                maxRadius = 96 # trailing
                probeChunks = 2
                # dangling before next table
                [energy]
                enabled = true
                # footer
                """);
        TomlTable radar = doc.root().table("radar");
        assertEquals(List.of(" file header", " second line"), radar.comments());
        assertEquals(" header trailing", radar.trailingComment());
        assertEquals(List.of(" about maxRadius"), radar.comments("maxRadius"));
        assertEquals(" trailing", radar.trailingComment("maxRadius"));
        assertEquals(List.of(), radar.comments("probeChunks"));
        assertNull(radar.trailingComment("probeChunks"));
        assertEquals(List.of(" dangling before next table"), doc.root().table("energy").comments());
        assertEquals(List.of(" footer"), doc.footer());
    }

    @Test
    void crlfBomTabsAndLoneCr() throws Exception {
        TomlDocument doc = TomlReader.parse("\uFEFF# c\r\n[detection]\r\n\textraChunks = 2\r\n\t#Allowed: A, B\r\n\tactiveFrom = \"HANDS\"\r\nx = 1\ry = \"\"\"a\r\nb\"\"\"\r\n");
        TomlTable d = doc.root().table("detection");
        assertEquals(2L, d.get("extraChunks"));
        assertEquals("HANDS", d.get("activeFrom"));
        assertEquals(List.of("Allowed: A, B"), d.comments("activeFrom"));
        assertEquals(1L, d.get("x"));
        assertEquals("a\nb", d.get("y"));
    }

    @Test
    void errorsOnTheLineAfterACrlfLineCountCorrectly() {
        TomlParseException e = assertThrows(TomlParseException.class, () -> TomlReader.parse("a = 1\r\nb = 2\r\nc = = 3\r\n"));
        assertEquals(3, e.line());
        assertEquals(5, e.column());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "missing equals         | 'a 1'                          | 1 | 3 | '='",
            "missing value          | 'a ='                          | 1 | 4 | expected a value",
            "bare word value        | 'a = big'                      | 1 | 5 | strings need quotes",
            "unterminated string    | 'a = \"abc'                    | 1 | 5 | unterminated string",
            "string across lines    | 'a = \"ab\\nc\"'                | 1 | 5 | unterminated string",
            "invalid escape         | 'a = \"\\q\"'                  | 1 | 6 | invalid escape",
            "duplicate key          | 'a = 1\\na = 2'                 | 2 | 1 | duplicate key",
            "table twice            | '[t]\\n[t]'                     | 2 | 1 | defined twice",
            "array of tables        | '[[t]]'                        | 1 | 1 | not supported",
            "date                   | 'd = 1979-05-27'               | 1 | 5 | dates",
            "time                   | 'd = 07:32:00'                 | 1 | 5 | dates",
            "trailing garbage       | 'a = 1 2'                      | 1 | 7 | end of the line",
            "unterminated array     | 'a = [1, 2'                    | 1 | 5 | unterminated array",
            "array missing comma    | 'a = [1 2]'                    | 1 | 8 | ','",
            "integer overflow       | 'a = 9223372036854775808'      | 1 | 5 | out of range",
            "leading zero           | 'a = 012'                      | 1 | 5 | invalid value",
            "bad underscore         | 'a = 1__0'                     | 1 | 5 | invalid value",
            "key then dotted value  | 'a = 1\\na.b = 2'               | 2 | 1 | already holds a value",
            "header over a value    | 'a = 1\\n[a.b]'                 | 2 | 1 | already holds a value",
            "missing header bracket | '[a'                           | 1 | 3 | ']'",
            "empty key              | '= 1'                          | 1 | 1 | expected a key",
            "surrogate escape       | 'a = \"\\uD800\"'              | 1 | 6 | surrogate",
    })
    void errorsReportLineAndColumn(String name, String text, int line, int column, String reason) {
        String input = text.replace("\\n", "\n");
        TomlParseException e = assertThrows(TomlParseException.class, () -> TomlReader.parse(input), name);
        assertEquals(line, e.line(), name + ": " + e.getMessage());
        assertEquals(column, e.column(), name + ": " + e.getMessage());
        assertTrue(e.reason().contains(reason), name + ": " + e.getMessage());
        assertTrue(e.getMessage().startsWith("line " + line + ", column " + column + ": "));
    }

    @Test
    void controlCharacterInStringIsRejected() {
        TomlParseException e = assertThrows(TomlParseException.class, () -> TomlReader.parse("a = \"x\u0001y\""));
        assertTrue(e.reason().contains("U+0001"));
    }

    @Test
    void deepNestingIsRejectedNotAStackOverflow() {
        String deep = "a = " + "[".repeat(10_000) + "]".repeat(10_000);
        TomlParseException e = assertThrows(TomlParseException.class, () -> TomlReader.parse(deep));
        assertTrue(e.reason().contains("nested too deeply"));
    }

    @Test
    void emptyAndCommentOnlyInputs() throws Exception {
        assertTrue(TomlReader.parse("").root().isEmpty());
        TomlDocument doc = TomlReader.parse("# only a comment\n\n");
        assertTrue(doc.root().isEmpty());
        assertEquals(List.of(" only a comment"), doc.footer());
    }
}
