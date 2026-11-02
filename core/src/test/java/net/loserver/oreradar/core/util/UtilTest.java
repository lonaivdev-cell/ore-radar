package net.loserver.oreradar.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UtilTest {

    @Test
    void longHashSetAgreesWithHashSet() {
        LongHashSet set = new LongHashSet(4);
        Set<Long> ref = new HashSet<>();
        Random rnd = new Random(9);
        for (int i = 0; i < 50_000; i++) {
            long v = rnd.nextInt(3) == 0 ? rnd.nextInt(100) - 50 : rnd.nextLong();
            assertEquals(ref.add(v), set.add(v));
        }
        assertEquals(ref.size(), set.size());
        for (long v : ref) assertTrue(set.contains(v));
        assertTrue(set.contains(0));
        for (int i = 0; i < 1000; i++) {
            long v = rnd.nextLong();
            assertEquals(ref.contains(v), set.contains(v));
        }
        LongHashSet empty = new LongHashSet(new long[0]);
        assertFalse(empty.contains(0));
        assertTrue(empty.add(0));
        assertFalse(empty.add(0));
    }

    @Test
    void deadline() {
        assertFalse(Deadline.NONE.expired());
        assertFalse(Deadline.afterMicros(60_000_000).expired());
    }

    @Test
    void backupsNeverOverwrite(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.toml");
        Files.writeString(f, "one");
        assertEquals(dir.resolve("c.toml.bak"), AtomicFiles.backup(f));
        Files.writeString(f, "two");
        assertEquals(dir.resolve("c.toml.bak.1"), AtomicFiles.backup(f));
        assertEquals("one", Files.readString(dir.resolve("c.toml.bak")));
        assertEquals("two", Files.readString(dir.resolve("c.toml.bak.1")));
        AtomicFiles.write(dir.resolve("sub/dir/new.toml"), "x".getBytes());
        assertEquals("x", Files.readString(dir.resolve("sub/dir/new.toml")));
    }

    @Test
    void json() throws Exception {
        Object v = Json.parse("\uFEFF { \"replace\": true, \"groups\": [[\"a:b\", \"#c:d\"], []], \"n\": -1.5e2, \"z\": null, "
                + "\"s\": \"\\u00e9\\n\\\"\" }");
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals(true, m.get("replace"));
        assertEquals(List.of(List.of("a:b", "#c:d"), List.of()), m.get("groups"));
        assertEquals(-150.0, m.get("n"));
        assertTrue(m.containsKey("z"));
        assertEquals("é\n\"", m.get("s"));
        for (String bad : List.of("", "{", "[1,]", "{\"a\" 1}", "{\"a\":1,\"a\":2}", "01", "tru", "\"x", "[1] 2", "{'a':1}", "\"\\x\"",
                "[" .repeat(100) + "]".repeat(100), "\"a\u0001\"")) {
            assertThrows(Json.JsonException.class, () -> Json.parse(bad), bad);
        }
        Json.JsonException e = assertThrows(Json.JsonException.class, () -> Json.parse("{\n  \"a\": [1,\n  2,,]\n}"));
        assertTrue(e.getMessage().startsWith("line 3, column 5"), e.getMessage());
    }

    @Test
    void jsonWriterOutputReadsBack() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", 1);
        value.put("half", 0.5);
        value.put("none", null);
        value.put("yes", true);
        value.put("text", "quote \" backslash \\ newline \n tab \t bell \u0007 é ★");
        value.put("empty", List.of());
        value.put("nested", Map.of("list", List.of("a", List.of(), Map.of())));
        String text = Json.write(value);
        assertTrue(text.startsWith("{\n  \"version\": 1,\n  \"half\": 0.5,\n"), text);
        assertTrue(text.contains("\\u0007"), text);
        assertTrue(text.endsWith("}\n"));
        Map<?, ?> back = (Map<?, ?>) Json.parse(text);
        assertEquals(List.copyOf(value.keySet()), List.copyOf(back.keySet()));
        assertEquals(1.0, back.get("version"));
        assertEquals(value.get("text"), back.get("text"));
        assertEquals(value.get("nested"), back.get("nested"));
        assertEquals("[]\n", Json.write(List.of()));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Map.of(1, 2)));
        assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
    }

    @Test
    void moveAsideNeverOverwrites(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("d.json");
        Files.writeString(f, "one");
        assertEquals(dir.resolve("d.json.bad"), AtomicFiles.moveAside(f, ".bad"));
        assertFalse(Files.exists(f));
        Files.writeString(f, "two");
        assertEquals(dir.resolve("d.json.bad.1"), AtomicFiles.moveAside(f, ".bad"));
        assertEquals("one", Files.readString(dir.resolve("d.json.bad")));
        assertEquals("two", Files.readString(dir.resolve("d.json.bad.1")));
    }
}
