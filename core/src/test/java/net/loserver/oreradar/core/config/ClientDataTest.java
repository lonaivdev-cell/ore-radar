package net.loserver.oreradar.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.loserver.oreradar.core.util.Json;

class ClientDataTest {
    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(ClientData.FILE_NAME);
    }

    @Test
    void serverKeys() {
        assertEquals("play.example.net", ClientData.serverKey(" Play.Example.net:25565 "));
        assertEquals("play.example.net", ClientData.serverKey("play.example.net"));
        assertEquals("play.example.net:25600", ClientData.serverKey("play.example.net:25600"));
        assertEquals("[::1]", ClientData.serverKey("[::1]:25565"));
        assertEquals("::1:25565", ClientData.serverKey("::1:25565"), "a bare IPv6 address has no port to drop");
        assertEquals("sp:New World", ClientData.singleplayerKey("New World"));
    }

    @Test
    void aMissingFileStartsEmptyAndIsNotCreated() {
        LoadResult<ClientData> r = ClientData.load(file());
        assertFalse(r.failed());
        assertFalse(r.fileWritten());
        assertEquals(List.of(), r.warnings());
        assertEquals(List.of(), r.config().favourites("a"));
        assertEquals(List.of(), r.config().recents("a"));
        assertFalse(Files.exists(file()));
    }

    @Test
    void favouritesAndRecentsAreKeptPerServerAndRoundTrip() throws Exception {
        ClientData d = new ClientData();
        String mp = ClientData.serverKey("mc.example.net"), sp = ClientData.singleplayerKey("Test");
        assertTrue(d.setFavourite(mp, "@ores/iron", true));
        assertTrue(d.setFavourite(mp, "iron_ore", true), "stored in canonical form");
        assertFalse(d.setFavourite(mp, "minecraft:iron_ore", true), "already a favourite");
        assertFalse(d.setFavourite(mp, "not a spec", true));
        assertFalse(d.setFavourite(mp, "", true));
        assertTrue(d.setFavourite(sp, "#c:ores", true));
        assertTrue(d.isFavourite(mp, "minecraft:iron_ore"));
        assertFalse(d.isFavourite(sp, "@ores/iron"));
        assertEquals(List.of("@ores/iron", "minecraft:iron_ore"), d.favourites(mp));
        assertTrue(d.setFavourite(mp, "@ores/iron", false));
        assertFalse(d.setFavourite(mp, "@ores/iron", false));
        assertFalse(d.setFavourite("nowhere", "@ores/iron", false));

        assertTrue(d.addRecent(mp, "@pack/c.ores/zinc"));
        assertTrue(d.addRecent(mp, "#c:ores"));
        assertFalse(d.addRecent(mp, "#c:ores"), "already the newest");
        assertTrue(d.addRecent(mp, "@pack/c.ores/zinc"));
        assertFalse(d.addRecent(mp, "@"));
        assertEquals(List.of("@pack/c.ores/zinc", "#c:ores"), d.recents(mp));

        d.save(file());
        LoadResult<ClientData> r = ClientData.load(file());
        assertEquals(List.of(), r.warnings());
        assertEquals(List.of("minecraft:iron_ore"), r.config().favourites(mp));
        assertEquals(List.of("@pack/c.ores/zinc", "#c:ores"), r.config().recents(mp));
        assertEquals(List.of("#c:ores"), r.config().favourites(sp));
        assertEquals(List.of(), r.config().recents(sp));
        Map<?, ?> root = (Map<?, ?>) Json.parse(Files.readString(file()));
        assertEquals(1.0, root.get("version"));
        assertFalse(Files.exists(dir.resolve(ClientData.FILE_NAME + ".tmp")), "written atomically through a temporary file");
    }

    @Test
    void recentsKeepTheNewestEighteenAndFavouritesStopAtTheCap() {
        ClientData d = new ClientData();
        for (int i = 0; i < 30; i++) d.addRecent("s", "mod:block_" + i);
        List<String> recents = d.recents("s");
        assertEquals(ClientData.MAX_RECENTS, recents.size());
        assertEquals("mod:block_29", recents.get(0));
        assertEquals("mod:block_12", recents.get(ClientData.MAX_RECENTS - 1));
        d.addRecent("s", "mod:block_20");
        assertEquals("mod:block_20", d.recents("s").get(0));
        assertEquals(ClientData.MAX_RECENTS, d.recents("s").size(), "moved, not duplicated");

        for (int i = 0; i < ClientData.MAX_FAVOURITES; i++) assertTrue(d.setFavourite("s", "mod:fav_" + i, true));
        assertFalse(d.setFavourite("s", "mod:one_too_many", true));
        assertEquals(ClientData.MAX_FAVOURITES, d.favourites("s").size());
        assertTrue(d.setFavourite("s", "mod:fav_0", false));
        assertTrue(d.setFavourite("s", "mod:one_too_many", true));
    }

    @Test
    void badEntriesAreDroppedWithWarnings() throws Exception {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 25; i++) tooMany.add("mod:b" + i);
        Files.writeString(file(), Json.write(Map.of("version", 1, "servers", Map.of(
                "good", Map.of("favourites", List.of("@ores/iron", "not a spec", 5, "@ores/iron", "iron_ore"), "recents", tooMany),
                "odd", "a string",
                "", Map.of("favourites", List.of("@ores/iron")),
                "typo", Map.of("favourites", "@ores/iron")))));
        LoadResult<ClientData> r = ClientData.load(file());
        assertFalse(r.failed());
        ClientData d = r.config();
        assertEquals(List.of("@ores/iron", "minecraft:iron_ore"), d.favourites("good"));
        assertEquals(tooMany.subList(0, ClientData.MAX_RECENTS), d.recents("good"));
        assertEquals(List.of(), d.favourites("typo"));
        assertEquals(5, r.warnings().size(), r.warnings().toString());
        assertTrue(r.warnings().contains(file() + ": server good favourites: dropped 2 invalid entries"), r.warnings().toString());
        assertTrue(Files.exists(file()), "a readable file stays where it is");
    }

    @Test
    void aCorruptFileIsSetAsideAndTheListsStartEmpty() throws Exception {
        Files.writeString(file(), "{\"servers\": {\"a\": {\"favourites\": [\"@ores/iron\"");
        LoadResult<ClientData> r = ClientData.load(file());
        assertTrue(r.failed());
        assertEquals(List.of(), r.config().favourites("a"));
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("line 1, column ") && r.warnings().get(0).endsWith("the old file kept as "
                + ClientData.FILE_NAME + ".bad"), r.warnings().get(0));
        assertFalse(Files.exists(file()));
        assertTrue(Files.readString(dir.resolve(ClientData.FILE_NAME + ".bad")).startsWith("{\"servers\""));

        // Wrong shapes and bad bytes count as corrupt too, and an earlier .bad is never overwritten.
        Files.writeString(file(), "[1, 2]");
        assertTrue(ClientData.load(file()).failed());
        assertTrue(Files.exists(dir.resolve(ClientData.FILE_NAME + ".bad.1")));
        Files.writeString(file(), "{\"servers\": []}");
        assertTrue(ClientData.load(file()).failed());
        Files.write(file(), new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}'});
        LoadResult<ClientData> bytes = ClientData.load(file());
        assertTrue(bytes.failed());
        assertTrue(bytes.warnings().get(0).contains("it is not UTF-8 text"), bytes.warnings().toString());
        assertTrue(Files.exists(dir.resolve(ClientData.FILE_NAME + ".bad.3")));

        // A save after a failed load starts a fresh file.
        ClientData d = r.config();
        d.addRecent("a", "#c:ores");
        d.save(file());
        assertEquals(List.of("#c:ores"), ClientData.load(file()).config().recents("a"));
    }

    @Test
    void aFileFromANewerVersionIsReadWithAWarning() throws Exception {
        Files.writeString(file(), "{\"version\": 2, \"servers\": {\"a\": {\"recents\": [\"#c:ores\"], \"pins\": []}}, \"extra\": true}");
        LoadResult<ClientData> r = ClientData.load(file());
        assertFalse(r.failed());
        assertEquals(List.of("#c:ores"), r.config().recents("a"));
        assertEquals(1, r.warnings().size(), r.warnings().toString());
    }
}
