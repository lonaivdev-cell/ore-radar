package net.loserver.oreradar.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.loserver.oreradar.core.net.ConfigPacket;
import net.loserver.oreradar.core.net.ScanPacket;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** How the screen and the HUD word a scan's outcome (DESIGN §6). */
public final class StatusText {
    /** Text colours: found, nothing found, a problem (no charge, disabled), and idle. */
    public static final int FOUND = 0xFF7FE0FF;
    public static final int NONE = 0xFFD0D0D0;
    public static final int PROBLEM = 0xFFFF7A6E;
    public static final int IDLE = 0xFFA0A0A0;

    private StatusText() {}

    /**
     * The status: "Found 14 within 38 m" and "nearest 12 m ↓4", "Nothing within 96 m", "Out of
     * charge", "Off", "Disabled in this dimension", or "Not active". A find comes in parts, so a narrow
     * panel can give each its own line; when "Found 2096 within 87 m" is wider than {@code fits}
     * allows it splits into "Found 2096" and "within 87 m", instead of wrapping between the number
     * and its unit.
     *
     * @param nearest the nearest match, or null when the server sent no position or distances are off
     */
    public static List<Component> lines(ScanPacket scan, ConfigPacket.Limits limits, @Nullable RadarClient.Nearest nearest, boolean height,
                                        Predicate<Component> fits) {
        return switch (scan.status()) {
            case FOUND -> {
                ScanPacket.Found found = scan.found();
                List<Component> parts = new ArrayList<>(3);
                Component count = Component.translatable("oreradar.status.found", found.totalMatches(), found.outlineRadius());
                if (fits.test(count)) {
                    parts.add(count);
                } else {
                    parts.add(Component.translatable("oreradar.status.found_count", found.totalMatches()));
                    parts.add(Component.translatable("oreradar.status.within", found.outlineRadius()));
                }
                if (nearest != null) parts.add(Component.translatable("oreradar.status.nearest", distance(nearest, height)));
                yield parts;
            }
            case NONE_FOUND -> List.of(Component.translatable("oreradar.status.none", limits.maxRadius()));
            case NO_CHARGE -> List.of(Component.translatable("oreradar.status.no_charge"));
            case OFF -> List.of(Component.translatable("oreradar.status.off"));
            case DIMENSION_DISABLED -> List.of(Component.translatable("oreradar.status.dimension"));
            case IDLE -> List.of(Component.translatable("oreradar.status.idle"));
        };
    }

    public static int color(ScanPacket.Status status) {
        return switch (status) {
            case FOUND -> FOUND;
            case NONE_FOUND -> NONE;
            case NO_CHARGE, DIMENSION_DISABLED -> PROBLEM;
            case OFF, IDLE -> IDLE;
        };
    }

    /** "12 m", with " ↑4" or " ↓4" when the match is well above or below eye level and heights are shown. */
    public static Component distance(RadarClient.Nearest nearest, boolean height) {
        Component metres = Component.translatable("oreradar.hud.distance", Math.round(nearest.distance()));
        if (!height || nearest.height() == 0) return metres;
        String key = nearest.height() > 0 ? "oreradar.hud.up" : "oreradar.hud.down";
        return Component.translatable("oreradar.hud.with_height", metres, Component.translatable(key, Math.abs(nearest.height())));
    }
}
