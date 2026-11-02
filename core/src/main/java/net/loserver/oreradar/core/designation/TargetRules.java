package net.loserver.oreradar.core.designation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.loserver.oreradar.core.config.CategoryDef;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.net.ConfigPacket;
import net.loserver.oreradar.core.net.RadarProtocol;

/**
 * The server's targeting rules resolved against a block registry (DESIGN §4): which blocks may be
 * tracked (those the categories list, or every block under {@code allowAll}, never a denied one),
 * the groups, the category tabs and the catalog clients choose from. Every platform builds
 * one at start and after each reload, so all of them apply the same rules. Immutable after
 * {@link #build}; safe to read from any thread.
 */
public final class TargetRules<B> {
    /** Blocks in this tag are never trackable. */
    public static final String UNTRACKABLE_TAG = "oreradar:untrackable";
    /** Under vanilla's 1 MiB limit for a server-to-client custom payload, with room for framing. */
    public static final int MAX_CONFIG_PAYLOAD_BYTES = 1_000_000;
    private static final int CACHE_LIMIT = 4096;

    /** A resolved group: its allowed members (never empty) and the block that stands for it. */
    public record Group<B>(String id, Set<B> members, B representative) {
        public TargetSpec spec() {
            return TargetSpec.group(id);
        }
    }

    public enum Status {
        OK,
        /** Not a well-formed spec. */
        INVALID,
        /** Names no existing group, tag or block (e.g. after a pack change). */
        UNKNOWN,
        /** Exists, but the server's rules forbid every block it names. */
        NOT_ALLOWED
    }

    /** What a spec finds: on OK, the allowed blocks (never empty) and a representative for name and icon. */
    public record Resolution<B>(Status status, TargetSpec spec, Set<B> blocks, B representative) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    private final BlockLookup<B> lookup;
    private final Set<B> allowed;
    private final List<Group<B>> groups;
    private final Map<String, Group<B>> groupsById;
    private final Map<B, Group<B>> groupOf;
    private final List<ConfigPacket.Category> categories;
    private final List<ConfigPacket.CatalogEntry> catalog;
    private final Map<String, Resolution<B>> cache = new ConcurrentHashMap<>();

    private TargetRules(BlockLookup<B> lookup, Set<B> allowed, List<Group<B>> groups, Map<B, Group<B>> groupOf,
                        List<ConfigPacket.Category> categories, List<ConfigPacket.CatalogEntry> catalog) {
        this.lookup = lookup;
        this.allowed = allowed;
        this.groups = groups;
        this.groupOf = groupOf;
        this.categories = categories;
        this.catalog = catalog;
        Map<String, Group<B>> byId = new HashMap<>();
        for (Group<B> g : groups) byId.put(g.id(), g);
        this.groupsById = byId;
    }

    /**
     * @param packGroups datapack groups files in load order (see {@link GroupSources#pack})
     * @param warnings   receives problems worth logging
     */
    public static <B> TargetRules<B> build(ServerConfig config, BlockLookup<B> lookup, List<PackGroupFile> packGroups,
                                           List<String> warnings) {
        Set<B> excluded = expand(lookup, config.denyList());
        for (B b : lookup.tag(UNTRACKABLE_TAG)) excluded.add(b);
        List<CategoryDef> cats = config.categories();
        List<Set<B>> catMembers = new ArrayList<>(cats.size());
        Set<B> listed = new HashSet<>();
        for (CategoryDef c : cats) {
            Set<B> m = expand(lookup, c.blocks());
            listed.addAll(m);
            catMembers.add(m);
        }
        Set<B> allowed = new LinkedHashSet<>();
        for (B b : lookup.blocks()) {
            if (!lookup.isAir(b) && !excluded.contains(b) && (config.allowAll() || listed.contains(b))) allowed.add(b);
        }
        for (Set<B> m : catMembers) m.retainAll(allowed);
        if (allowed.isEmpty()) warnings.add("no block is trackable (see [categories] and denyList): radars will find nothing");

        List<Group<B>> groups = new ArrayList<>();
        Map<B, Group<B>> groupOf = new HashMap<>();
        Set<Set<B>> seen = new HashSet<>();
        List<GroupDef> defs = GroupSources.all(lookup.tagIds(), config.groups(), packGroups, config.readVeinMiningGroups(), warnings);
        for (GroupDef d : defs) {
            Set<B> members = expand(lookup, d.designations());
            members.retainAll(allowed);
            if (members.isEmpty() || !seen.add(members)) continue; // nothing trackable, or same blocks as an earlier group
            Group<B> g = new Group<>(d.id(), Collections.unmodifiableSet(members), representative(lookup, members));
            groups.add(g);
            for (B b : members) groupOf.putIfAbsent(b, g);
        }

        List<ConfigPacket.CatalogEntry> entries = new ArrayList<>();
        List<Long> masks = new ArrayList<>();
        for (Group<B> g : groups) {
            long mask = 0;
            for (int i = 0; i < catMembers.size(); i++) {
                if (!Collections.disjoint(catMembers.get(i), g.members())) mask |= 1L << i;
            }
            B rep = g.representative();
            entries.add(new ConfigPacket.CatalogEntry(g.spec().toString(), lookup.id(rep), lookup.nameKey(rep), true, 0, g.members().size()));
            masks.add(mask);
        }
        int groupEntries = entries.size();
        for (B b : allowed) {
            if (groupOf.containsKey(b)) continue;
            long mask = 0;
            for (int i = 0; i < catMembers.size(); i++) {
                if (catMembers.get(i).contains(b)) mask |= 1L << i;
            }
            if (mask == 0 && !lookup.hasItem(b)) continue;
            String id = lookup.id(b);
            entries.add(new ConfigPacket.CatalogEntry(id, id, lookup.nameKey(b), true, 0, 1));
            masks.add(mask);
        }

        // Only tabs with something in them are sent; renumber the mask bits to match.
        long used = 0;
        for (long m : masks) used |= m;
        List<ConfigPacket.Category> visible = new ArrayList<>();
        int[] newBit = new int[cats.size()];
        for (int i = 0; i < cats.size(); i++) {
            newBit[i] = -1;
            if ((used & (1L << i)) != 0) {
                newBit[i] = visible.size();
                visible.add(cats.get(i).toPacket());
            }
        }
        List<ConfigPacket.CatalogEntry> finalEntries = new ArrayList<>(entries.size());
        for (int k = 0; k < entries.size(); k++) {
            long m = masks.get(k), remapped = 0;
            for (int i = 0; i < cats.size(); i++) {
                if ((m & (1L << i)) != 0) remapped |= 1L << newBit[i];
            }
            ConfigPacket.CatalogEntry e = entries.get(k);
            finalEntries.add(new ConfigPacket.CatalogEntry(e.spec(), e.iconBlockId(), e.name(), e.nameIsTranslationKey(), remapped,
                    e.memberCount()));
        }
        List<ConfigPacket.CatalogEntry> fitted = fit(config, visible, finalEntries, groupEntries, warnings);
        return new TargetRules<>(lookup, Collections.unmodifiableSet(allowed), List.copyOf(groups), groupOf, List.copyOf(visible),
                List.copyOf(fitted));
    }

    /**
     * Keeps the catalog within one config packet: drops single blocks from the end (groups first
     * stay) with a warning. Only {@code allowAll} in a very large modpack gets here.
     */
    private static List<ConfigPacket.CatalogEntry> fit(ServerConfig config, List<ConfigPacket.Category> cats,
                                                       List<ConfigPacket.CatalogEntry> entries, int groupEntries, List<String> warnings) {
        List<ConfigPacket.CatalogEntry> list = entries;
        if (list.size() > RadarProtocol.MAX_CATALOG) list = list.subList(0, RadarProtocol.MAX_CATALOG);
        list = head(config.limits(), cats, list, groupEntries, MAX_CONFIG_PAYLOAD_BYTES);
        if (list.size() < entries.size()) {
            warnings.add("the catalog has " + entries.size() + " entries, more than one packet holds; only the first " + list.size()
                    + " are offered in the radar screen (turn allowAll off, or list fewer blocks in the categories)");
        }
        return list;
    }

    /** The longest head of {@code entries}, never shorter than {@code keep}, whose config packet fits {@code maxBytes}. */
    private static List<ConfigPacket.CatalogEntry> head(ConfigPacket.Limits limits, List<ConfigPacket.Category> cats,
                                                        List<ConfigPacket.CatalogEntry> entries, int keep, int maxBytes) {
        List<ConfigPacket.CatalogEntry> list = entries;
        int size = new ConfigPacket(limits, cats, list).toBytes().length;
        while (size > maxBytes && list.size() > keep) {
            int average = Math.max(1, size / Math.max(1, list.size()));
            int drop = (size - maxBytes) / average + 1;
            list = list.subList(0, Math.max(keep, list.size() - drop));
            size = new ConfigPacket(limits, cats, list).toBytes().length;
        }
        // The estimate can take a few entries too many (entries differ in size); give back those that still fit.
        while (list.size() < entries.size()) {
            List<ConfigPacket.CatalogEntry> longer = entries.subList(0, list.size() + 1);
            if (new ConfigPacket(limits, cats, longer).toBytes().length > maxBytes) break;
            list = longer;
        }
        return list;
    }

    /** Every block the designations name (tags expanded), in designation order; unknown ones contribute nothing. */
    private static <B> Set<B> expand(BlockLookup<B> lookup, List<Designation> designations) {
        Set<B> out = new LinkedHashSet<>();
        for (Designation d : designations) {
            if (d.tag()) {
                for (B b : lookup.tag(d.id())) out.add(b);
            } else {
                B b = lookup.block(d.id());
                if (b != null) out.add(b);
            }
        }
        return out;
    }

    /**
     * The block that names and pictures a set (1.0's choice): prefer plain ores over deepslate,
     * nether and end variants, vanilla over modded, and blocks with an item.
     */
    public static <B> B representative(BlockLookup<B> lookup, Iterable<B> blocks) {
        B best = null;
        int bestScore = Integer.MAX_VALUE;
        for (B b : blocks) {
            String id = lookup.id(b);
            int colon = id.indexOf(':');
            String ns = id.substring(0, Math.max(0, colon)), path = id.substring(colon + 1);
            int score = 0;
            if (path.contains("deepslate")) score += 4;
            if (path.contains("nether") || path.contains("end_")) score += 2;
            if (!path.endsWith("_ore") && !path.startsWith("ore_")) score += 1;
            if (!ns.equals("minecraft")) score += 1;
            if (!lookup.hasItem(b)) score += 8;
            if (score < bestScore) {
                bestScore = score;
                best = b;
            }
        }
        return best;
    }

    /** Resolves a stored spec (DESIGN §4). Only the server evaluates specs for scanning. */
    public Resolution<B> resolve(String specString) {
        TargetSpec spec = TargetSpec.parse(specString);
        if (spec == null) return new Resolution<>(Status.INVALID, null, Set.of(), null);
        String key = spec.toString();
        Resolution<B> r = cache.get(key);
        if (r != null) return r;
        r = compute(spec);
        if (cache.size() >= CACHE_LIMIT) cache.clear(); // specs come from players; keep the cache bounded
        cache.put(key, r);
        return r;
    }

    private Resolution<B> compute(TargetSpec spec) {
        switch (spec.kind()) {
            case GROUP -> {
                Group<B> g = groupsById.get(spec.id());
                return g == null ? new Resolution<>(Status.UNKNOWN, spec, Set.of(), null)
                        : new Resolution<>(Status.OK, spec, g.members(), g.representative());
            }
            case TAG -> {
                Set<B> members = new LinkedHashSet<>();
                for (B b : lookup.tag(spec.id())) members.add(b);
                if (members.isEmpty()) {
                    // 1.0 radars store #forge:ores/<m>, which the 1.21.1 loaders no longer define: such a tag finds
                    // the auto ore group it would feed.
                    Group<B> g = groupsById.get(GroupSources.autoGroupId(spec.id()));
                    return g == null ? new Resolution<>(Status.UNKNOWN, spec, Set.of(), null)
                            : new Resolution<>(Status.OK, spec, g.members(), g.representative());
                }
                members.retainAll(allowed);
                if (members.isEmpty()) return new Resolution<>(Status.NOT_ALLOWED, spec, Set.of(), null);
                return new Resolution<>(Status.OK, spec, Collections.unmodifiableSet(members), representative(lookup, members));
            }
            default -> {
                B b = lookup.block(spec.id());
                if (b == null || lookup.isAir(b)) return new Resolution<>(Status.UNKNOWN, spec, Set.of(), null);
                if (!allowed.contains(b)) return new Resolution<>(Status.NOT_ALLOWED, spec, Set.of(), b);
                return new Resolution<>(Status.OK, spec, Set.of(b), b);
            }
        }
    }

    /**
     * What sneak-using the radar on this block tracks: its first group, else the block itself, or
     * null when the rules forbid it ("untrackable").
     */
    public TargetSpec specFor(B block) {
        Group<B> g = groupOf.get(block);
        if (g != null) return g.spec();
        return allowed.contains(block) ? TargetSpec.of(Designation.block(lookup.id(block))) : null;
    }

    public boolean isAllowed(B block) {
        return allowed.contains(block);
    }

    /** Every trackable block, in registry order. */
    public Set<B> allowed() {
        return allowed;
    }

    /** Groups in merge order: auto, config, datapack. */
    public List<Group<B>> groups() {
        return groups;
    }

    public Group<B> group(String id) {
        return groupsById.get(id);
    }

    /** The first group containing the block, or null. */
    public Group<B> groupOf(B block) {
        return groupOf.get(block);
    }

    /** The non-empty category tabs, in config order. */
    public List<ConfigPacket.Category> categories() {
        return categories;
    }

    /** Groups first, then single blocks in registry order. */
    public List<ConfigPacket.CatalogEntry> catalog() {
        return catalog;
    }

    /** The config packet for these rules and the config they were built from. */
    public ConfigPacket configPacket(ServerConfig config) {
        return new ConfigPacket(config.limits(), categories, catalog);
    }
}
