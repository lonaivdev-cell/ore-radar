package net.loserver.oreradar.core.config;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.loserver.oreradar.core.designation.TargetSpec;
import net.loserver.oreradar.core.util.AtomicFiles;
import net.loserver.oreradar.core.util.Json;

/**
 * The radar screen's per-server lists (DESIGN §6), kept beside the TOML in
 * {@code config/oreradar-client-data.json}: for each server, the favourite targets in the order the
 * player starred them and the recent ones, newest first. They are data the screen maintains, not
 * settings, so they stay out of {@code oreradar-client.toml}.
 *
 * <p>A server is identified by {@link #serverKey} (multiplayer) or {@link #singleplayerKey}. Every
 * stored spec has passed {@link TargetSpec#parse} and is kept in its canonical form; whether the
 * current server still offers it is the screen's business. Mutable; use it on the client thread
 * only. {@link #load} never throws, and {@link #save} replaces the file atomically.
 */
public final class ClientData {
    public static final String FILE_NAME = "oreradar-client-data.json";
    public static final int MAX_RECENTS = 18;
    public static final int MAX_FAVOURITES = 256;
    /** Where an unreadable file is moved, so the next save does not destroy what the user had. */
    public static final String BAD_SUFFIX = ".bad";
    static final int VERSION = 1;
    private static final String DEFAULT_PORT = ":25565";

    /** One server's lists. */
    private record Lists(List<String> favourites, List<String> recents) {
        Lists() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        boolean isEmpty() {
            return favourites.isEmpty() && recents.isEmpty();
        }
    }

    private final Map<String, Lists> servers = new LinkedHashMap<>();

    public ClientData() {}

    /**
     * The key for a multiplayer server, from the address the player connected with. Case and the
     * default port are dropped, so {@code Play.Example.net} and {@code play.example.net:25565}
     * share one set of lists.
     */
    public static String serverKey(String address) {
        String key = address.trim().toLowerCase(Locale.ROOT);
        // Only strip a port: "[::1]:25565" or "host:25565", never part of a bare IPv6 address.
        boolean hasPort = key.startsWith("[") || key.indexOf(':') == key.length() - DEFAULT_PORT.length();
        return hasPort && key.endsWith(DEFAULT_PORT) ? key.substring(0, key.length() - DEFAULT_PORT.length()) : key;
    }

    /** The key for a singleplayer world, by its name as DESIGN §6 says ({@code sp:<world name>}). */
    public static String singleplayerKey(String worldName) {
        return "sp:" + worldName;
    }

    /**
     * Reads the file. A missing file gives empty lists. A file that is not valid JSON, or not shaped
     * like this file, is moved to {@code <name>.bad} (with a warning) and the lists start empty; bad
     * entries in an otherwise good file are dropped with a warning. Nothing is written here:
     * {@code fileWritten()} is always false, and {@code failed()} is true when the file could not be
     * used at all.
     */
    public static LoadResult<ClientData> load(Path file) {
        List<String> warnings = new ArrayList<>();
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new LoadResult<>(new ClientData(), warnings, false, false);
        } catch (CharacterCodingException e) {
            return corrupt(file, "it is not UTF-8 text", warnings);
        } catch (IOException e) {
            warnings.add(file + ": cannot read it (" + e + "); favourites and recent targets start empty");
            return new LoadResult<>(new ClientData(), warnings, false, true);
        }
        Object root;
        try {
            root = Json.parse(text);
        } catch (Json.JsonException e) {
            return corrupt(file, e.getMessage(), warnings);
        }
        if (!(root instanceof Map<?, ?> obj)) return corrupt(file, "expected a JSON object", warnings);
        Object version = obj.get("version");
        if (version != null && !(version instanceof Double v && v == VERSION)) {
            warnings.add(file + ": written by another version of Ore Radar (version " + version + "); reading what this one understands");
        }
        Object servers = obj.get("servers");
        if (servers == null) servers = Map.of();
        if (!(servers instanceof Map<?, ?> byServer)) return corrupt(file, "\"servers\" must be an object", warnings);

        ClientData data = new ClientData();
        for (Map.Entry<?, ?> e : byServer.entrySet()) {
            String key = (String) e.getKey(); // the reader only produces string keys
            if (key.isEmpty()) {
                warnings.add(file + ": ignoring lists stored under an empty server name");
                continue;
            }
            if (!(e.getValue() instanceof Map<?, ?> entry)) {
                warnings.add(file + ": ignoring server " + key + ": expected an object");
                continue;
            }
            Lists lists = new Lists();
            readSpecs(entry.get("favourites"), lists.favourites(), MAX_FAVOURITES, file + ": server " + key + " favourites", warnings);
            readSpecs(entry.get("recents"), lists.recents(), MAX_RECENTS, file + ": server " + key + " recents", warnings);
            if (!lists.isEmpty()) data.servers.put(key, lists);
        }
        return new LoadResult<>(data, warnings, false, false);
    }

    private static LoadResult<ClientData> corrupt(Path file, String reason, List<String> warnings) {
        String kept;
        try {
            kept = "kept as " + AtomicFiles.moveAside(file, BAD_SUFFIX).getFileName();
        } catch (IOException e) {
            kept = "could not be set aside (" + e + ") and will be replaced on the next save";
        }
        warnings.add(file + ": " + reason + "; favourites and recent targets start empty, the old file " + kept);
        return new LoadResult<>(new ClientData(), warnings, false, true);
    }

    /** Valid, distinct specs in file order, up to max. */
    private static void readSpecs(Object value, List<String> into, int max, String where, List<String> warnings) {
        if (value == null) return;
        if (!(value instanceof List<?> list)) {
            warnings.add(where + ": expected an array of target specs");
            return;
        }
        int invalid = 0;
        for (Object o : list) {
            TargetSpec spec = o instanceof String s ? TargetSpec.parse(s) : null;
            if (spec == null) {
                invalid++;
            } else if (!into.contains(spec.toString())) {
                if (into.size() == max) {
                    warnings.add(where + ": keeping the first " + max + ", the most there can be");
                    break;
                }
                into.add(spec.toString());
            }
        }
        if (invalid > 0) warnings.add(where + ": dropped " + invalid + " invalid " + (invalid == 1 ? "entry" : "entries"));
    }

    /** Writes every server's lists, replacing the file atomically. Servers with no lists are left out. */
    public void save(Path file) throws IOException {
        Map<String, Object> byServer = new LinkedHashMap<>();
        for (Map.Entry<String, Lists> e : servers.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("favourites", e.getValue().favourites());
            entry.put("recents", e.getValue().recents());
            byServer.put(e.getKey(), entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("servers", byServer);
        AtomicFiles.write(file, Json.write(root).getBytes(StandardCharsets.UTF_8));
    }

    /** The server's favourite specs, in the order they were starred; empty if none. Unmodifiable. */
    public List<String> favourites(String serverKey) {
        Lists l = servers.get(serverKey);
        return l == null ? List.of() : List.copyOf(l.favourites());
    }

    /** The server's recent specs, newest first, at most {@link #MAX_RECENTS}. Unmodifiable. */
    public List<String> recents(String serverKey) {
        Lists l = servers.get(serverKey);
        return l == null ? List.of() : List.copyOf(l.recents());
    }

    public boolean isFavourite(String serverKey, String spec) {
        TargetSpec parsed = TargetSpec.parse(spec);
        Lists l = servers.get(serverKey);
        return parsed != null && l != null && l.favourites().contains(parsed.toString());
    }

    /**
     * Stars or unstars a spec. Returns whether the lists changed (then save): false for an invalid
     * spec, for no change, and for a new favourite when the server already has
     * {@link #MAX_FAVOURITES}.
     */
    public boolean setFavourite(String serverKey, String spec, boolean favourite) {
        TargetSpec parsed = TargetSpec.parse(spec);
        if (parsed == null) return false;
        String canonical = parsed.toString();
        if (!favourite) {
            Lists l = servers.get(serverKey);
            return l != null && l.favourites().remove(canonical);
        }
        Lists l = servers.computeIfAbsent(serverKey, k -> new Lists());
        if (l.favourites().contains(canonical) || l.favourites().size() >= MAX_FAVOURITES) return false;
        return l.favourites().add(canonical);
    }

    /**
     * Records a spec the player just tracked: it moves to the front of the server's recents, and
     * the oldest beyond {@link #MAX_RECENTS} drops off. Returns whether the lists changed (false
     * for an invalid spec or one already first).
     */
    public boolean addRecent(String serverKey, String spec) {
        TargetSpec parsed = TargetSpec.parse(spec);
        if (parsed == null) return false;
        String canonical = parsed.toString();
        Lists l = servers.computeIfAbsent(serverKey, k -> new Lists());
        List<String> recents = l.recents();
        if (!recents.isEmpty() && recents.get(0).equals(canonical)) return false;
        recents.remove(canonical);
        recents.add(0, canonical);
        if (recents.size() > MAX_RECENTS) recents.subList(MAX_RECENTS, recents.size()).clear();
        return true;
    }
}
