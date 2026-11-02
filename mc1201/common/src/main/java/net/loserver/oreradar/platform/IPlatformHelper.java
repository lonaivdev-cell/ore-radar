package net.loserver.oreradar.platform;

import java.nio.file.Path;
import java.util.function.Supplier;

import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.station.ChargingStationBlockEntity;
import net.loserver.oreradar.station.ChargingStationMenu;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** What common code needs from the mod loader. Each loader module implements it (see {@link Services}). */
public interface IPlatformHelper {
    /** The loader's name for logs and {@code /oreradar info}, such as "Forge" or "Fabric". */
    String getPlatformName();

    /** The game's config directory, home of oreradar-server.toml and oreradar-client.toml. */
    Path getConfigDir();

    /**
     * Registers {@code oreradar:<name>} in a vanilla registry. Call it only while the mod
     * initialises; the value may not exist until the loader's registration phase, so read it
     * through the returned supplier.
     */
    <R, T extends R> Supplier<T> register(ResourceKey<Registry<R>> registry, String name, Supplier<T> factory);

    /** The radar item, with the loader's extras: no re-equip bob, and the energy item capability. */
    RadarItem createRadarItem(Item.Properties properties);

    /**
     * The station's block entity type, whose block entities carry the loader's energy and
     * item-handler capabilities. (Vanilla's block entity factory type is not public in 1.20.1, so
     * common code cannot build the type itself.)
     */
    BlockEntityType<ChargingStationBlockEntity> createStationBlockEntityType(Block station);

    /** The station's menu type: a plain vanilla one, whose constructor 1.20.1 keeps private. */
    MenuType<ChargingStationMenu> createStationMenuType();

    /**
     * Sends a payload to one player on a server-to-client channel, as the whole custom payload
     * (DESIGN §7: no discriminator byte, so both loaders' jars speak the same bytes).
     */
    void sendToPlayer(ServerPlayer player, RadarProtocol.Channel channel, byte[] payload);

    /**
     * Sends a payload to the server on a client-to-server channel. Client side only: the caller
     * must be connected to a server.
     */
    void sendToServer(RadarProtocol.Channel channel, byte[] payload);
}
