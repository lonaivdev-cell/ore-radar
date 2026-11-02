package net.loserver.oreradar.core.designation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.loserver.oreradar.core.util.Json;

/**
 * One datapack groups file: {@code data/<ns>/oreradar/groups.json} or Vein Mining's
 * {@code data/<ns>/veinmining/groups.json}, both {@code {"replace": bool, "groups": [[...], ...]}}.
 *
 * @param location the file's resource location (e.g. {@code mypack:veinmining/groups.json});
 *                 {@code replace} drops what lower-priority packs put at the same location
 */
public record PackGroupFile(String location, Source source, boolean replace, List<List<Designation>> groups) {

    public enum Source {
        ORE_RADAR, VEIN_MINING
    }

    public PackGroupFile {
        List<List<Designation>> copy = new ArrayList<>(groups.size());
        for (List<Designation> g : groups) copy.add(List.copyOf(g));
        groups = List.copyOf(copy);
    }

    /**
     * Parses one file. A group may also be one comma-separated string, as older Vein Mining configs
     * wrote them. Bad entries are skipped with a warning; returns null (with a warning) when the
     * file is not usable at all.
     */
    public static PackGroupFile parse(String location, Source source, String json, List<String> warnings) {
        Object root;
        try {
            root = Json.parse(json);
        } catch (Json.JsonException e) {
            warnings.add(location + ": " + e.getMessage());
            return null;
        }
        if (!(root instanceof Map<?, ?> obj)) {
            warnings.add(location + ": expected a JSON object");
            return null;
        }
        Object replace = obj.get("replace");
        if (replace != null && !(replace instanceof Boolean)) warnings.add(location + ": \"replace\" must be true or false");
        Object list = obj.get("groups");
        if (list != null && !(list instanceof List<?>)) {
            warnings.add(location + ": \"groups\" must be an array of arrays");
            return null;
        }
        List<List<Designation>> groups = new ArrayList<>();
        if (list != null) {
            int n = 0;
            for (Object g : (List<?>) list) {
                n++;
                List<Designation> parsed = parseGroup(g, location + " group " + n, warnings);
                if (!parsed.isEmpty()) groups.add(parsed);
            }
        }
        return new PackGroupFile(location, source, Boolean.TRUE.equals(replace), groups);
    }

    /** One group from a config or datapack: an array of designations, or one comma-separated string. */
    public static List<Designation> parseGroup(Object group, String where, List<String> warnings) {
        List<Object> entries = new ArrayList<>();
        if (group instanceof String s) {
            for (String part : s.split(",")) entries.add(part);
        } else if (group instanceof List<?> l) {
            entries.addAll(l);
        } else {
            warnings.add(where + ": expected an array of block ids and #tags");
            return List.of();
        }
        List<Designation> out = new ArrayList<>(entries.size());
        for (Object e : entries) {
            Designation d = e instanceof String s ? Designation.parse(s) : null;
            if (d == null) warnings.add(where + ": ignoring invalid entry " + e);
            else if (!out.contains(d)) out.add(d);
        }
        return out;
    }
}
