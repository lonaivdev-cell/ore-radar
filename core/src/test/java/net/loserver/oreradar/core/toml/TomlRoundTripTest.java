package net.loserver.oreradar.core.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TomlRoundTripTest {

    private static final String USER_FILE = """
            # Ore Radar server settings
            [radar]
            # how far
            maxRadius = 128 # I raised this
            probeChunks = 2
            myOwnKey = "kept"
            "odd key" = 1

            [features.outlines]
            enabled = false

            [targets]
            groups = [["#c:ores/zinc", "create:zinc_ore"], ["a:b", "#c:c"]]
            denyList = ["#c:ores", "#forge:ores"]

            [categories.ores]
            name = "Minérios ⛏"
            blocks = ["#c:ores"]

            [extra.nested]
            x = 1.5
            y = -0.0
            z = [1, 2.5, "s", true, []]
            # trailing comment at the end
            """;

    @Test
    void parseWriteParseKeepsValuesAndComments() throws Exception {
        TomlDocument first = TomlReader.parse(USER_FILE);
        String written = TomlWriter.write(first);
        TomlDocument second = TomlReader.parse(written);
        assertEquals(first.root(), second.root());
        assertEquals(List.of(" Ore Radar server settings"), second.root().table("radar").comments());
        assertEquals(List.of(" how far"), second.root().table("radar").comments("maxRadius"));
        assertEquals(" I raised this", second.root().table("radar").trailingComment("maxRadius"));
        assertEquals(List.of(" trailing comment at the end"), second.footer());
        assertEquals("kept", second.root().get("radar", "myOwnKey"));
        assertEquals(1L, second.root().get("radar", "odd key"));
        // Writing is stable: a second pass changes nothing.
        assertEquals(written, TomlWriter.write(second));
    }

    @Test
    void writerFormsArraysAndKeys() {
        TomlDocument doc = new TomlDocument();
        TomlTable t = doc.root().getOrCreateTable("t");
        t.put("short", List.of("a", "b"));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add("minecraft:item_number_" + i);
        t.put("long", many);
        t.put("nested", List.of(List.of("x", "y")));
        t.put("empty", List.of());
        t.put("needs quotes", 1);
        t.put("dotted.key", 2);
        t.put("tables", List.of(new TomlTable()));
        String out = TomlWriter.write(doc);
        assertTrue(out.contains("short = [\"a\", \"b\"]\n"), out);
        assertTrue(out.contains("long = [\n    \"minecraft:item_number_0\",\n"), out);
        assertTrue(out.contains("nested = [\n    [\"x\", \"y\"],\n]\n"), out);
        assertTrue(out.contains("empty = []\n"), out);
        assertTrue(out.contains("\"needs quotes\" = 1\n"), out);
        assertTrue(out.contains("\"dotted.key\" = 2\n"), out);
        assertTrue(out.contains("tables = [{}]\n"), out);
    }

    @Test
    void doublesAndStringsSurviveExactly() throws Exception {
        TomlDocument doc = new TomlDocument();
        TomlTable t = doc.root();
        double[] ds = {2.0, 0.12, 1e-5, 6.02e23, -3.5, 1e15, 123456789.125, Double.MAX_VALUE, Double.MIN_VALUE};
        for (int i = 0; i < ds.length; i++) t.put("d" + i, ds[i]);
        t.put("inf", Double.POSITIVE_INFINITY);
        t.put("ninf", Double.NEGATIVE_INFINITY);
        t.put("nan", Double.NaN);
        String tricky = "quote\" back\\ tab\t nl\n cr\r bell\u0007 del\u007f é 😀 #notacomment";
        t.put("s", tricky);
        String out = TomlWriter.write(doc);
        TomlTable back = TomlReader.parse(out).root();
        for (int i = 0; i < ds.length; i++) assertEquals(ds[i], back.get("d" + i), "d" + i);
        assertEquals("d0 = 2.0", out.lines().findFirst().orElseThrow());
        assertEquals(Double.POSITIVE_INFINITY, back.get("inf"));
        assertEquals(Double.NEGATIVE_INFINITY, back.get("ninf"));
        assertTrue(Double.isNaN((Double) back.get("nan")));
        assertEquals(tricky, back.get("s"));
    }

    @Test
    void tablesWithOnlySubTablesGetNoHeaderUnlessCommented() {
        TomlDocument doc = new TomlDocument();
        TomlTable features = doc.root().getOrCreateTable("features");
        features.getOrCreateTable("beep").put("enabled", true);
        String plain = TomlWriter.write(doc);
        assertFalse(plain.contains("[features]\n"), plain);
        assertTrue(plain.contains("[features.beep]\nenabled = true\n"), plain);
        features.setComments(List.of(" what clients may show"));
        String commented = TomlWriter.write(doc);
        assertTrue(commented.startsWith("# what clients may show\n[features]\n\n[features.beep]\n"), commented);
    }

    @Test
    void inlineTableFromTheUserFileIsRewrittenAsASectionWithItsComment() throws Exception {
        TomlDocument doc = TomlReader.parse("[categories]\n# the ores tab\nores = { name = \"Ores\", blocks = [] }\n");
        String out = TomlWriter.write(doc);
        assertTrue(out.contains("# the ores tab\n[categories.ores]\nname = \"Ores\"\nblocks = []\n"), out);
        assertEquals(doc.root(), TomlReader.parse(out).root());
    }

    @Test
    void fileWriteIsUtf8AndAtomic(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.toml");
        Files.writeString(file, "old");
        TomlDocument doc = new TomlDocument();
        doc.root().put("name", "Minérios");
        TomlWriter.write(doc, file);
        assertEquals("name = \"Minérios\"\n", Files.readString(file, StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("a.toml.tmp")));
        assertEquals("Minérios", TomlReader.read(file).root().get("name"));
    }

    @Test
    void malformedUtf8FileIsAnIoErrorNotGarbage(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("latin1.toml");
        Files.write(file, "name = \"Minérios\"".getBytes(StandardCharsets.ISO_8859_1));
        org.junit.jupiter.api.Assertions.assertThrows(java.nio.charset.CharacterCodingException.class, () -> TomlReader.read(file));
    }
}
