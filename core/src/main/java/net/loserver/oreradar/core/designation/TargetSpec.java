package net.loserver.oreradar.core.designation;

import java.util.Objects;

/**
 * What a radar is tuned to, as stored on the item (DESIGN §4): {@code @groupId}, {@code #ns:path}
 * (a block tag) or {@code ns:id} (one block). 1.0 specs ({@code #forge:ores/iron},
 * {@code minecraft:spawner}) parse unchanged. Only the server resolves specs to blocks.
 */
public record TargetSpec(Kind kind, String id) {
    public static final int MAX_LENGTH = 256;

    public enum Kind {
        GROUP, TAG, BLOCK
    }

    public TargetSpec {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        boolean ok = kind == Kind.GROUP ? isValidGroupId(id) : Designation.splitId(id) != null && id.indexOf(':') > 0;
        if (!ok) throw new IllegalArgumentException("invalid " + kind + " id " + id);
    }

    /** Parses a stored spec; null if empty, longer than {@link #MAX_LENGTH} or malformed. */
    public static TargetSpec parse(String spec) {
        if (spec == null || spec.isEmpty() || spec.length() > MAX_LENGTH) return null;
        if (spec.charAt(0) == '@') {
            String id = spec.substring(1);
            return isValidGroupId(id) ? new TargetSpec(Kind.GROUP, id) : null;
        }
        if (!spec.equals(spec.trim())) return null;
        Designation d = Designation.parse(spec);
        return d == null ? null : of(d);
    }

    public static TargetSpec of(Designation d) {
        return new TargetSpec(d.tag() ? Kind.TAG : Kind.BLOCK, d.id());
    }

    public static TargetSpec group(String groupId) {
        return new TargetSpec(Kind.GROUP, groupId);
    }

    /** The tag or block this spec names; null for a group. */
    public Designation designation() {
        return kind == Kind.GROUP ? null : (kind == Kind.TAG ? Designation.tag(id) : Designation.block(id));
    }

    /**
     * Group ids: lowercase letters, digits and {@code _ - . /}, optionally followed by a collision
     * suffix {@code ~2}, {@code ~3}, ... (see {@link GroupSources#key}); e.g. {@code ores/iron},
     * {@code custom/c.ores/zinc}, {@code pack/minecraft.clay~2}.
     */
    public static boolean isValidGroupId(String id) {
        if (id == null || id.length() >= MAX_LENGTH) return false;
        int tilde = id.indexOf('~');
        if (tilde < 0) return Designation.isValidPath(id);
        return tilde > 0 && Designation.isValidPath(id.substring(0, tilde)) && isCounter(id, tilde + 1);
    }

    /** A number from 2 up, without leading zeros, filling the rest of s from index from. */
    private static boolean isCounter(String s, int from) {
        int digits = s.length() - from;
        if (digits < 1 || digits > GroupSources.MAX_SUFFIX_LENGTH - 1 || s.charAt(from) == '0') return false;
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return digits > 1 || s.charAt(from) >= '2';
    }

    /** The spec string as stored on the item and sent on the wire. */
    @Override
    public String toString() {
        return switch (kind) {
            case GROUP -> "@" + id;
            case TAG -> "#" + id;
            case BLOCK -> id;
        };
    }
}
