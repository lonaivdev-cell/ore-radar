package net.loserver.oreradar.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

/** Ore Radar's key mappings; each loader registers them, and {@link RadarClient#tick} reads them. */
public final class RadarKeys {
    public static final String CATEGORY = "key.categories.oreradar";

    /** Opens the radar screen without using the item. Unbound by default (DESIGN §6). */
    public static final KeyMapping OPEN = new KeyMapping("key.oreradar.open", InputConstants.UNKNOWN.getValue(), CATEGORY);

    private RadarKeys() {}
}
