package net.loserver.oreradar.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import net.kyori.adventure.text.Component;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.designation.GroupSources;
import net.loserver.oreradar.core.designation.TargetRules;
import net.loserver.oreradar.core.net.ConfigPacket;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.core.scan.ScanBudget;

/**
 * {@code /oreradar} (DESIGN §8, §11): {@code give}, {@code reload}, {@code info}, {@code target}
 * and {@code charge}. {@code info} needs {@code oreradar.use}; the rest {@code oreradar.admin}.
 * {@code target} validates a spec exactly as a player's choice is validated.
 */
final class RadarCommand implements TabExecutor {
    static final String ADMIN_PERMISSION = "oreradar.admin";
    private static final List<String> SUBCOMMANDS = List.of("give", "reload", "info", "target", "charge");
    private static final int MAX_COMPLETIONS = 200;

    private final OreRadarPlugin plugin;

    RadarCommand(OreRadarPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) {
            usage(sender, label);
            return true;
        }
        if (!sender.hasPermission(sub.equals("info") ? RadarService.USE_PERMISSION : ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("You may not use /" + label + " " + sub + ".", Texts.WARN));
            return true;
        }
        switch (sub) {
            case "give" -> give(sender, label, args);
            case "reload" -> reload(sender);
            case "info" -> info(sender);
            case "target" -> target(sender, label, args);
            default -> charge(sender, label, args);
        }
        return true;
    }

    private static void usage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("/" + label + " give <player> [radar|station] | reload | info"
                + " | target <players> <spec|clear> | charge <players> <amount|full|empty>", Texts.MUTED));
    }

    private void give(CommandSender sender, String label, String[] args) {
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(Component.text("/" + label + " give <player> [radar|station]", Texts.MUTED));
            return;
        }
        String what = args.length == 3 ? args[2].toLowerCase(Locale.ROOT) : "radar";
        if (!what.equals("radar") && !what.equals("station")) {
            sender.sendMessage(Component.text("Give a radar or a station, not " + args[2] + ".", Texts.WARN));
            return;
        }
        for (Player p : players(sender, args[1])) {
            ServerRules r = plugin.rules();
            ItemStack item = what.equals("radar") ? RadarItems.newRadar(r) : RadarItems.newStation(0, r);
            for (ItemStack left : p.getInventory().addItem(item).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            sender.sendMessage(Component.text("Gave " + p.getName() + " " + (what.equals("radar") ? "an Ore Radar" : "a Charging Station")
                    + ".", Texts.ACCENT));
        }
    }

    private void reload(CommandSender sender) {
        ServerRules.Loaded loaded = plugin.reload();
        TargetRules<Material> rules = loaded.rules().rules();
        if (loaded.configFailed()) {
            sender.sendMessage(Component.text(ServerConfig.FILE_NAME + " could not be read (the log says where); kept the previous settings.",
                    Texts.WARN));
        }
        String warnings = loaded.warnings() == 0 ? "no warnings" : loaded.warnings() + " warning" + (loaded.warnings() == 1 ? "" : "s")
                + " in the log";
        sender.sendMessage(Component.text("Ore Radar reloaded: " + rules.allowed().size() + " trackable blocks, " + rules.groups().size()
                + " groups, " + rules.catalog().size() + " catalog entries; " + warnings + ".", Texts.ACCENT));
    }

    private void info(CommandSender sender) {
        ServerRules r = plugin.rules();
        ServerConfig c = r.config();
        TargetRules<Material> rules = r.rules();
        int auto = 0;
        for (TargetRules.Group<Material> g : rules.groups()) {
            if (g.id().startsWith(GroupSources.AUTO_PREFIX)) auto++;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Ore Radar " + plugin.getPluginMeta().getVersion() + " on " + Bukkit.getName() + " " + Bukkit.getMinecraftVersion());
        lines.add("Targets: " + rules.allowed().size() + " trackable blocks" + (c.allowAll() ? " (allowAll)" : "") + ", "
                + rules.groups().size() + " groups (" + auto + " ore, " + (rules.groups().size() - auto) + " from the config), "
                + plural(rules.categories().size(), "tab") + ", " + rules.catalog().size() + " catalog entries");
        lines.add("Scan: radius " + c.maxRadius() + " m, probe " + c.probeChunks() + " chunks, every " + c.scanIntervalTicks()
                + " ticks, budget " + Texts.number(c.scanBudgetMicros()) + " µs, " + c.maxMarkers() + " markers, active from "
                + c.activeFromMax() + " at most");
        lines.add("Features: outlines " + onOff(c.outlinesEnabled()) + (c.outlinesThroughWalls() ? " (through walls)" : "")
                + ", arrow " + onOff(c.arrowEnabled()) + " (precision " + c.arrowPrecisionBlocks() + ")"
                + ", distance " + onOff(c.arrowShowDistance()) + ", beep " + onOff(c.beepEnabled()) + " (every "
                + c.beepMinIntervalTicks() + "+ ticks within " + c.beepMaxRange() + " m)");
        lines.add("Energy: " + (c.energyEnabled() ? "capacity " + Texts.number(c.capacity()) + ", " + c.drainPerSecond()
                + "/s, written to the radar in use at each 5% step" : "off") + "; stations hold "
                + Texts.number(c.bufferCapacity()) + " and give " + Texts.number(c.chargeRatePerTick()) + "/tick, "
                + r.fuels().size() + " fuel items");
        lines.add("Dimensions denied: " + (c.dimensionsDeny().isEmpty() ? "none" : String.join(", ", c.dimensionsDeny())));
        lines.add("Paper: up to " + c.paperMaxDisplayOutlines() + " glowing outlines in #" + String.format("%06X", c.paperGlowColor())
                + "; the needle is the radar's own lodestone");
        lines.add("Now: " + plural(plugin.radars().players(), "player") + ", "
                + plural(plugin.stations().count(), "station") + " loaded (" + plugin.stations().workingCount() + " working)");
        ScanBudget.Load load = plugin.radars().scanLoad();
        lines.add("Scan time per tick over the last 5 s: " + Texts.number(load.averageMicros()) + " µs on average, "
                + Texts.number(load.maxMicros()) + " µs at most; the budget, shared by all players, is "
                + Texts.number(c.scanBudgetMicros()) + " µs");
        for (String l : lines) sender.sendMessage(Component.text(l, Texts.MUTED));
        if (sender instanceof Player p) plugin.radars().describe(p).forEach(sender::sendMessage);
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    private void target(CommandSender sender, String label, String[] args) {
        if (args.length != 3) {
            sender.sendMessage(Component.text("/" + label + " target <players> <spec|clear>", Texts.MUTED));
            return;
        }
        ServerRules r = plugin.rules();
        String spec = null;
        if (!args[2].equalsIgnoreCase("clear")) {
            TargetRules.Resolution<Material> res = r.rules().resolve(args[2]);
            if (!res.ok()) {
                sender.sendMessage(Component.text(args[2] + switch (res.status()) {
                    case INVALID -> " is not a target: use ns:block, #ns:tag or @group";
                    case UNKNOWN -> " names no block, tag or group on this server";
                    default -> " is not trackable under this server's rules";
                }, Texts.WARN));
                return;
            }
            spec = res.spec().toString();
        }
        for (Player p : players(sender, args[1])) {
            int slot = heldRadar(p);
            if (slot < 0) {
                sender.sendMessage(Component.text(p.getName() + " holds no radar.", Texts.WARN));
                continue;
            }
            PlayerInventory inv = p.getInventory();
            ItemStack item = inv.getItem(slot);
            RadarItems.setTarget(item, spec, r);
            inv.setItem(slot, item);
            sender.sendMessage(spec == null ? Component.text("Cleared " + p.getName() + "'s radar.", Texts.ACCENT)
                    : Component.text(p.getName() + "'s radar now tracks ", Texts.ACCENT).append(Texts.targetName(r.rules(), spec))
                            .append(Component.text(" (" + spec + ").", Texts.MUTED)));
        }
    }

    private void charge(CommandSender sender, String label, String[] args) {
        if (args.length != 3) {
            sender.sendMessage(Component.text("/" + label + " charge <players> <amount|full|empty>", Texts.MUTED));
            return;
        }
        ServerRules r = plugin.rules();
        int capacity = r.config().capacity();
        int amount;
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "full" -> amount = capacity;
            case "empty" -> amount = 0;
            default -> {
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    amount = -1;
                }
                if (amount < 0) {
                    sender.sendMessage(Component.text(args[2] + " is not a charge: a whole number from 0, full or empty.", Texts.WARN));
                    return;
                }
            }
        }
        int value = Math.min(amount, capacity);
        for (Player p : players(sender, args[1])) {
            int slot = heldRadar(p);
            if (slot < 0) {
                sender.sendMessage(Component.text(p.getName() + " holds no radar.", Texts.WARN));
                continue;
            }
            // What the radar in use owes is written first, so it cannot come off the new charge later.
            plugin.radars().settle(p);
            PlayerInventory inv = p.getInventory();
            ItemStack item = inv.getItem(slot);
            RadarItems.setCharge(item, value, r);
            inv.setItem(slot, item);
            sender.sendMessage(Component.text(p.getName() + "'s radar: " + Texts.charge(value, capacity) + ".", Texts.ACCENT));
        }
    }

    /** The main hand's radar, else the off hand's; -1 for none. */
    private static int heldRadar(Player p) {
        PlayerInventory inv = p.getInventory();
        if (RadarItems.isRadar(inv.getItemInMainHand())) return inv.getHeldItemSlot();
        if (RadarItems.isRadar(inv.getItemInOffHand())) return RadarProtocol.OFFHAND_SLOT;
        return -1;
    }

    /** A name or a vanilla selector (@a, @p, ...), players only. */
    private static List<Player> players(CommandSender sender, String arg) {
        List<Player> out = new ArrayList<>();
        try {
            for (Entity e : Bukkit.selectEntities(sender, arg)) {
                if (e instanceof Player p) out.add(p);
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text(arg + " is not a player name or selector: " + e.getMessage(), Texts.WARN));
            return out;
        }
        if (out.isEmpty()) sender.sendMessage(Component.text("No online player matches " + arg + ".", Texts.WARN));
        return out;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBCOMMANDS) {
                if (sender.hasPermission(s.equals("info") ? RadarService.USE_PERMISSION : ADMIN_PERMISSION)) options.add(s);
            }
        } else if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        } else if (args.length == 2 && List.of("give", "target", "charge").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
            options.addAll(List.of("@a", "@p", "@r", "@s"));
        } else if (args.length == 3) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give" -> options.addAll(List.of("radar", "station"));
                case "target" -> {
                    options.add("clear");
                    for (ConfigPacket.CatalogEntry e : plugin.rules().rules().catalog()) options.add(e.spec());
                }
                case "charge" -> options.addAll(List.of("full", "empty", String.valueOf(plugin.rules().config().capacity() / 2)));
                default -> {
                    return List.of();
                }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(prefix) && out.size() < MAX_COMPLETIONS) out.add(o);
        }
        return out;
    }
}
