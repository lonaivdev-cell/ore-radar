package net.loserver.oreradar.core.designation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Where groups come from (DESIGN §4), merged in this order: auto ore groups ({@code ores/<m>}),
 * the config's {@code groups} ({@code custom/<key>}), then datapack files ({@code pack/<key>}),
 * where {@code <key>} comes from the group's first designation ({@link #key}). This class works on
 * designations only; {@link TargetRules} resolves them.
 */
public final class GroupSources {
    public static final String AUTO_PREFIX = "ores/";
    public static final String CUSTOM_PREFIX = "custom/";
    public static final String PACK_PREFIX = "pack/";
    /** Room kept for a collision suffix: "~" and up to seven digits, more groups than one config packet holds. */
    static final int MAX_SUFFIX_LENGTH = 8;

    private GroupSources() {}

    /**
     * The auto group a block tag feeds, or null: {@code c:ores/<m>}, {@code forge:ores/<m>},
     * {@code c:<m>_ores} (1.20.1 Fabric) and {@code minecraft:<m>_ores} all become {@code ores/<m>}.
     */
    public static String autoGroupId(String tagId) {
        String[] id = Designation.splitId(tagId);
        if (id == null || tagId.indexOf(':') < 0) return null;
        String ns = id[0], path = id[1];
        String material = null;
        if ((ns.equals("c") || ns.equals("forge")) && path.startsWith("ores/")) {
            material = path.substring("ores/".length());
        } else if ((ns.equals("c") || ns.equals("minecraft")) && path.endsWith("_ores")) {
            material = path.substring(0, path.length() - "_ores".length());
        }
        return material == null || material.isEmpty() || material.indexOf('/') >= 0 ? null : AUTO_PREFIX + material;
    }

    /** Auto ore groups from every known block tag id, sorted by group id; each lists its tags sorted. */
    public static List<GroupDef> auto(Iterable<String> blockTagIds) {
        Map<String, TreeSet<String>> byGroup = new TreeMap<>();
        for (String tag : blockTagIds) {
            String group = autoGroupId(tag);
            if (group != null) byGroup.computeIfAbsent(group, k -> new TreeSet<>()).add(tag);
        }
        List<GroupDef> out = new ArrayList<>(byGroup.size());
        for (Map.Entry<String, TreeSet<String>> e : byGroup.entrySet()) {
            List<Designation> tags = new ArrayList<>();
            for (String t : e.getValue()) tags.add(Designation.tag(t));
            out.add(new GroupDef(e.getKey(), tags));
        }
        return out;
    }

    /**
     * The config's groups as {@code custom/<key>} (see {@link #key}), in list order; empty ones are
     * skipped.
     */
    public static List<GroupDef> custom(List<List<Designation>> configGroups, List<String> warnings) {
        List<Sourced> groups = new ArrayList<>(configGroups.size());
        for (List<Designation> g : configGroups) groups.add(new Sourced("[targets] groups", g));
        return keyed(CUSTOM_PREFIX, groups, warnings);
    }

    /**
     * Datapack groups as {@code pack/<key>} (see {@link #key}). Files are given in load order
     * (lowest priority first); a {@code replace} file discards earlier ones at the same location.
     * The result is ordered by location, so it does not depend on how a platform enumerates
     * resources, and neither do the {@code ~2} suffixes of colliding keys.
     */
    public static List<GroupDef> pack(List<PackGroupFile> filesInLoadOrder, boolean includeVeinMining, List<String> warnings) {
        Map<String, List<List<Designation>>> byLocation = new LinkedHashMap<>();
        for (PackGroupFile f : filesInLoadOrder) {
            if (f.source() == PackGroupFile.Source.VEIN_MINING && !includeVeinMining) continue;
            List<List<Designation>> list = byLocation.get(f.location());
            if (list == null || f.replace()) byLocation.put(f.location(), list = new ArrayList<>());
            list.addAll(f.groups());
        }
        List<Sourced> groups = new ArrayList<>();
        for (Map.Entry<String, List<List<Designation>>> e : new TreeMap<>(byLocation).entrySet()) {
            for (List<Designation> g : e.getValue()) groups.add(new Sourced(e.getKey(), g));
        }
        return keyed(PACK_PREFIX, groups, warnings);
    }

    /**
     * A group's key (DESIGN §4): its first designation without the {@code #}, with {@code :} turned
     * into {@code .}, so {@code #c:ores/zinc} gives {@code c.ores/zinc}. Keying by content rather
     * than position keeps a radar's stored {@code @custom/...} or {@code @pack/...} valid when the
     * list or the datapacks are reordered. Cut short, if need be, so that the id plus a collision
     * suffix still fits in a {@link TargetSpec}.
     */
    public static String key(String prefix, Designation first) {
        String key = first.id().replace(':', '.');
        int room = TargetSpec.MAX_LENGTH - 1 - prefix.length() - MAX_SUFFIX_LENGTH; // 1 for the spec's '@'
        return key.length() <= room ? key : key.substring(0, room);
    }

    /** A group and where it was declared, for warnings. */
    private record Sourced(String where, List<Designation> designations) {}

    /**
     * Gives each non-empty group its id. A later group whose key is taken gets {@code ~2},
     * {@code ~3}, ... with a warning, since that id now depends on order again. A group that repeats
     * an earlier one exactly is dropped without a warning: packs that ship the same group under both
     * {@code oreradar/} and {@code veinmining/} are common, and it would resolve to the same blocks.
     */
    private static List<GroupDef> keyed(String prefix, List<Sourced> groups, List<String> warnings) {
        Map<String, List<List<Designation>>> byId = new HashMap<>();
        List<GroupDef> out = new ArrayList<>();
        for (Sourced g : groups) {
            if (g.designations().isEmpty()) continue;
            String id = prefix + key(prefix, g.designations().get(0));
            List<List<Designation>> same = byId.computeIfAbsent(id, k -> new ArrayList<>());
            if (same.contains(g.designations())) continue;
            same.add(g.designations());
            String unique = same.size() == 1 ? id : id + "~" + same.size();
            if (same.size() > 1) {
                warnings.add(g.where() + ": another group also starts with " + g.designations().get(0) + ", so this one is @" + unique
                        + "; radars tracking it lose it if the groups are reordered (start it with a different entry)");
            }
            out.add(new GroupDef(unique, g.designations()));
        }
        return out;
    }

    /** Every group in merge order, honouring {@code readVeinMiningGroups}. */
    public static List<GroupDef> all(Iterable<String> blockTagIds, List<List<Designation>> configGroups, List<PackGroupFile> packFiles,
                                     boolean readVeinMiningGroups, List<String> warnings) {
        List<GroupDef> out = new ArrayList<>(auto(blockTagIds));
        out.addAll(custom(configGroups, warnings));
        out.addAll(pack(packFiles, readVeinMiningGroups, warnings));
        return out;
    }
}
