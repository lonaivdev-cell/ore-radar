package net.loserver.oreradar.paper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import net.loserver.oreradar.core.config.ServerConfig;

/**
 * Ore Radar on Paper (DESIGN §8): the same rules, scan and energy as the mods, from core, served to
 * every player as a vanilla client through a compass, the action bar, glowing block displays and
 * chest GUIs. It registers no plugin channels. Everything runs on the server thread; Folia is not
 * supported.
 */
public final class OreRadarPlugin extends JavaPlugin {
    private ServerRules rules;
    private RadarService radars;
    private StationManager stations;
    private SearchPrompt search;
    /** Radar menus with a viewer, to close at disable. */
    private final Set<RadarMenu> openMenus = new HashSet<>();

    @Override
    public void onEnable() {
        rules = ServerRules.load(configFile(), null, getLogger()).rules();
        removeLeftoverOutlines();

        radars = new RadarService(this);
        stations = new StationManager(this);
        search = new SearchPrompt(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new RadarListener(this, radars, stations), this);
        pm.registerEvents(stations, this);
        pm.registerEvents(search, this);
        pm.registerEvents(new Menu.Events(), this);

        RadarCommand command = new RadarCommand(this);
        PluginCommand cmd = getCommand("oreradar");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }

        Recipes.register(rules);
        stations.loadLoadedChunks();
        radars.start();
        getLogger().info("Ore Radar " + getPluginMeta().getVersion() + " enabled on " + getServer().getName() + " "
                + getServer().getMinecraftVersion() + ": " + rules.rules().allowed().size() + " trackable blocks, "
                + rules.rules().groups().size() + " groups, " + rules.rules().catalog().size() + " catalog entries");
    }

    @Override
    public void onDisable() {
        // Each step stands alone: a failed enable leaves some of these unset.
        if (radars != null) radars.stop();
        if (stations != null) stations.shutdown();
        if (search != null) search.clear();
        // Once disabled, nothing guards the GUIs' items any more.
        for (RadarMenu menu : new ArrayList<>(openMenus)) menu.closeAll();
        openMenus.clear();
        Recipes.remove();
        removeLeftoverOutlines();
    }

    ServerRules rules() {
        return rules;
    }

    RadarService radars() {
        return radars;
    }

    StationManager stations() {
        return stations;
    }

    SearchPrompt search() {
        return search;
    }

    Set<RadarMenu> openMenus() {
        return openMenus;
    }

    /**
     * Re-reads the config and rebuilds the rules (DESIGN §11 {@code reload}); a broken file keeps
     * the old settings, but the rules are rebuilt anyway, since tags and datapacks may have changed.
     */
    ServerRules.Loaded reload() {
        ServerRules.Loaded loaded = ServerRules.load(configFile(), rules.config(), getLogger());
        rules = loaded.rules();
        Recipes.register(rules);
        radars.rulesReloaded();
        stations.rulesReloaded();
        return loaded;
    }

    private Path configFile() {
        return getDataFolder().toPath().resolve(ServerConfig.FILE_NAME);
    }

    /** Outlines are never saved, but one kept by a crash or another plugin must not linger. */
    private void removeLeftoverOutlines() {
        int removed = 0;
        for (World w : Bukkit.getWorlds()) {
            for (BlockDisplay d : w.getEntitiesByClass(BlockDisplay.class)) {
                if (RadarListener.isOutline(d)) {
                    d.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) getLogger().info("Removed " + removed + " leftover outline displays");
    }
}
