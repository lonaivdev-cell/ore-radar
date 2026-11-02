package net.loserver.oreradar.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.core.designation.PackGroupFile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Reads the datapack groups files (DESIGN §4): every pack's {@code data/<ns>/oreradar/groups.json}
 * and Vein Mining's {@code data/<ns>/veinmining/groups.json}, in load order. The files are read off
 * the server thread with the rest of the datapack; the rules are rebuilt from them once the reload
 * has also bound the new tags ({@link RadarServer#onTagsLoaded}). Each loader registers one
 * instance as a server data reload listener.
 */
public class PackGroups extends SimplePreparableReloadListener<List<PackGroupFile>> {
    public static final ResourceLocation ID = OreRadar.id("pack_groups");
    private static final String ORE_RADAR_PATH = "oreradar/groups.json";
    private static final String VEIN_MINING_PATH = "veinmining/groups.json";

    private static volatile List<PackGroupFile> latest = List.of();

    /** The files of the last datapack load, in load order (lowest priority first). */
    public static List<PackGroupFile> latest() {
        return latest;
    }

    @Override
    protected List<PackGroupFile> prepare(ResourceManager resources, ProfilerFiller profiler) {
        List<PackGroupFile> files = new ArrayList<>();
        for (String namespace : new TreeSet<>(resources.getNamespaces())) {
            read(resources, new ResourceLocation(namespace, ORE_RADAR_PATH), PackGroupFile.Source.ORE_RADAR, files);
            read(resources, new ResourceLocation(namespace, VEIN_MINING_PATH), PackGroupFile.Source.VEIN_MINING, files);
        }
        return files;
    }

    @Override
    protected void apply(List<PackGroupFile> files, ResourceManager resources, ProfilerFiller profiler) {
        latest = List.copyOf(files);
    }

    private static void read(ResourceManager resources, ResourceLocation location, PackGroupFile.Source source, List<PackGroupFile> out) {
        // getResourceStack lists every pack's copy, lowest priority first: the order "replace" needs.
        for (Resource resource : resources.getResourceStack(location)) {
            String json;
            try (BufferedReader reader = resource.openAsReader()) {
                json = reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                OreRadar.LOGGER.warn("Ignoring {} from pack {}: {}", location, resource.sourcePackId(), e.toString());
                continue;
            }
            List<String> warnings = new ArrayList<>();
            PackGroupFile file = PackGroupFile.parse(location.toString(), source, json, warnings);
            warnings.forEach(w -> OreRadar.LOGGER.warn("Pack {}: {}", resource.sourcePackId(), w));
            if (file != null) out.add(file);
        }
    }
}
