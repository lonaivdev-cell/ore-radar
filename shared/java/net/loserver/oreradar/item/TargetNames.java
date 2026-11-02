package net.loserver.oreradar.item;

import java.util.ArrayList;
import java.util.List;

import net.loserver.oreradar.client.ClientBridge;
import net.loserver.oreradar.core.designation.GroupSources;
import net.loserver.oreradar.core.designation.TargetRules;
import net.loserver.oreradar.core.designation.TargetSpec;
import net.loserver.oreradar.server.RadarServer;
import net.loserver.oreradar.server.RegistryBlockLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * How a target spec is named to players: by the block that stands for it, as the server's catalog
 * names it. Messages from the server use the resolved rules; tooltips ask the client's catalog
 * first, then the server's rules (singleplayer), then derive a name from the synced tags.
 */
public final class TargetNames {
    private TargetNames() {}

    public static Component of(String spec) {
        Component fromClient = ClientBridge.get().targetName(spec);
        if (fromClient != null) return fromClient;
        RadarServer server = RadarServer.get();
        if (server != null) return of(server.rules().targets().resolve(spec));
        Block derived = derive(spec);
        return derived != null ? derived.getName() : unknown(spec);
    }

    /** The name of what a spec resolved to; the spec itself when nothing stands for it. */
    public static Component of(TargetRules.Resolution<Block> resolution) {
        if (resolution.representative() != null) return resolution.representative().getName();
        return resolution.spec() == null ? Component.literal("?") : unknown(resolution.spec().toString());
    }

    /**
     * The block that stands for a spec, without rules: the block, the tag's most typical member, or
     * an auto ore group's tags, from the registries and the synced tags. The client uses it for
     * specs its catalog does not list, such as a 1.0 radar's "#forge:ores/iron". Null when nothing
     * stands for the spec.
     */
    @Nullable
    public static Block derive(String spec) {
        TargetSpec parsed = TargetSpec.parse(spec);
        if (parsed == null) return null;
        RegistryBlockLookup lookup = RegistryBlockLookup.INSTANCE;
        List<Block> members = new ArrayList<>();
        switch (parsed.kind()) {
            case BLOCK -> {
                Block block = lookup.block(parsed.id());
                if (block != null && !lookup.isAir(block)) members.add(block);
            }
            case TAG -> {
                lookup.tag(parsed.id()).forEach(members::add);
                // 1.0's #forge:ores/<m> is gone on 1.21.1; the server then tracks its auto ore group.
                if (members.isEmpty()) addAutoGroup(lookup, GroupSources.autoGroupId(parsed.id()), members);
            }
            case GROUP -> addAutoGroup(lookup, parsed.id(), members);
        }
        return TargetRules.representative(lookup, members);
    }

    /** The members of auto ore group {@code groupId} ({@code ores/<m>}) through its tags; nothing for any other id. */
    private static void addAutoGroup(RegistryBlockLookup lookup, @Nullable String groupId, List<Block> members) {
        if (groupId == null || !groupId.startsWith(GroupSources.AUTO_PREFIX)) return;
        String material = groupId.substring(GroupSources.AUTO_PREFIX.length());
        for (String tag : List.of("c:ores/" + material, "forge:ores/" + material, "minecraft:" + material + "_ores", "c:" + material + "_ores")) {
            lookup.tag(tag).forEach(members::add);
        }
    }

    private static Component unknown(String spec) {
        return Component.translatable("oreradar.target.unknown", spec);
    }
}
