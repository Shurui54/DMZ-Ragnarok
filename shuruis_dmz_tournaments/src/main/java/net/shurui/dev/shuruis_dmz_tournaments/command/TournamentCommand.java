package net.shurui.dev.shuruis_dmz_tournaments.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.shurui.dev.shuruis_dmz_tournaments.data.TournamentData;
import net.shurui.dev.shuruis_dmz_tournaments.region.Region;
import net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpcs;
import net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet;
import net.shurui.dev.shuruis_dmz_tournaments.reward.TitleDisplay;
import net.shurui.dev.shuruis_dmz_tournaments.reward.TitleManager;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentDef;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentInstance;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentManager;
import net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Command tree rooted at {@code /rg tourney} (legacy hidden aliases {@code /sdt} and {@code /tournament}).
 * Tournaments are referenced by display name. Player commands need {@code USE}, management needs {@code ADMIN}.
 */
public final class TournamentCommand {
    private TournamentCommand() {}

    /** Suggest tournament display names (quoted so names with spaces work). */
    private static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, b) -> {
        List<String> names = new ArrayList<>();
        for (TournamentDef def : TournamentData.get(ctx.getSource().getServer()).allDefs().values()) names.add(def.name);
        return SharedSuggestionProvider.suggest(names.stream().map(TournamentCommand::quoteIfNeeded), b);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // the branded /sdt root moved onto the shared /rg tree: /sdt start is now /rg tourney start. Brigadier
        // merges this /rg registration with the other addons' into one node.
        var root = Commands.literal("tourney");

        root.then(Commands.literal("list").requires(s -> Permissions.check(s, Permissions.USE, "rg.tourney.list")).executes(TournamentCommand::list));
        root.then(Commands.literal("join").requires(s -> Permissions.check(s, Permissions.USE, "rg.tourney.join")).then(nameArg().executes(TournamentCommand::join)));
        root.then(Commands.literal("leave").requires(s -> Permissions.check(s, Permissions.USE, "rg.tourney.leave")).then(nameArg().executes(TournamentCommand::leave)));
        root.then(Commands.literal("tpwait").requires(s -> Permissions.check(s, Permissions.USE, "rg.tourney.tpwait")).then(nameArg().executes(TournamentCommand::tpWait)));
        root.then(Commands.literal("status").requires(s -> Permissions.check(s, Permissions.USE, "rg.tourney.status")).then(nameArg().executes(TournamentCommand::status)));
        root.then(Commands.literal("loadout").requires(s -> Permissions.check(s, Permissions.USE, "rg.tourney.loadout")).executes(TournamentCommand::loadout));
        root.then(Commands.literal("cancelcharacter").requires(s -> Permissions.check(s, Permissions.USE, "rg.tourney.cancelcharacter"))
                .executes(TournamentCommand::cancelCharacter));

        root.then(Commands.literal("edit").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.tourney.edit")).executes(TournamentCommand::openEditor));
        root.then(Commands.literal("open").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.tourney.open")).then(nameArg().executes(TournamentCommand::open)));
        root.then(Commands.literal("start").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.tourney.start")).then(nameArg().executes(TournamentCommand::start)));
        root.then(Commands.literal("cancel").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.tourney.cancel")).then(nameArg().executes(TournamentCommand::cancel)));
        root.then(Commands.literal("forceexit").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.tourney.forceexit"))
                .then(Commands.argument("player", EntityArgument.player()).executes(TournamentCommand::forceExit)));

        // Manual coordinate entry, an alternative to a WorldEdit selection. Order is <region> <c1> <c2> <name>
        // because the greedy tournament name must come last.
        root.then(Commands.literal("bounds").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.tourney.bounds"))
                .then(boundsRegion("arena"))
                .then(boundsRegion("waiting"))
                .then(boundsRegion("stands")));

        // Team sign-up (2v2 / 3v3). The greedy tournament name comes last, so team name / id come first.
        root.then(Commands.literal("team").requires(s -> Permissions.check(s, Permissions.USE, "rg.tourney.team"))
                .then(Commands.literal("create").then(Commands.argument("team", StringArgumentType.string())
                        .then(nameArg().executes(TournamentCommand::teamCreate))))
                .then(Commands.literal("join").then(Commands.argument("teamId", IntegerArgumentType.integer(1))
                        .then(nameArg().executes(TournamentCommand::teamJoin))))
                .then(Commands.literal("leave").then(nameArg().executes(TournamentCommand::teamLeave))));

        root.then(Commands.literal("npc").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.tourney.npc"))
                .then(Commands.literal("spawn").then(nameArg().executes(TournamentCommand::npcSpawn)))
                .then(Commands.literal("bind").then(nameArg().executes(TournamentCommand::npcBind)))
                .then(Commands.literal("setgroup").then(Commands.argument("group", StringArgumentType.greedyString())
                        .executes(TournamentCommand::npcSetGroup)))
                .then(Commands.literal("remove").executes(TournamentCommand::npcRemove))
                .then(Commands.literal("removeall").executes(TournamentCommand::npcRemoveAll)));

        root.then(Commands.literal("title").requires(s -> Permissions.check(s, Permissions.ADMIN, "rg.tourney.title"))
                .then(Commands.literal("list").executes(TournamentCommand::titleList))
                .then(Commands.literal("set").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("player", EntityArgument.player()).executes(TournamentCommand::titleSet))))
                .then(Commands.literal("clear").then(Commands.argument("id", StringArgumentType.word())
                        .executes(TournamentCommand::titleClear))));

        dispatcher.register(Commands.literal("rg").then(root));
        // Legacy hidden aliases: /sdt and /tournament redirect onto /rg tourney. SU keeps them out of the client
        // command tree and migrates command.sdt.* / command.tournament.* onto command.rg.tourney.*.
        var tourneyNode = dispatcher.getRoot().getChild("rg").getChild("tourney");
        dispatcher.register(Commands.literal("sdt").redirect(tourneyNode));
        dispatcher.register(Commands.literal("tournament").redirect(tourneyNode));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> nameArg() {
        return Commands.argument("name", StringArgumentType.greedyString()).suggests(NAMES);
    }

    /** {@code /rg tourney bounds <regionKey> <corner1> <corner2> <name...>} for one region key. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> boundsRegion(String regionKey) {
        return Commands.literal(regionKey)
                .then(Commands.argument("corner1", BlockPosArgument.blockPos())
                        .then(Commands.argument("corner2", BlockPosArgument.blockPos())
                                .then(Commands.argument("name", StringArgumentType.greedyString()).suggests(NAMES)
                                        .executes(ctx -> setBounds(ctx, regionKey)))));
    }

    private static int setBounds(CommandContext<CommandSourceStack> ctx, String regionKey) {
        TournamentDef def = resolve(ctx);
        if (def == null) return msg(ctx, "&cNo tournament by that name.");
        BlockPos c1 = BlockPosArgument.getBlockPos(ctx, "corner1");
        BlockPos c2 = BlockPosArgument.getBlockPos(ctx, "corner2");
        ServerLevel level = ctx.getSource().getLevel();
        Region region = new Region(level.dimension(), c1, c2);
        switch (regionKey) {
            case "arena" -> def.arena = region;
            case "waiting" -> def.waiting = region;
            case "stands" -> def.stands = region;
            default -> { return msg(ctx, "&cUnknown region '" + regionKey + "'."); }
        }
        TournamentData.get(ctx.getSource().getServer()).putDef(def);
        return msg(ctx, "&a" + regionKey + " bounds set: " + region);
    }

    private static int openEditor(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        TournamentNet.openHub(player);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        var defs = TournamentData.get(server).allDefs();
        if (defs.isEmpty()) return msg(ctx, "&7No tournaments. Use /rg tourney edit to create one.");
        msg(ctx, "&6Tournaments:");
        TournamentManager m = TournamentManager.get();
        for (TournamentDef def : defs.values()) {
            TournamentInstance inst = m == null ? null : m.instance(def.id);
            String state = inst == null ? "IDLE" : inst.state().name();
            String bounds = def.hasAllRegions() ? "&aready" : "&cno bounds";
            msg(ctx, "&e" + def.name + " &7[" + bounds + "&7, " + state + "]");
        }
        return 1;
    }

    private static int join(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        TournamentDef def = resolve(ctx);
        if (def == null) return msg(ctx, "&cNo tournament by that name.");
        // on a shard network, a tournament open on another world takes the player there to enter
        if (net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentNetwork.routeJoin(player, def.id)) return 1;
        // register the sign-up; no teleport, joining players stay put until the tournament starts
        TournamentManager m = TournamentManager.get();
        if (m != null) {
            switch (m.join(def.id, player)) {
                case OK -> msg(ctx, "&aYou have signed up for '" + def.name + "'!");
                case ALREADY -> msg(ctx, "&eYou are already signed up.");
                case CLOSED -> msg(ctx, "&7Sign-ups aren't open yet - talk to the NPC when they are.");
                case FULL -> msg(ctx, "&cThat tournament is full.");
                case NO_CHARACTER -> msg(ctx, "&cCreate a DragonMineZ character first.");
                case TITLE_HOLDER -> {
                    var inst = m.instance(def.id);
                    msg(ctx, net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentInstance.titleHolderRefusal(
                            inst != null ? inst.barringTitlesFor(player.getUUID()) : null));
                }
                case NO_SUCH_TOURNAMENT -> msg(ctx, "&cNo such tournament.");
            }
        }
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        TournamentInstance inst = inst(ctx);
        if (inst != null && inst.leave(player.getUUID())) msg(ctx, "&eYou have withdrawn.");
        else msg(ctx, "&cYou were not signed up.");
        return 1;
    }

    private static int tpWait(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        TournamentInstance inst = inst(ctx);
        if (inst == null) return msg(ctx, "&cNo tournament by that name.");
        if (!inst.isSignedUp(player.getUUID()) && !inst.isContestant(player.getUUID()))
            return msg(ctx, "&cOnly signed-up fighters can teleport to the waiting area.");
        if (!inst.teleportToWaiting(player)) return msg(ctx, "&cThe waiting area is not configured.");
        return msg(ctx, "&aTeleported to the fighter waiting area.");
    }

    // opens the attack loadout pick-list. In the creation sandbox this is the "finish" step (its save finalizes
    // the character); otherwise it edits an existing loadout.
    private static int loadout(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        if (net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.isCreating(player)) {
            net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet.openCreation(player);
        } else {
            net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet.openLoadout(player);
        }
        return 1;
    }

    // abort an unfinished tournament-character build and hand the real character straight back. Finishing the
    // sandbox needs DMZ's own creation done, every allocation spent AND a move pick, so a player who opened it by
    // mistake had no way back while online: their TP gain is cancelled for as long as the character is held, which
    // read to them as "I get no TP from anything" (tickets 811 and 812, 2026-09-15). A player in a real match is
    // not creating, so this can never pull a live fighter out of a tournament.
    private static int cancelCharacter(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        if (!net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.isCreating(player)) {
            return msg(ctx, "&cYou are not building a tournament character.");
        }
        net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.exit(player);
        return msg(ctx, "&eBuild cancelled. You are back on your own character.");
    }

    // staff unstick: put a player back on their real character. Refused while they are an active fighter in a live
    // match, where the swap belongs to the tournament itself.
    private static int forceExit(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (!net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.isActive(target)) {
            return msg(ctx, "&c" + target.getGameProfile().getName() + " is not on a tournament character.");
        }
        TournamentManager manager = TournamentManager.get();
        if (manager != null && manager.isActiveFighter(target.getUUID())) {
            return msg(ctx, "&cThey are fighting in a live tournament. Cancel the tournament first.");
        }
        net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.exit(target);
        target.sendSystemMessage(TextUtil.color("&eYou are back on your own character."));
        return msg(ctx, "&a" + target.getGameProfile().getName() + " is back on their own character.");
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        TournamentInstance inst = inst(ctx);
        if (inst == null) return msg(ctx, "&cNo tournament by that name.");
        ctx.getSource().sendSystemMessage(inst.statusSummary());
        return 1;
    }

    private static int open(CommandContext<CommandSourceStack> ctx) {
        TournamentDef def = resolve(ctx);
        if (def == null) return msg(ctx, "&cNo tournament by that name.");
        if (!def.hasAllRegions()) return msg(ctx, "&cSet arena, waiting and stands bounds first (/rg tourney edit).");
        TournamentManager m = TournamentManager.get();
        if (m != null && m.openSignups(def.id, def.signupMinutes)) msg(ctx, "&aSign-ups opened for '" + def.name + "'.");
        else msg(ctx, "&cThat tournament is already running.");
        return 1;
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        TournamentDef def = resolve(ctx);
        TournamentManager m = TournamentManager.get();
        if (def != null && m != null && m.forceStart(def.id)) msg(ctx, "&aStarting now!");
        else msg(ctx, "&cSign-ups are not open for that tournament.");
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) {
        TournamentDef def = resolve(ctx);
        TournamentManager m = TournamentManager.get();
        if (def != null && m != null && m.cancel(def.id)) msg(ctx, "&eTournament cancelled.");
        else msg(ctx, "&cNo such tournament.");
        return 1;
    }

    private static int npcSpawn(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        TournamentDef def = resolve(ctx);
        if (def == null) return msg(ctx, "&cNo tournament by that name.");
        Entity npc = TournamentNpcs.spawn(player.serverLevel(), def,
                new Vec3(player.getX(), player.getY(), player.getZ()), player.getYRot());
        if (npc == null) return msg(ctx, "&cFailed to spawn entity '" + def.npcEntityType + "'.");
        return msg(ctx, "&aSpawned '" + def.npcName + "' (" + def.npcEntityType + ") for '" + def.name + "'.");
    }

    private static int npcBind(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        TournamentDef def = resolve(ctx);
        if (def == null) return msg(ctx, "&cNo tournament by that name.");
        Entity nearest = nearestEntity(player);
        if (nearest == null) return msg(ctx, "&cNo entity within 6 blocks to bind.");
        TournamentNpcs.tag(nearest, def.id);
        return msg(ctx, "&aBound the nearby " + nearest.getType().getDescription().getString() + " to '" + def.name + "' (single sign-up).");
    }

    /** {@code /rg tourney npc setgroup <group|all>}: make the nearby entity a browser NPC filtered to a group. */
    private static int npcSetGroup(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        String group = StringArgumentType.getString(ctx, "group").trim();
        if (group.equalsIgnoreCase("all")) group = "";
        Entity nearest = nearestEntity(player);
        if (nearest == null) return msg(ctx, "&cNo entity within 6 blocks.");
        TournamentNpcs.tagBrowse(nearest, group);
        return msg(ctx, "&aThe nearby " + nearest.getType().getDescription().getString() + " now browses "
                + (group.isEmpty() ? "all tournaments." : "the '" + group + "' group."));
    }

    /** {@code /rg tourney npc remove}: discard the nearest tournament sign-up host within 8 blocks. */
    private static int npcRemove(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        Entity nearest = null;
        double best = 64.0; // 8-block radius, squared
        for (Entity e : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(8))) {
            if (!TournamentNpcs.isHost(e)) continue;
            double d = e.distanceToSqr(player);
            if (d < best) { best = d; nearest = e; }
        }
        if (nearest == null) return msg(ctx, "&cNo tournament NPC within 8 blocks.");
        String type = nearest.getType().getDescription().getString();
        nearest.discard();
        return msg(ctx, "&aRemoved the nearby tournament NPC (" + type + ").");
    }

    /** {@code /rg tourney npc removeall}: discard every tournament sign-up host on the server. */
    private static int npcRemoveAll(CommandContext<CommandSourceStack> ctx) {
        int removed = TournamentNpcs.removeAll(ctx.getSource().getServer());
        return msg(ctx, "&aRemoved " + removed + " tournament NPC" + (removed == 1 ? "" : "s") + ".");
    }

    private static Entity nearestEntity(ServerPlayer player) {
        Entity nearest = null;
        double best = 36.0;
        for (Entity e : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(6))) {
            if (e == player || e instanceof ServerPlayer) continue;
            double d = e.distanceToSqr(player);
            if (d < best) { best = d; nearest = e; }
        }
        return nearest;
    }

    private static int teamCreate(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        TournamentInstance inst = inst(ctx);
        if (inst == null) return msg(ctx, "&cNo tournament by that name.");
        if (!inst.format().isTeam()) return msg(ctx, "&cThat tournament is not a team format.");
        String teamName = StringArgumentType.getString(ctx, "team");
        return msg(ctx, inst.createTeam(player, teamName)
                ? "&aTeam '" + teamName + "' created - share the name so teammates can /rg tourney team join." : "&cCould not create a team (sign-ups closed or full).");
    }

    private static int teamJoin(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        TournamentInstance inst = inst(ctx);
        if (inst == null) return msg(ctx, "&cNo tournament by that name.");
        if (!inst.format().isTeam()) return msg(ctx, "&cThat tournament is not a team format.");
        int teamId = IntegerArgumentType.getInteger(ctx, "teamId");
        return msg(ctx, inst.joinTeam(player, teamId) ? "&aJoined the team!" : "&cCould not join that team (full or missing).");
    }

    private static int teamLeave(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        TournamentInstance inst = inst(ctx);
        if (inst == null) return msg(ctx, "&cNo tournament by that name.");
        return msg(ctx, inst.leaveTeam(player.getUUID()) ? "&eYou left your team." : "&cYou were not in a team.");
    }

    private static int titleList(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        TournamentData data = TournamentData.get(server);
        for (String d : TitleManager.definitions()) {
            int sep = d.indexOf('|');
            String tid = sep > 0 ? d.substring(0, sep) : d;
            UUID holder = data.getTitleHolder(tid);
            String holderName = holder == null ? "&7(vacant)" : resolveName(server, holder);
            ctx.getSource().sendSystemMessage(TextUtil.color("&e" + tid + " &f-> " + TitleManager.displayName(tid) + " &f| " + holderName));
        }
        return 1;
    }

    private static int titleSet(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String tid = StringArgumentType.getString(ctx, "id");
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (!TitleManager.isDefined(tid)) return msg(ctx, "&cNo such title. See /rg tourney title list.");
        TitleManager.grant(ctx.getSource().getServer(), tid, target);
        return 1;
    }

    private static int titleClear(CommandContext<CommandSourceStack> ctx) {
        String tid = StringArgumentType.getString(ctx, "id");
        MinecraftServer server = ctx.getSource().getServer();
        UUID holder = TournamentData.get(server).getTitleHolder(tid);
        // records a dated revocation tombstone (see TournamentData.setTitleHolder). On a shard network it rides the
        // tournaments:titles state sync to every shard, including the holder's, where it strips their energy bar and
        // abilities without a relog. Can't be merged back, so the clear is no longer the self-undoing no-op it was.
        TournamentData.get(server).setTitleHolder(tid, null);
        if (holder == null) return msg(ctx, "&eTitle '" + tid + "' had no holder.");

        // holder online here: clear their tag now. Elsewhere: report where, so the admin knows the revoke has to
        // travel there. Offline everywhere: lands on next login. Presence lookup only runs with the shard layer on.
        ServerPlayer local = server.getPlayerList().getPlayer(holder);
        if (local != null) {
            TitleDisplay.clear(local);
            return msg(ctx, "&eCleared holder of '" + tid + "'.");
        }
        if (net.shurui.shuruisutilities.shard.ShardSync.active()) {
            net.shurui.shuruisutilities.shard.ShardPresence.Located at =
                    net.shurui.shuruisutilities.shard.ShardPresence.findById(holder);
            if (at != null) {
                return msg(ctx, "&eCleared holder of '" + tid + "'. " + at.name()
                        + " is on '" + at.serverId() + "'; the revoke will reach them there shortly.");
            }
        }
        return msg(ctx, "&eCleared holder of '" + tid + "'.");
    }

    private static TournamentDef resolve(CommandContext<CommandSourceStack> ctx) {
        String name = unquote(StringArgumentType.getString(ctx, "name")).trim();
        TournamentData data = TournamentData.get(ctx.getSource().getServer());
        for (TournamentDef def : data.allDefs().values()) {
            if (def.name.equalsIgnoreCase(name)) return def;
        }
        // fall back to matching by internal id
        return data.getDef(name);
    }

    private static TournamentInstance inst(CommandContext<CommandSourceStack> ctx) {
        TournamentDef def = resolve(ctx);
        TournamentManager m = TournamentManager.get();
        return (def == null || m == null) ? null : m.instance(def.id);
    }

    private static String quoteIfNeeded(String s) {
        return s.contains(" ") ? '"' + s + '"' : s;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    private static String resolveName(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) return p.getGameProfile().getName();
        return server.getProfileCache() != null
                ? server.getProfileCache().get(id).map(com.mojang.authlib.GameProfile::getName).orElse(id.toString())
                : id.toString();
    }

    private static ServerPlayer playerOrNull(CommandContext<CommandSourceStack> ctx) {
        try {
            return ctx.getSource().getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.translatable("command.dmz_ragnarok.tournaments.player_only"));
            return null;
        }
    }

    private static int msg(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSystemMessage(TextUtil.color(text));
        return 1;
    }
}
