package net.shurui.dev.sdu.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.compat.DmzTechniques;
import net.shurui.dev.sdu.compat.shard.ShardFanoutBridge;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.shurui.dev.sdu.registry.ModEntities;

import java.nio.file.Files;
import java.nio.file.Path;


// /rg npc: summon a named NPC definition, list, or reload from disk. summon is the main hook for DMZ custom
// sagas (command blocks, mcfunctions, /execute, or vanilla /summon sdu:dmz_fighter ... {Definition:"id"}).
public final class DmzNpcCommand {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private DmzNpcCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // The branded /sdu root moved onto the shared /rg tree under the npc segment: /sdu edit is now /rg npc edit.
        // Brigadier merges this /rg registration with the other addons' /rg registrations into one command node.
        dispatcher.register(Commands.literal("rg")
                // Bare /rg edit is the suite's main entry point: it opens the shared editor hub, which surfaces
                // every editor (sdu's races/forms/sagas/..., plus the raid, tournament, dungeon and SU sections
                // sibling addons register into it). Op-gated to match the per-domain editors (/rg npc|raid|
                // tourney|dungeon edit), so this generates command.rg.edit at OP just like they do.
                .then(Commands.literal("edit")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            if (ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                                net.shurui.dev.sdu.network.DmzNet.openHub(player);
                                return 1;
                            }
                            ctx.getSource().sendFailure(Component.translatable("command.dmz_ragnarok.npc.edit.player_only"));
                            return 0;
                        }))
                .then(Commands.literal("npc")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            net.shurui.dev.sdu.saga.SagaSpawnBindings.loadFrom(ctx.getSource().getServer());
                            net.shurui.dev.sdu.saga.DeferredSpawnRegistry.loadFrom(ctx.getSource().getServer());
                            net.shurui.dev.sdu.saga.TalkNpcRegistry.loadFrom(ctx.getSource().getServer());
                            net.shurui.dev.sdu.saga.SagaNpcMap.load();
                            ctx.getSource().sendSuccess(() -> Component.translatable("command.dmz_ragnarok.npc.reload.ok"), true);
                            return 1;
                        }))



                .then(Commands.literal("edit")
                        .executes(ctx -> {
                            if (ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                                net.shurui.dev.sdu.network.DmzNet.openHub(player);
                                return 1;
                            }
                            ctx.getSource().sendFailure(Component.translatable("command.dmz_ragnarok.npc.edit.player_only"));
                            return 0;
                        }))
                .then(Commands.literal("givetechnique")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("technique", StringArgumentType.string())
                                        .suggests(SUGGEST_TECH)
                                        .executes(DmzNpcCommand::giveTechnique))))
                .then(Commands.literal("hakai")
                        // private: the node is absent (not sent, not runnable) unless the key installed the hakai
                        .requires(source -> source.hasPermission(2) && net.shurui.dev.sdu.api.key.HakaiHooks.available())
                        .executes(DmzNpcCommand::giveHakai)
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(DmzNpcCommand::giveHakaiTargets))
                        .then(Commands.literal("revoke")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(DmzNpcCommand::revokeHakai))))
                .then(Commands.literal("buyskill")
                        // self-only, no perm gate: the stack-skill first-buy the DMZ client menu never sends.
                        // guaranteed server path parallel to the client mixin.
                        .then(Commands.argument("skill", StringArgumentType.string())
                                .suggests(SUGGEST_STACK_SKILL)
                                .executes(DmzNpcCommand::buyStackSkill)))
                .then(Commands.literal("forms")
                        .then(Commands.literal("grantall")
                                .executes(ctx -> grantAllForms(ctx, java.util.List.of(ctx.getSource().getPlayerOrException())))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> grantAllForms(ctx, EntityArgument.getPlayers(ctx, "targets")))))
                        .then(Commands.literal("verify")
                                .executes(DmzNpcCommand::verifyFormConfigs)))
                // /rg npc waypoint (the manual compass pins) is private: the Ragnarok Key merges it into this node
                // (S21, WaypointCommands).
                .then(Commands.literal("htc")
                        .then(Commands.literal("set")
                                // No args: capture the executing admin's current dimension + position + facing.
                                .executes(ctx -> setHtc(ctx, null, null))
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(ctx -> setHtc(ctx, Vec3Argument.getVec3(ctx, "pos"), null))
                                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                .executes(ctx -> setHtc(ctx, Vec3Argument.getVec3(ctx, "pos"),
                                                        DimensionArgument.getDimension(ctx, "dimension").dimension().location().toString())))))
                        .then(Commands.literal("clear")
                                .executes(DmzNpcCommand::clearHtc))
                        .then(Commands.literal("show")
                                .executes(DmzNpcCommand::showHtc)))
                .then(Commands.literal("totem")
                        .then(Commands.literal("time")
                                // No arg: report the current lifetime. With <minutes>: set it. IntegerArgumentType's
                                // range refuses nonsense (0, negative, absurdly large) at parse, before we run.
                                .executes(DmzNpcCommand::showTotemTime)
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(
                                        net.shurui.dev.sdu.grave.GraveTotemConfig.MIN_MINUTES,
                                        net.shurui.dev.sdu.grave.GraveTotemConfig.MAX_MINUTES))
                                        .executes(DmzNpcCommand::setTotemTime))))
                .then(Commands.literal("dmznpc")
                        .then(Commands.literal("model")
                                .then(Commands.argument("geo", StringArgumentType.string())
                                        .then(Commands.argument("anim", StringArgumentType.string())
                                                .executes(ctx -> applyModel(ctx, "", "", "", ""))
                                                .then(Commands.argument("idle", StringArgumentType.string())
                                                        .then(Commands.argument("walk", StringArgumentType.string())
                                                                .then(Commands.argument("attack", StringArgumentType.string())
                                                                        .then(Commands.argument("hurt", StringArgumentType.string())
                                                                                .executes(ctx -> applyModel(ctx,
                                                                                        StringArgumentType.getString(ctx, "idle"),
                                                                                        StringArgumentType.getString(ctx, "walk"),
                                                                                        StringArgumentType.getString(ctx, "attack"),
                                                                                        StringArgumentType.getString(ctx, "hurt")))))))))))));
        // Legacy hidden alias: /sdu still works, redirected onto /rg npc. SU keeps it out of the client command
        // tree (SUCommandManager.hiddenLegacyRoots) and migrates its stored command.sdu.* grants onto command.rg.npc.*.
        dispatcher.register(Commands.literal("sdu")
                .redirect(dispatcher.getRoot().getChild("rg").getChild("npc")));
    }

    // Carry an @a / @e selector on a player targeting node across the shard network, AFTER the local pass has run
    // and been reported. The remote line each other shard re-runs is `before + <player name> + after`, so the caller
    // supplies the fixed parts of the command around the target. A no-op on a single server (the bridge reflects into
    // SU's shard layer only when it is live), so behaviour is unchanged there. See ShardFanoutBridge and
    // ShardSelectorFanout for why this cannot double apply: each shard only ever acts on players it currently holds.
    // Public so the Ragnarok Key's /rg npc waypoint subtree (S21) fans out exactly as it did from here.
    public static void maybeFanOut(CommandContext<CommandSourceStack> ctx, String before, String after) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sender)) {
            return;
        }
        int scope = ShardFanoutBridge.classify(ctx.getInput());
        if (scope == ShardFanoutBridge.SCOPE_NETWORK) {
            // The players the local pass already handled, so the fan-out never queues a remote row for one of them.
            java.util.Set<java.util.UUID> local = new java.util.HashSet<>();
            try {
                for (ServerPlayer p : EntityArgument.getPlayers(ctx, "targets")) {
                    local.add(p.getUUID());
                }
            } catch (Exception ignored) {
                // No resolvable local targets is fine: the remote rows are what matter and the exclude set stays empty.
            }
            ShardFanoutBridge.fanOutByName(sender, before, after, local);
        } else if (scope == ShardFanoutBridge.SCOPE_LOCAL_PREDICATED) {
            // A predicated selector can be positional, so it is NOT fanned out; tell the operator how to reach the net.
            ctx.getSource().sendSuccess(() -> Component.literal("That selector ran on this shard only. Use a bare @a"
                    + " to reach players on the other shards."), false);
        }
    }

    // /rg npc dmznpc model <geo> <anim> [idle] [walk] [attack] [hurt]: feed DMZ GeckoLib assets onto the nearest
    // Custom NPC within 8 blocks. geo/anim are reslocs. test hook until the editor tab lands; needs CNPC-Gecko-Addon.
    private static int applyModel(CommandContext<CommandSourceStack> ctx, String idle, String walk, String attack, String hurt) {
        CommandSourceStack src = ctx.getSource();
        if (!net.shurui.dev.sdu.compat.cnpc.DmzCnpcCompat.geckoAddonAvailable()) {
            src.sendFailure(Component.translatable("command.dmz_ragnarok.npc.dmznpc.no_gecko_addon"));
            return 0;
        }
        String geo = StringArgumentType.getString(ctx, "geo");
        String anim = StringArgumentType.getString(ctx, "anim");
        int n = net.shurui.dev.sdu.compat.cnpc.CnpcGeckoBridge.applyToNearest(
                src.getLevel(), src.getPosition(), 8.0, geo, anim, idle, walk, attack, hurt);
        if (n > 0) {
            src.sendSuccess(() -> Component.translatable("command.dmz_ragnarok.npc.dmznpc.applied", geo), true);
        } else {
            src.sendFailure(Component.translatable("command.dmz_ragnarok.npc.dmznpc.no_npc"));
        }
        return n;
    }

    // /rg npc htc set [pos] [dimension]: set where the DMZ time-chamber portal drops players on exit. no args =
    // the admin's current dim+pos+facing. explicit pos uses the sender's facing (dimension defaults to sender's).
    // enables the override; leaving the HTC then goes here instead of DMZ's default.
    private static int setHtc(CommandContext<CommandSourceStack> ctx, Vec3 pos, String dim) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Vec3 at = pos != null ? pos : player.position();
        String d = dim != null ? dim : player.level().dimension().location().toString();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        net.shurui.dev.sdu.htc.HtcDestination.set(d, at.x, at.y, at.z, yaw, pitch);
        final String fd = d;
        ctx.getSource().sendSuccess(() -> Component.translatable("command.dmz_ragnarok.npc.htc.set",
                fd,
                String.format("%.1f", at.x), String.format("%.1f", at.y), String.format("%.1f", at.z),
                String.format("%.1f", yaw), String.format("%.1f", pitch)), true);
        return 1;
    }

    // /rg npc htc clear: disable the override, back to DMZ's default exit.
    private static int clearHtc(CommandContext<CommandSourceStack> ctx) {
        net.shurui.dev.sdu.htc.HtcDestination.clear();
        ctx.getSource().sendSuccess(() -> Component.translatable("command.dmz_ragnarok.npc.htc.cleared"), true);
        return 1;
    }

    // /rg npc htc show: print the stored HTC exit, or that DMZ default is in use.
    private static int showHtc(CommandContext<CommandSourceStack> ctx) {
        if (!net.shurui.dev.sdu.htc.HtcDestination.isEnabled()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("command.dmz_ragnarok.npc.htc.show_default"), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("command.dmz_ragnarok.npc.htc.show",
                net.shurui.dev.sdu.htc.HtcDestination.dimension(),
                String.format("%.1f", net.shurui.dev.sdu.htc.HtcDestination.x()),
                String.format("%.1f", net.shurui.dev.sdu.htc.HtcDestination.y()),
                String.format("%.1f", net.shurui.dev.sdu.htc.HtcDestination.z()),
                String.format("%.1f", net.shurui.dev.sdu.htc.HtcDestination.yaw()),
                String.format("%.1f", net.shurui.dev.sdu.htc.HtcDestination.pitch())), false);
        return 1;
    }

    // /rg npc totem time: report the current grave/totem despawn lifetime in minutes. Read-only, so it is not
    // broadcast to other ops (sendSuccess ..., false).
    private static int showTotemTime(CommandContext<CommandSourceStack> ctx) {
        int minutes = net.shurui.dev.sdu.grave.GraveTotemConfig.minutes();
        ctx.getSource().sendSuccess(
                () -> Component.translatable("command.dmz_ragnarok.npc.totem.time.show", minutes), false);
        return minutes;
    }

    // /rg npc totem time <minutes>: set the grave/totem despawn lifetime. The store clamps and persists, and
    // ShardStateSync (su:cfg_grave_totem) carries the change to every other shard, so no fan-out is needed here.
    private static int setTotemTime(CommandContext<CommandSourceStack> ctx) {
        int requested = IntegerArgumentType.getInteger(ctx, "minutes");
        int applied = net.shurui.dev.sdu.grave.GraveTotemConfig.set(requested);
        ctx.getSource().sendSuccess(
                () -> Component.translatable("command.dmz_ragnarok.npc.totem.time.set", applied), true);
        return applied;
    }

    // DMZ technique ids (kamehameha, galick_gun, ...) for givetechnique.
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TECH = (ctx, builder) ->
            SharedSuggestionProvider.suggest(DmzTechniques.techniqueIds(), builder);

    // DMZ stack-skill ids (kaioken, ultimate) for buyskill.
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_STACK_SKILL = (ctx, builder) ->
            SharedSuggestionProvider.suggest(net.shurui.dev.sdu.compat.DmzSkills.stackSkillIds(), builder);

    // /rg npc buyskill <skill>: self-buy the next level of a DMZ STACK skill (kaioken, ultimate). the path the
    // DMZ client menu never sends for a stack skill's first level; mirrors DMZ's UpdateSkillC2S PURCHASE 1:1.
    private static int buyStackSkill(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String skill = StringArgumentType.getString(ctx, "skill");
        net.shurui.dev.sdu.compat.DmzSkills.BuyResult result =
                net.shurui.dev.sdu.compat.DmzSkills.buyStackSkill(player, skill);
        final Component msg = result.message();
        if (result.success()) {
            ctx.getSource().sendSuccess(() -> msg, false);
            return 1;
        }
        ctx.getSource().sendFailure(msg);
        return 0;
    }

    // /rg npc forms grantall [targets]: unlock all DMZ forms + max mastery per target (default the sender), then
    // resync. mirrors DMZ's /forms + /mastery all.
    private static int grantAllForms(CommandContext<CommandSourceStack> ctx, java.util.Collection<ServerPlayer> targets) {
        int granted = 0;
        for (ServerPlayer p : targets) {
            if (net.shurui.dev.sdu.compat.DmzForms.grantAllForms(p)) {
                granted++;
                ctx.getSource().sendSuccess(
                        () -> Component.translatable("command.dmz_ragnarok.npc.forms.grantall.ok", p.getGameProfile().getName()), true);
            } else {
                ctx.getSource().sendFailure(
                        Component.translatable("command.dmz_ragnarok.npc.forms.grantall.no_stats", p.getGameProfile().getName()));
            }
        }
        // A bare @a here is meant to reach everyone on the network, not just this shard. The local grant above stands
        // as the this-shard count; the fan-out grants the players held by the other shards, once each.
        maybeFanOut(ctx, "rg npc forms grantall ", "");
        return granted;
    }

    // /rg npc forms verify: read every race's form config back the way the GAME reads it and report what is
    // wrong with it. Runs at server start too; this is the on-demand version, for after an edit.
    private static int verifyFormConfigs(CommandContext<CommandSourceStack> ctx) {
        var findings = net.shurui.dev.sdu.form.FormConfigAudit.run();
        if (findings.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Form configs look fine: no problems found.")
                            .withStyle(net.minecraft.ChatFormatting.GREEN), false);
            return 1;
        }
        ctx.getSource().sendSuccess(
                () -> Component.literal("Form config problems (" + findings.size() + "):")
                        .withStyle(net.minecraft.ChatFormatting.YELLOW), false);
        for (var f : findings) {
            // WARN is broken, NOTE is probably not what was meant, so they are coloured apart rather than
            // dumped as one undifferentiated wall.
            net.minecraft.ChatFormatting colour =
                    f.level() == net.shurui.dev.sdu.form.FormConfigAudit.Level.WARN
                            ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.GRAY;
            ctx.getSource().sendSuccess(
                    () -> Component.literal(" - " + f.race() + ": " + f.message()).withStyle(colour), false);
        }
        return findings.size();
    }

    // /rg npc hakai: op-only self-grant of the hidden shuruis_hakai technique. kept out of givetechnique's
    // suggestions; DmzTechniques.grant re-gates on hasPermissions(2), so a non-op just gets refused.
    private static int giveHakai(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (DmzTechniques.grant(player, DmzTechniques.SHURUIS_HAKAI_ID)) {
            ctx.getSource().sendSuccess(() -> Component.translatable("command.dmz_ragnarok.npc.hakai.ok"), false);
            return 1;
        }
        ctx.getSource().sendFailure(Component.translatable("command.dmz_ragnarok.npc.hakai.fail"));
        return 0;
    }

    // /rg npc hakai <targets>: op-only grant of shuruis_hakai to other players. DmzTechniques.grant re-gates on
    // hasPermissions(2) per target, so a non-operator target is refused and not counted (see giveHakai).
    private static int giveHakaiTargets(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int granted = 0;
        for (ServerPlayer p : EntityArgument.getPlayers(ctx, "targets")) {
            if (DmzTechniques.grant(p, DmzTechniques.SHURUIS_HAKAI_ID)) {
                granted++;
            }
        }
        final int n = granted;
        ctx.getSource().sendSuccess(() -> Component.translatable("command.dmz_ragnarok.npc.hakai.ok_multi", n), true);
        maybeFanOut(ctx, "rg npc hakai ", "");
        return granted;
    }

    // /rg npc hakai revoke <targets>: op-only removal of shuruis_hakai from other players. Counts only targets
    // that actually had it (DmzTechniques.revoke no-ops on a player without the technique).
    private static int revokeHakai(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int revoked = 0;
        for (ServerPlayer p : EntityArgument.getPlayers(ctx, "targets")) {
            if (DmzTechniques.revoke(p, DmzTechniques.SHURUIS_HAKAI_ID)) {
                revoked++;
            }
        }
        final int n = revoked;
        ctx.getSource().sendSuccess(() -> Component.translatable("command.dmz_ragnarok.npc.hakai.revoked", n), true);
        maybeFanOut(ctx, "rg npc hakai revoke ", "");
        return revoked;
    }

    private static int giveTechnique(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        final String tech = StringArgumentType.getString(ctx, "technique");
        // biden_blast is a novelty admin technique (a big_bang clone with a custom sound), meant to be handed out
        // ONLY by an operator running this command in game, never by an automated or non player source. The npc
        // subtree gates on hasPermission(2), which the server console passes at level 4, so a store package command,
        // a datapack function or a command block can otherwise silently grant it to whoever it names. Require an
        // actual in game player source for this id: a real operator (and the cross shard forward, which runs under a
        // ServerPlayer) still works, while the console/automation path is refused. Every other technique is
        // unaffected.
        if (DmzTechniques.BIDEN_BLAST_ID.equals(tech)
                && !(ctx.getSource().getEntity() instanceof ServerPlayer)) {
            ctx.getSource().sendFailure(
                    Component.translatable("command.dmz_ragnarok.npc.givetechnique.restricted", tech));
            return 0;
        }
        int granted = 0;
        for (ServerPlayer p : EntityArgument.getPlayers(ctx, "targets")) {
            if (DmzTechniques.grant(p, tech)) {
                granted++;
            }
        }
        final int n = granted;
        // sendSuccess(..., false) = don't broadcast to other ops; keeps quest-reward grants silent.
        ctx.getSource().sendSuccess(() -> Component.translatable("command.dmz_ragnarok.npc.givetechnique.ok", tech, n), false);
        // The technique id is a fixed trailing argument on the remote line, so it rides through unchanged.
        maybeFanOut(ctx, "rg npc givetechnique ", " " + tech);
        return granted;
    }

}
