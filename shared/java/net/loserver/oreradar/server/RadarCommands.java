package net.loserver.oreradar.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.core.config.LoadResult;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.designation.TargetRules;
import net.loserver.oreradar.core.net.ConfigPacket;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.item.TargetNames;
import net.loserver.oreradar.platform.Services;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * {@code /oreradar} (DESIGN §11): {@code reload} and {@code info} for admins, and {@code target}
 * and {@code charge} for admins and tests. Each loader registers it with its command event.
 */
public final class RadarCommands {
    private static final String CLEAR = "clear";
    private static final int ADMIN = 2;

    private static final SuggestionProvider<CommandSourceStack> SPECS = (context, builder) -> {
        RadarServer server = RadarServer.get();
        Stream<String> specs = server == null ? Stream.empty()
                : server.rules().targets().catalog().stream().map(ConfigPacket.CatalogEntry::spec);
        return SharedSuggestionProvider.suggest(Stream.concat(Stream.of(CLEAR), specs), builder);
    };

    private RadarCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oreradar")
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(ADMIN))
                        .executes(RadarCommands::reload))
                .then(Commands.literal("info")
                        .executes(RadarCommands::info))
                .then(Commands.literal("target")
                        .requires(source -> source.hasPermission(ADMIN))
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("spec", StringArgumentType.greedyString())
                                        .suggests(SPECS)
                                        .executes(RadarCommands::target))))
                .then(Commands.literal("charge")
                        .requires(source -> source.hasPermission(ADMIN))
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.literal("full").executes(context -> charge(context, -1)))
                                .then(Commands.literal("empty").executes(context -> charge(context, 0)))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(context -> charge(context, IntegerArgumentType.getInteger(context, "amount")))))));
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        RadarServer server = running(context.getSource());
        if (server == null) return 0;
        LoadResult<ServerConfig> loaded = server.reload();
        String file = server.configFile().getFileName().toString();
        if (loaded.failed()) {
            String why = loaded.warnings().isEmpty() ? "" : loaded.warnings().get(0);
            context.getSource().sendFailure(Component.translatable("oreradar.command.reload.failed", file, why));
            return 0;
        }
        int targets = server.rules().targets().catalog().size();
        // The file's own warnings and the rules', such as a catalog too large for one packet.
        int warnings = loaded.warnings().size() + server.ruleWarnings();
        context.getSource().sendSuccess(() -> Component.translatable("oreradar.command.reload.done", file, targets, warnings),
                true);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        RadarServer server = running(source);
        if (server == null) return 0;
        ServerConfig c = server.config();
        TargetRules<Block> targets = server.rules().targets();
        int untrackable = 0;
        for (Block ignored : RegistryBlockLookup.INSTANCE.tag(TargetRules.UNTRACKABLE_TAG)) untrackable++;
        String dimensions = c.dimensionsDeny().isEmpty() ? "-" : String.join(", ", c.dimensionsDeny());

        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("oreradar.info.header", Services.PLATFORM.getPlatformName(), RadarProtocol.VERSION));
        lines.add(Component.translatable("oreradar.info.targets", targets.allowed().size(), CommonComponents.optionStatus(c.allowAll()),
                targets.groups().size(), targets.catalog().size(), targets.categories().size()));
        lines.add(Component.translatable("oreradar.info.exclusions", c.denyList().size(), untrackable));
        lines.add(Component.translatable("oreradar.info.scan", c.maxRadius(), c.probeChunks(), c.scanIntervalTicks(),
                c.scanBudgetMicros(), c.maxMarkers(), c.activeFromMax().name()));
        lines.add(Component.translatable("oreradar.info.dimensions", dimensions));
        lines.add(Component.translatable("oreradar.info.features", CommonComponents.optionStatus(c.outlinesEnabled()),
                CommonComponents.optionStatus(c.outlinesThroughWalls()), CommonComponents.optionStatus(c.arrowEnabled()),
                c.arrowPrecisionBlocks(), CommonComponents.optionStatus(c.arrowShowDistance()),
                CommonComponents.optionStatus(c.beepEnabled())));
        EnergySettings energy = EnergySettings.of(c);
        lines.add(energy.enabled()
                ? Component.translatable("oreradar.info.energy", RadarItem.formatNumber(c.capacity()), c.drainPerSecond(),
                        CommonComponents.optionStatus(energy.externalChargers()), c.fePerCharge(), c.trEnergyPerCharge())
                : Component.translatable("oreradar.info.energy_off"));
        lines.add(Component.translatable("oreradar.info.players", server.playersWithOreRadar()));
        ServerPlayer self = source.getPlayer();
        if (self != null) lines.add(lastScan(server.player(self.getUUID())));
        lines.forEach(line -> source.sendSuccess(() -> line, false));
        return 1;
    }

    private static Component lastScan(PlayerRadar radar) {
        if (radar == null || (!radar.saidHello() && radar.incompatibleProtocol() == 0)) {
            return Component.translatable("oreradar.info.no_hello");
        }
        if (radar.incompatibleProtocol() != 0) {
            return Component.translatable("oreradar.info.incompatible", radar.incompatibleProtocol(), RadarProtocol.VERSION);
        }
        PlayerRadar.ScanStats scan = radar.lastScan();
        if (scan == null) return Component.translatable("oreradar.info.no_scan");
        return Component.translatable("oreradar.info.last_scan", scan.micros(), scan.ticks(), scan.matches(), scan.status().name());
    }

    private static int target(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        RadarServer server = running(source);
        if (server == null) return 0;
        String spec = StringArgumentType.getString(context, "spec").trim();
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
        TargetRules.Resolution<Block> resolution = spec.equals(CLEAR) ? null : server.rules().targets().resolve(spec);
        if (resolution != null && !resolution.ok()) {
            source.sendFailure(resolution.status() == TargetRules.Status.NOT_ALLOWED
                    ? Component.translatable("oreradar.command.target.not_allowed", spec)
                    : Component.translatable("oreradar.command.target.unknown", spec));
            return 0;
        }
        int done = 0;
        for (ServerPlayer player : players) {
            ItemStack radar = heldRadar(player);
            if (radar.isEmpty()) {
                source.sendFailure(Component.translatable("oreradar.command.no_radar", player.getDisplayName()));
                continue;
            }
            RadarItem.setTarget(radar, resolution == null ? null : resolution.spec().toString());
            PlayerRadar state = server.player(player.getUUID());
            if (state != null) state.requestScan();
            Component feedback = resolution == null
                    ? Component.translatable("oreradar.command.target.cleared", player.getDisplayName())
                    : Component.translatable("oreradar.command.target.set", player.getDisplayName(), TargetNames.of(resolution));
            source.sendSuccess(() -> feedback, true);
            done++;
        }
        return done;
    }

    /** @param amount the charge to set, or -1 for full */
    private static int charge(CommandContext<CommandSourceStack> context, int amount) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (running(source) == null) return 0;
        EnergySettings energy = EnergySettings.current();
        if (!energy.enabled()) {
            source.sendFailure(Component.translatable("oreradar.command.charge.disabled"));
            return 0;
        }
        int charge = amount < 0 ? energy.capacity() : Math.min(amount, energy.capacity());
        int done = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(context, "players")) {
            ItemStack radar = heldRadar(player);
            if (radar.isEmpty()) {
                source.sendFailure(Component.translatable("oreradar.command.no_radar", player.getDisplayName()));
                continue;
            }
            RadarItem.setCharge(radar, charge);
            source.sendSuccess(() -> Component.translatable("oreradar.command.charge.set", player.getDisplayName(),
                    RadarItem.formatNumber(charge), RadarItem.formatNumber(energy.capacity())), true);
            done++;
        }
        return done;
    }

    /** The radar in the main hand, else the off hand; empty if neither holds one. */
    private static ItemStack heldRadar(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof RadarItem) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static RadarServer running(CommandSourceStack source) {
        RadarServer server = RadarServer.get();
        if (server == null) source.sendFailure(Component.translatable("oreradar.command.not_running"));
        return server;
    }
}
