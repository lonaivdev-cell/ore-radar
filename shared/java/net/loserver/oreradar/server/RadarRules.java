package net.loserver.oreradar.server;

import java.util.List;

import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.designation.PackGroupFile;
import net.loserver.oreradar.core.designation.TargetRules;
import net.loserver.oreradar.core.energy.ChargingStation;
import net.loserver.oreradar.station.FuelTable;
import net.minecraft.world.level.block.Block;

/**
 * Everything the server derives from one config and one datapack load: the targeting rules, the
 * station's fuels and settings, and the config packet every client gets. Built at start, after each
 * datapack reload and on {@code /oreradar reload}; immutable, so a scan or a station keeps a
 * consistent view for as long as it holds one.
 */
public final class RadarRules {
    private final ServerConfig config;
    private final TargetRules<Block> targets;
    private final FuelTable fuels;
    private final ChargingStation.Settings station;
    private final byte[] configPacket;

    private RadarRules(ServerConfig config, TargetRules<Block> targets, FuelTable fuels) {
        this.config = config;
        this.targets = targets;
        this.fuels = fuels;
        this.station = ChargingStation.Settings.of(config);
        this.configPacket = targets.configPacket(config).toBytes();
    }

    /**
     * Resolves the config against the registry and the tags bound right now.
     *
     * @param packGroups the datapack groups files in load order ({@link PackGroups#latest()})
     * @param warnings   receives problems worth logging
     */
    public static RadarRules build(ServerConfig config, List<PackGroupFile> packGroups, List<String> warnings) {
        TargetRules<Block> targets = TargetRules.build(config, RegistryBlockLookup.INSTANCE, packGroups, warnings);
        return new RadarRules(config, targets, FuelTable.of(config.fuels(), warnings));
    }

    public ServerConfig config() {
        return config;
    }

    public TargetRules<Block> targets() {
        return targets;
    }

    public FuelTable fuels() {
        return fuels;
    }

    public ChargingStation.Settings station() {
        return station;
    }

    /** The encoded {@code oreradar:config} payload. Shared: don't modify it. */
    public byte[] configPacket() {
        return configPacket;
    }
}
