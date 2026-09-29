package net.shurui.dev.shuruis_raid_bosses.command;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Stats;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.shurui.dev.shuruis_raid_bosses.data.RaidData;
import net.shurui.dev.shuruis_raid_bosses.entity.RaidNpcs;
import net.shurui.dev.shuruis_raid_bosses.network.OpenHubPacket;
import net.shurui.dev.shuruis_raid_bosses.network.RaidNet;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidInstance;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidManager;
import net.shurui.dev.shuruis_raid_bosses.util.TextUtil;
import net.shurui.dev.shuruis_raid_bosses.Config;
import net.shurui.dev.shuruis_raid_bosses.data.ZSoulData;
import net.shurui.dev.shuruis_raid_bosses.dmz.DmzHooks;
import net.shurui.dev.shuruis_raid_bosses.dmz.ZSoulManager;
import net.shurui.dev.shuruis_raid_bosses.item.ZSoulTier;
import net.shurui.dev.shuruis_raid_bosses.item.ZStat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Command tree rooted at {@code /rg raid} (legacy hidden aliases {@code /srb}, {@code /raidboss}). Raids
 * are referenced by display name. Player commands need {@link Permissions#USE}, management
 * {@link Permissions#ADMIN}.
 */
public final class RaidCommand {
    private RaidCommand() {}

    private static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, b) -> {
        List<String> names = new ArrayList<>();
        for (RaidBossDef def : RaidData.get(ctx.getSource().getServer()).allDefs().values()) names.add(def.name);
        return SharedSuggestionProvider.suggest(names.stream().map(RaidCommand::quoteIfNeeded), b);
    };

    private static final SuggestionProvider<CommandSourceStack> ZSOUL_STATS = (ctx, b) -> {
        List<String> names = new ArrayList<>();
        names.add("all");
        for (ZStat stat : ZStat.values()) if (stat.hasDedicatedSoul()) names.add(stat.itemName);
        return SharedSuggestionProvider.suggest(names, b);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // The branded /srb root moved under the shared /rg tree: /srb start is now /rg raid start. Brigadier
        // merges this /rg registration with the other addons' into one node.
        var root = Commands.literal("raid");

        root.then(Commands.literal("list").requires(s -> Permissions.check(s, Permissions.USE, "rg.raid.list")).executes(RaidCommand::list));
        root.then(Commands.literal("join").requires(s -> Permissions.check(s, Permissions.USE, "rg.raid.join")).then(nameArg().executes(RaidCommand::join)));
        root.then(Commands.literal("leave").requires(s -> Permissions.check(s, Permissions.USE, "rg.raid.leave")).then(nameArg().executes(RaidCommand::leave)));
        root.then(Commands.literal("status").requires(s -> Permissions.check(s, Permissions.USE, "rg.raid.status")).then(nameArg().executes(RaidCommand::status)));

        root.then(Commands.literal("edit").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.raid.edit")).executes(RaidCommand::openEditor));
        root.then(Commands.literal("open").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.raid.open")).then(nameArg().executes(RaidCommand::open)));
        root.then(Commands.literal("start").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.raid.start")).then(nameArg().executes(RaidCommand::start)));
        root.then(Commands.literal("cancel").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.raid.cancel")).then(nameArg().executes(RaidCommand::cancel)));

        root.then(Commands.literal("npc").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.raid.npc"))
                .then(Commands.literal("spawn").then(nameArg().executes(RaidCommand::npcSpawn)))
                .then(Commands.literal("bind").then(nameArg().executes(RaidCommand::npcBind)))
                .then(Commands.literal("remove")
                        .executes(RaidCommand::npcRemoveAll)
                        .then(nameArg().executes(RaidCommand::npcRemove))));

        root.then(Commands.literal("soul").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.raid.soul"))
                .then(nameArg().executes(RaidCommand::giveSoul)));

        // Beyond-cap points are added in the DMZ stats GUI, not via command, so only read-only info remains
        // plus an admin reset.
        root.then(Commands.literal("zsoul").requires(s -> Permissions.check(s, Permissions.USE, "rg.raid.zsoul"))
                .then(Commands.literal("info").executes(RaidCommand::zsoulInfo))
                .then(Commands.literal("reset").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.raid.zsoul.reset"))
                        .executes(RaidCommand::zsoulReset)
                        .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                                .executes(RaidCommand::zsoulResetOther))));

        dispatcher.register(Commands.literal("rg").then(root));
        // Legacy /srb and /raidboss still work, redirected onto /rg raid. SU keeps them out of the client
        // command tree and migrates command.srb.* / command.raidboss.* onto command.rg.raid.*.
        var raidNode = dispatcher.getRoot().getChild("rg").getChild("raid");
        dispatcher.register(Commands.literal("srb").redirect(raidNode));
        dispatcher.register(Commands.literal("raidboss").redirect(raidNode));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> nameArg() {
        return Commands.argument("name", StringArgumentType.greedyString()).suggests(NAMES);
    }

    private static int openEditor(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        RaidNet.sendToPlayer(new OpenHubPacket(), player);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        var defs = RaidData.get(server).allDefs();
        if (defs.isEmpty()) return key(ctx, "command.dmz_ragnarok.raid.list.empty");
        key(ctx, "command.dmz_ragnarok.raid.list.header");
        RaidManager m = RaidManager.get();
        for (RaidBossDef def : defs.values()) {
            RaidInstance inst = m == null ? null : m.instance(def.id);
            String state = inst == null ? "IDLE" : inst.state().name();
            Component bounds = def.hasArena()
                    ? Component.translatable("command.dmz_ragnarok.raid.list.bounds_ready")
                    : Component.translatable("command.dmz_ragnarok.raid.list.bounds_none");
            key(ctx, "command.dmz_ragnarok.raid.list.row", colored(def.name), bounds, state);
        }
        return 1;
    }

    private static int join(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        RaidBossDef def = resolve(ctx);
        if (def == null) return key(ctx, "command.dmz_ragnarok.raid.no_raid");
        // A raid on another open world takes the player there to join.
        if (net.shurui.dev.shuruis_raid_bosses.raid.RaidNetwork.routeJoin(player, def.id)) return 1;
        RaidManager m = RaidManager.get();
        if (m != null) {
            switch (m.join(def.id, player)) {
                case OK -> {
                    // Placement goes through the raid's surface-snap path, not the raw NPC coordinates,
                    // which had no snap and could land the player underground.
                    RaidInstance inst = m.instance(def.id);
                    if (inst != null && inst.placeInArena(player)) {
                        key(ctx, "command.dmz_ragnarok.raid.join.teleported", colored(def.npcName), colored(def.name));
                    }
                    key(ctx, "command.dmz_ragnarok.raid.join.ok", colored(def.name));
                }
                case ALREADY -> key(ctx, "command.dmz_ragnarok.raid.join.already");
                case CLOSED -> key(ctx, "command.dmz_ragnarok.raid.join.closed");
                case FULL -> key(ctx, "command.dmz_ragnarok.raid.join.full");
                case NO_CHARACTER -> key(ctx, "command.dmz_ragnarok.raid.join.no_character");
                case NO_SUCH_RAID -> key(ctx, "command.dmz_ragnarok.raid.no_such_raid");
            }
        }
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        RaidInstance inst = inst(ctx);
        if (inst != null && inst.leave(player.getUUID())) key(ctx, "command.dmz_ragnarok.raid.leave.ok");
        else key(ctx, "command.dmz_ragnarok.raid.leave.not_signed_up");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        RaidInstance inst = inst(ctx);
        if (inst == null) return key(ctx, "command.dmz_ragnarok.raid.no_raid");
        ctx.getSource().sendSystemMessage(inst.statusSummary());
        return 1;
    }

    private static int open(CommandContext<CommandSourceStack> ctx) {
        RaidBossDef def = resolve(ctx);
        if (def == null) return key(ctx, "command.dmz_ragnarok.raid.no_raid");
        if (!def.hasArena()) return key(ctx, "command.dmz_ragnarok.raid.open.no_arena");
        RaidManager m = RaidManager.get();
        if (m != null && m.openSignups(def.id, def.signupMinutes)) key(ctx, "command.dmz_ragnarok.raid.open.ok", colored(def.name));
        else key(ctx, "command.dmz_ragnarok.raid.open.already_running");
        return 1;
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        RaidBossDef def = resolve(ctx);
        RaidManager m = RaidManager.get();
        if (def != null && m != null && m.forceStart(def.id)) key(ctx, "command.dmz_ragnarok.raid.start.ok");
        else key(ctx, "command.dmz_ragnarok.raid.start.not_open");
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) {
        RaidBossDef def = resolve(ctx);
        RaidManager m = RaidManager.get();
        if (def != null && m != null && m.cancel(def.id)) key(ctx, "command.dmz_ragnarok.raid.cancel.ok");
        else key(ctx, "command.dmz_ragnarok.raid.no_such_raid");
        return 1;
    }

    private static int npcSpawn(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        RaidBossDef def = resolve(ctx);
        if (def == null) return key(ctx, "command.dmz_ragnarok.raid.no_raid");
        Entity npc = RaidNpcs.spawn(player.serverLevel(), def,
                new Vec3(player.getX(), player.getY(), player.getZ()), player.getYRot());
        if (npc == null) return key(ctx, "command.dmz_ragnarok.raid.npc.spawn_failed", def.npcEntityType);
        return key(ctx, "command.dmz_ragnarok.raid.npc.spawned", colored(def.npcName), def.npcEntityType, colored(def.name));
    }

    private static int npcRemove(CommandContext<CommandSourceStack> ctx) {
        RaidBossDef def = resolve(ctx);
        if (def == null) return key(ctx, "command.dmz_ragnarok.raid.no_raid");
        int removed = RaidNpcs.removeAll(ctx.getSource().getServer(), def.id);
        if (removed == 0) return key(ctx, "command.dmz_ragnarok.raid.npc.none_for", colored(def.name));
        return key(ctx, removed == 1 ? "command.dmz_ragnarok.raid.npc.removed_one_for"
                : "command.dmz_ragnarok.raid.npc.removed_many_for", removed, colored(def.name));
    }

    private static int npcRemoveAll(CommandContext<CommandSourceStack> ctx) {
        int removed = RaidNpcs.removeAll(ctx.getSource().getServer(), null);
        if (removed == 0) return key(ctx, "command.dmz_ragnarok.raid.npc.none");
        return key(ctx, removed == 1 ? "command.dmz_ragnarok.raid.npc.removed_one"
                : "command.dmz_ragnarok.raid.npc.removed_many", removed);
    }

    private static int giveSoul(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        RaidBossDef def = resolve(ctx);
        if (def == null) return key(ctx, "command.dmz_ragnarok.raid.no_raid");
        net.minecraft.world.item.ItemStack soul = net.shurui.dev.shuruis_raid_bosses.item.RaidSoulItem.create(
                net.shurui.dev.shuruis_raid_bosses.registry.ModItems.RAID_SOUL.get(), def);
        // add() mutates the stack down to the overflow and returns true on a partial placement, so the leftover
        // has to be read off the stack itself (see CrateManager.grantReward, ticket 800).
        player.getInventory().add(soul);
        if (!soul.isEmpty()) player.drop(soul, false);
        return key(ctx, "command.dmz_ragnarok.raid.soul.given", colored(def.name));
    }

    private static int npcBind(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        RaidBossDef def = resolve(ctx);
        if (def == null) return key(ctx, "command.dmz_ragnarok.raid.no_raid");
        Entity nearest = null;
        double best = 36.0;
        for (Entity e : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(6))) {
            if (e == player || e instanceof ServerPlayer) continue;
            double d = e.distanceToSqr(player);
            if (d < best) { best = d; nearest = e; }
        }
        if (nearest == null) return key(ctx, "command.dmz_ragnarok.raid.npc.none_to_bind");
        RaidNpcs.tag(nearest, def.id);
        return key(ctx, "command.dmz_ragnarok.raid.npc.bound", nearest.getType().getDescription(), colored(def.name));
    }

    private static RaidBossDef resolve(CommandContext<CommandSourceStack> ctx) {
        String name = unquote(StringArgumentType.getString(ctx, "name")).trim();
        RaidData data = RaidData.get(ctx.getSource().getServer());
        for (RaidBossDef def : data.allDefs().values()) {
            if (def.name.equalsIgnoreCase(name)) return def;
        }
        return data.getDef(name);
    }

    private static RaidInstance inst(CommandContext<CommandSourceStack> ctx) {
        RaidBossDef def = resolve(ctx);
        RaidManager m = RaidManager.get();
        return (def == null || m == null) ? null : m.instance(def.id);
    }

    private static String quoteIfNeeded(String s) {
        return s.contains(" ") ? '"' + s + '"' : s;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    private static int zsoulDisabledMsg(CommandContext<CommandSourceStack> ctx) {
        // "disabled" only when the real key installed the Z-Soul logic; a jar that merely claims the key still needs it
        return key(ctx, net.shurui.dev.shuruis_raid_bosses.KeyGate.unlocked()
                && net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks.available()
                ? "command.dmz_ragnarok.raid.zsoul.disabled"
                : "command.dmz_ragnarok.raid.zsoul.needs_key");
    }

    private static int zsoulInfo(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        if (!ZSoulManager.enabled()) return zsoulDisabledMsg(ctx);
        int cap = DmzHooks.globalStatCap(player);
        if (cap <= 0) return key(ctx, "command.dmz_ragnarok.raid.no_character");
        ZSoulData data = ZSoulData.get(ctx.getSource().getServer());
        EnumMap<ZStat, ZSoulTier> worn = ZSoulManager.wornTiers(player);
        key(ctx, "command.dmz_ragnarok.raid.zsoul.header", cap);
        for (ZStat stat : ZStat.values()) {
            int banked = data.get(player.getUUID(), stat);
            ZSoulTier tier = worn.get(stat);
            int max = tier == null ? 0 : tier.maxBonus(cap);
            int applied = tier == null ? 0 : Math.min(banked, max);
            Component tierStr = tier == null
                    ? Component.translatable("command.dmz_ragnarok.raid.zsoul.not_worn")
                    : Component.translatable("command.dmz_ragnarok.raid.zsoul.tier",
                            tier.colour + capitalise(tier.id), max);
            key(ctx, "command.dmz_ragnarok.raid.zsoul.line",
                    Component.translatable("stat.dmz_ragnarok.raid." + stat.itemNameOrDefense()), applied, banked, tierStr);
        }
        return 1;
    }

    /** Clear the sender's banked beyond-cap Z-Soul points (all stats back to 0). */
    private static int zsoulReset(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        return player == null ? 0 : doZsoulReset(ctx, player);
    }

    /** Admin: clear another player's banked beyond-cap Z-Soul points. */
    private static int zsoulResetOther(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return doZsoulReset(ctx, net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player"));
    }

    private static int doZsoulReset(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        if (target.getServer() == null) return 0;
        ZSoulData data = ZSoulData.get(target.getServer());
        for (ZStat stat : ZStat.values()) {
            data.set(target.getUUID(), stat, 0);
        }
        ZSoulManager.refresh(target);
        return key(ctx, "command.dmz_ragnarok.raid.zsoul.reset", target.getGameProfile().getName());
    }

    private static ZStat statByName(String name) {
        for (ZStat stat : ZStat.values()) {
            if (stat.hasDedicatedSoul() && stat.itemName.equals(name)) return stat;
        }
        return null;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static ServerPlayer playerOrNull(CommandContext<CommandSourceStack> ctx) {
        try {
            return ctx.getSource().getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.translatable("command.dmz_ragnarok.raid.player_only"));
            return null;
        }
    }

    /**
     * Send a translated system message. Raid display names may carry owner-entered {@code &} colour codes,
     * so wrap them in {@link TextUtil#color} first ({@link #colored}) for those to render.
     */
    private static int key(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        ctx.getSource().sendSystemMessage(Component.translatable(key, args));
        return 1;
    }

    /** A raid display name as a coloured Component (so owner {@code &} codes still render inside a key). */
    private static Component colored(String raw) {
        return TextUtil.color(raw);
    }
}
