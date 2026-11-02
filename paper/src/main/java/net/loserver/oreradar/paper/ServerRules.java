package net.loserver.oreradar.paper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;

import net.loserver.oreradar.core.config.LoadResult;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.designation.FuelEntry;
import net.loserver.oreradar.core.designation.TargetRules;
import net.loserver.oreradar.core.energy.ChargingStation;

/**
 * The server config and everything built from it. Replaced as a whole by {@link #load}, so a tick
 * never sees half of a reload. Groups come from the ore tags and the config's {@code groups}:
 * Paper has no API that reads files inside datapacks, and Vein Mining, whose groups the mods also
 * read from datapacks, is a mod.
 *
 * @param fuels       item → charge, from {@code [station] fuels} (first entry wins)
 * @param fuelEntries the {@code [station] fuels} entries that name something on this server, for
 *                    the station to list
 */
record ServerRules(ServerConfig config, TargetRules<Material> rules, Map<Material, Integer> fuels,
                   List<FuelEntry> fuelEntries, ChargingStation.Settings station) {

    /**
     * What a load did, for {@code /oreradar reload}.
     *
     * @param configFailed the file could not be read; the previous settings stay
     * @param warnings     everything logged as a warning
     */
    record Loaded(ServerRules rules, boolean configFailed, int warnings) {}

    /**
     * Reads {@code oreradar-server.toml} (writing it with defaults if missing) and builds the rules,
     * logging every warning. Never throws for a bad file: core keeps the previous config and says why.
     *
     * @param previous the config in use, or null at start
     */
    static Loaded load(Path configFile, ServerConfig previous, Logger log) {
        LoadResult<ServerConfig> result = ServerConfig.load(configFile, previous, true);
        result.warnings().forEach(w -> log.warning(configFile.getFileName() + ": " + w));
        if (result.fileWritten()) log.info("Wrote " + configFile);
        ServerConfig config = result.config();

        List<String> warnings = new ArrayList<>();
        TargetRules<Material> rules = TargetRules.build(config, new PaperBlockLookup(), List.of(), warnings);
        List<FuelEntry> fuelEntries = new ArrayList<>();
        Map<Material, Integer> fuels = fuels(config, fuelEntries, warnings);
        warnings.forEach(log::warning);
        ServerRules loaded = new ServerRules(config, rules, fuels, List.copyOf(fuelEntries), ChargingStation.Settings.of(config));
        return new Loaded(loaded, result.failed(), result.warnings().size() + warnings.size());
    }

    /** The charge one item of this stack adds to a station's buffer; 0 if it is not a fuel. */
    int fuelValue(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || RadarItems.isRadar(stack) || RadarItems.isStation(stack)) return 0;
        return fuels.getOrDefault(stack.getType(), 0);
    }

    /** @param used receives the entries that name something here */
    private static Map<Material, Integer> fuels(ServerConfig config, List<FuelEntry> used, List<String> warnings) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        for (FuelEntry f : config.fuels()) {
            if (f.item().tag()) {
                NamespacedKey key = NamespacedKey.fromString(f.item().id());
                Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
                // c: tags come from mod loaders; on Paper they exist only if a datapack defines them.
                if (tag == null) continue;
                for (Material m : tag.getValues()) out.putIfAbsent(m, f.value());
                used.add(f);
            } else {
                Material m = Material.matchMaterial(f.item().id());
                if (m == null || !m.isItem()) {
                    warnings.add("[station] fuels: " + f.item() + " is not an item on this server; ignoring it");
                    continue;
                }
                out.putIfAbsent(m, f.value());
                used.add(f);
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
