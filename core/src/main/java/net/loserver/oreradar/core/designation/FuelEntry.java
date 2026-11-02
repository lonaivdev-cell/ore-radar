package net.loserver.oreradar.core.designation;

import java.util.Objects;

/**
 * One charging-station fuel, written {@code designation=value} in the server config: an item id or
 * {@code #item_tag}, and the charge one item adds to the station's buffer.
 */
public record FuelEntry(Designation item, int value) {
    public FuelEntry {
        Objects.requireNonNull(item, "item");
        if (value <= 0) throw new IllegalArgumentException("fuel value must be positive: " + value);
    }

    /** Parses {@code minecraft:redstone=4000} (spaces around '=' allowed); null if malformed. */
    public static FuelEntry parse(String text) {
        if (text == null) return null;
        int eq = text.lastIndexOf('=');
        if (eq < 0) return null;
        Designation item = Designation.parse(text.substring(0, eq));
        String number = text.substring(eq + 1).trim();
        if (item == null || number.isEmpty() || number.length() > 10) return null;
        for (int i = 0; i < number.length(); i++) {
            if (number.charAt(i) < '0' || number.charAt(i) > '9') return null;
        }
        long v = Long.parseLong(number);
        return v > 0 && v <= Integer.MAX_VALUE ? new FuelEntry(item, (int) v) : null;
    }

    @Override
    public String toString() {
        return item + "=" + value;
    }
}
