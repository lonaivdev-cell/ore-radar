package net.loserver.oreradar.core;

/**
 * Where a radar must sit in the inventory to work. Ordered: each value includes the ones before it,
 * so the effective value of two limits is the one with the smaller ordinal ({@link #min}).
 * The ordinal is the wire value (DESIGN §7).
 */
public enum ActiveFrom {
    /** Main hand or off hand. */
    HANDS,
    /** Hands, then the hotbar. */
    HOTBAR,
    /** Hands, hotbar, then the main inventory. */
    INVENTORY;

    private static final ActiveFrom[] VALUES = values();

    public static ActiveFrom min(ActiveFrom a, ActiveFrom b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    /** The value with this wire ordinal, or null if there is none. */
    public static ActiveFrom byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }
}
