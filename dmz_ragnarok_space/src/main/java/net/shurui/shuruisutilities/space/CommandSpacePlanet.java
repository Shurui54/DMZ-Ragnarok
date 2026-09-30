package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import net.shurui.shuruisutilities.core.commands.ShuruisUtilitiesCommandBuilder;
import net.shurui.shuruisutilities.guilds.GuildManager;
import net.shurui.shuruisutilities.guilds.model.Guild;
import net.shurui.shuruisutilities.guilds.model.GuildPermission;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.world.space.SurfaceTravelData;
import net.shurui.shuruisutilities.world.space.SurfaceSnap;

/**
 * {@code /spaceplanet} (alias {@code /planet}): claim, unclaim and inspect the GENERATED planet the caller is standing
 * on, for their guild. Mirrors the guild-command conventions ({@link ShuruisUtilitiesCommandBuilder}, the
 * {@code processCommandPlayer} switch dispatch, {@link ChatOutputHandler} feedback).
 *
 * <p>Rules, taken from the existing guild chunk-claim code rather than invented:
 * <ul>
 *   <li>You must be IN a guild ({@link GuildManager#guildOf}).</li>
 *   <li>You need the guild {@link GuildPermission#CLAIM} rank permission to claim and
 *       {@link GuildPermission#UNCLAIM} to unclaim, exactly as the chunk claim/unclaim do.</li>
 *   <li>One planet per guild: claiming a second fails and names the currently-claimed one. This is a SEPARATE
 *       allowance and does NOT touch the chunk claim cap or the battle-power rule.</li>
 * </ul>
 *
 * <p>MOONS. A planet's orbiting moon is a real claimable body with its own stable id ({@link MoonBody}). It flows
 * through this exact command with no new code: a moon's id is just another key in {@link GeneratedPlanetClaims}, and
 * {@link SurfaceTravelData} records it on landing like any generated planet. DECISION (least-surprising default, flag
 * it here to change): a moon COUNTS against the one-planet-per-guild cap. The rule is stated to players as "one planet
 * per guild" and {@link GeneratedPlanetClaims#claimedByGuild} scans ALL claims flat, so a guild holds exactly one body
 * total, planet OR moon, and cannot use a moon as a second free slot. To make moons a SEPARATE allowance (one planet AND
 * one moon per guild) instead, split the cap check in {@link #doClaim} on {@link MoonBody#isMoon}: keep two counts and
 * compare a moon claim only against other moon claims. Nothing else needs to change.
 *
 * <p>Ownership lives in {@link GeneratedPlanetClaims} keyed by the planet id, never on an entity, so it survives every
 * planet body evaporating. Which planet the caller is on comes from {@link SurfaceTravelData} (recorded on landing),
 * so this only works while a player is actually standing on a generated planet's surface.
 */
public class CommandSpacePlanet extends ShuruisUtilitiesCommandBuilder
{
    public CommandSpacePlanet(boolean enabled)
    {
        super(enabled);
    }

    @Override
    public @NotNull String getPrimaryAlias()
    {
        return "spaceplanet";
    }

    @Override
    public String @NotNull [] getDefaultSecondaryAliases()
    {
        return new String[] { "planet" };
    }

    @Override
    public boolean canConsoleUseCommand()
    {
        return false;
    }

    @Override
    public DefaultPermissionLevel getPermissionLevel()
    {
        return DefaultPermissionLevel.ALL;
    }

    // Suggests the ids /planet destroy can act on: every existing generated planet and moon, drawn from the cached index
    // (GeneratedPlanetIndex) so a keystroke never re-walks space. Each suggestion carries its display name as the tooltip,
    // so an operator reading "sugen:1a2b..." sees "Rakoli" on hover. Fails open to no suggestions on any error.
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_DESTRUCTIBLE = (ctx, builder) ->
    {
        MinecraftServer server = ctx.getSource().getServer();
        if (server == null)
        {
            return builder.buildFuture();
        }
        String lower = builder.getRemaining().toLowerCase(Locale.ROOT);
        long gameTime = server.overworld().getGameTime();
        for (String id : GeneratedPlanetIndex.destructibleIds(server, gameTime))
        {
            if (id.toLowerCase(Locale.ROOT).startsWith(lower))
            {
                builder.suggest(id, Component.literal(GeneratedPlanets.nameFor(id)));
            }
        }
        return builder.buildFuture();
    };

    // Suggests the ids /planet restore can act on: every currently-destroyed body. Reads only the tiny destroyed set.
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_DESTROYED = (ctx, builder) ->
    {
        MinecraftServer server = ctx.getSource().getServer();
        if (server == null)
        {
            return builder.buildFuture();
        }
        String lower = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String id : GeneratedPlanetIndex.destroyedIds(server))
        {
            if (id.toLowerCase(Locale.ROOT).startsWith(lower))
            {
                builder.suggest(id, Component.literal(GeneratedPlanets.nameFor(id)));
            }
        }
        return builder.buildFuture();
    };

    // how many search results to print before collapsing the rest into a "refine the search" tail.
    private static final int SEARCH_LIMIT = 10;

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> setExecution()
    {
        return baseBuilder
                .then(Commands.literal("claim").executes(ctx -> execute(ctx, "claim")))
                .then(Commands.literal("unclaim").executes(ctx -> execute(ctx, "unclaim")))
                .then(Commands.literal("info").executes(ctx -> execute(ctx, "info")))
                // rebind-free fallback for the planet-info GUI keybind (P by default). Opens the SAME screen the keybind
                // does, for the generated planet the caller is LOOKING at in space, so a player whose P key is stolen by
                // another mod still has a way in. It is deliberately separate from "info" (which is text about the
                // surface planet you are STANDING on): this one needs no surface, only a look-at, so it runs before the
                // standing-on-a-surface guard below.
                .then(Commands.literal("look").executes(ctx -> execute(ctx, "look")))
                // manual escape hatch: teleport the caller to their planet's ground-snapped cell centre. Available to
                // ANY player (no permission gate), because it exists for the player who has managed to get stuck on a
                // surface, staff or not. Runs through the standing-on-a-surface guard below like claim/unclaim/info.
                .then(Commands.literal("unstuck").executes(ctx -> execute(ctx, "unstuck")))
                // PUBLIC conquest trigger: spawn the theme defender boss for the unowned planet you are standing on once
                // its wild defenders are cleared, then beat it to claim the planet personally (no guild). Available to any
                // player, keyless, singleplayer or dedicated. Runs through the standing-on-a-surface guard below.
                .then(Commands.literal("conquer").executes(ctx -> execute(ctx, "conquer")))
                // admin/self-test: force-spawn the conquest boss for the planet you are standing on, ignoring the wild
                // defenders, so the boss + stat-scaling + claim path can be exercised on demand.
                .then(Commands.literal("conquestboss")
                        .requires(s -> hasPermission(s, ModuleSpacePlanetClaims.PERM_ADMIN, "spaceplanet.conquestboss"))
                        .executes(ctx -> execute(ctx, "conquestboss")))
                // admin subcommands, OP-gated per-node exactly like the guild admin subcommands. These take an explicit
                // planet id and do NOT require the caller to be standing on the planet, so a planet can be destroyed
                // from anywhere. The single server-side entry point PlanetDestruction does the work; the ki blast in
                // phase B and the raid path later call the same entry point, not this command.
                .then(Commands.literal("destroy")
                        .requires(s -> hasPermission(s, ModuleSpacePlanetClaims.PERM_ADMIN, "spaceplanet.destroy"))
                        .then(Commands.argument("planetId", StringArgumentType.string())
                                .suggests(SUGGEST_DESTRUCTIBLE)
                                .executes(ctx -> execute(ctx, "destroy:" + StringArgumentType.getString(ctx, "planetId")))))
                .then(Commands.literal("restore")
                        .requires(s -> hasPermission(s, ModuleSpacePlanetClaims.PERM_ADMIN, "spaceplanet.restore"))
                        .then(Commands.argument("planetId", StringArgumentType.string())
                                .suggests(SUGGEST_DESTROYED)
                                .executes(ctx -> execute(ctx, "restore:" + StringArgumentType.getString(ctx, "planetId")))))
                // admin: find a planet (or moon, or destroyed body) by a substring of its display name, so an operator can
                // recover the id to /planet destroy or /planet restore without flying to it. OP-gated and run BEFORE the
                // standing-on-a-surface guard like destroy/restore, so it works from anywhere. greedyString so a two-word
                // name like "Overworld Moon" is one argument.
                .then(Commands.literal("search")
                        .requires(s -> hasPermission(s, ModuleSpacePlanetClaims.PERM_ADMIN, "spaceplanet.search"))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> execute(ctx, "search:" + StringArgumentType.getString(ctx, "name")))))
                // admin super-body diagnostics + escape hatch. Read-only status of all seven Super Dragon Ball bodies,
                // and a force-relocate for a body whose ball is genuinely lost (destroyed in an inventory, void or by
                // /clear, which fires no despawn event so it can never relocate on its own). OP-gated per-node and run
                // BEFORE the standing-on-a-surface guard like destroy/restore, so they work from anywhere.
                .then(Commands.literal("superstatus")
                        .requires(s -> hasPermission(s, ModuleSpacePlanetClaims.PERM_ADMIN, "spaceplanet.superstatus"))
                        .executes(ctx -> execute(ctx, "superstatus")))
                .then(Commands.literal("superrelocate")
                        .requires(s -> hasPermission(s, ModuleSpacePlanetClaims.PERM_ADMIN, "spaceplanet.superrelocate"))
                        .then(Commands.argument("star", StringArgumentType.string())
                                .executes(ctx -> execute(ctx, "superrelocate:" + StringArgumentType.getString(ctx, "star")))))
                .executes(ctx -> execute(ctx, "info"));
    }

    @Override
    public int processCommandPlayer(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = getServerPlayer(src);
        MinecraftServer server = player.getServer();

        // admin destroy/restore carry an explicit planet id and run BEFORE the standing-on-a-surface guard, so a planet
        // can be acted on from anywhere. The per-node .requires already OP-gated these.
        if (params.startsWith("destroy:"))
        {
            return doDestroy(src, server, params.substring("destroy:".length()), player);
        }
        if (params.startsWith("restore:"))
        {
            return doRestore(src, server, params.substring("restore:".length()));
        }
        // admin: name search, no surface required, OP-gated by the per-node .requires above.
        if (params.startsWith("search:"))
        {
            return doSearch(src, player, server, params.substring("search:".length()));
        }
        // admin super-body diagnostics + escape hatch: no surface required, OP-gated by the per-node .requires above.
        if (params.equals("superstatus"))
        {
            return doSuperStatus(player, server);
        }
        if (params.startsWith("superrelocate:"))
        {
            return doSuperRelocate(player, server, params.substring("superrelocate:".length()));
        }
        // the look-at GUI fallback runs BEFORE the standing-on-a-surface guard: it targets the planet the caller is
        // LOOKING at from space, not the one they are standing on, so requiring a surface here would make it unusable
        // exactly where it is meant to be used.
        if (params.equals("look"))
        {
            return doLook(player);
        }

        // must be standing on a generated planet's surface: the planet id is recorded on landing.
        if (!SurfaceDimension.isSurface(player.level()) || !SurfaceTravelData.hasPlanet(player))
        {
            ChatOutputHandler.chatError(src, "You must be standing on a generated planet to do that.");
            return Command.SINGLE_SUCCESS;
        }
        String planetId = SurfaceTravelData.planetId(player);
        String planetName = GeneratedPlanets.nameFor(planetId);
        GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);

        switch (params)
        {
            case "claim":
                return doClaim(src, player, server, claims, planetId, planetName);
            case "unclaim":
                return doUnclaim(src, player, server, claims, planetId, planetName);
            case "conquer":
                return doConquer(src, player, server, claims, planetId, planetName);
            case "conquestboss":
                return doDebugConquestBoss(src, player, server, claims, planetId, planetName);
            case "unstuck":
                return doUnstuck(player, planetId);
            case "info":
            default:
                return doInfo(src, server, claims, planetId, planetName);
        }
    }

    // print the planet-info readout for the body the caller is looking at, reusing PlanetInfoServer's targeting VERBATIM.
    // The floating overlay (toggled by the P key) is now the primary display; this command is its one-shot CHAT
    // counterpart, so it works even when a player has the overlay switched off or their P key is stolen. readout casts the
    // same look-at ray the overlay uses (so the command and the panel can never disagree on which planet is in reach),
    // prints the full state on a hit, and on a miss sends its OWN translated action-bar reason (not in space / not looking
    // at a planet / out of range). So there is nothing to duplicate here and no new player-facing string to translate:
    // this just forwards the caller. Commands already run on the server thread, which is where readout expects to run.
    private int doLook(ServerPlayer player)
    {
        PlanetInfoServer.readout(player);
        return Command.SINGLE_SUCCESS;
    }

    private int doClaim(CommandSourceStack src, ServerPlayer player, MinecraftServer server,
                        GeneratedPlanetClaims claims, String planetId, String planetName)
    {
        Guild guild = GuildManager.guildOf(player.getUUID());
        if (guild == null)
        {
            ChatOutputHandler.chatError(src, "You are not in a guild. Use /guild create <name>.");
            return Command.SINGLE_SUCCESS;
        }
        // same permission gate as the chunk claim: the guild CLAIM rank permission.
        if (!guild.hasPermission(player.getUUID(), GuildPermission.CLAIM))
        {
            ChatOutputHandler.chatError(src, "Your guild rank lacks permission: claim");
            return Command.SINGLE_SUCCESS;
        }
        // already personally conquered by a player? the two claim paths never share a planet, so a guild cannot take one
        // a player already owns. Refused with the same translated key the info line uses.
        if (claims.isPersonallyClaimed(planetId))
        {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "message.dmz_ragnarok.core.planet_conquest_owned_personal"));
            return Command.SINGLE_SUCCESS;
        }
        // already owned?
        String owner = claims.owner(planetId);
        if (owner != null)
        {
            if (owner.equals(guild.id))
            {
                ChatOutputHandler.chatError(src, "Your guild already owns this planet.");
                return Command.SINGLE_SUCCESS;
            }
            Guild ownerGuild = GuildManager.byId(owner);
            if (ownerGuild != null)
            {
                // still owned by a live guild: refuse.
                ChatOutputHandler.chatError(src, "This planet is already claimed by %s.", ownerGuild.name);
                return Command.SINGLE_SUCCESS;
            }
            // the owning guild no longer exists (it disbanded): the claim is stale, so drop it and let this guild
            // take the planet. Falls through to the one-per-guild and claim steps below.
            claims.unclaim(planetId);
        }
        // one planet per guild: fail and name the one they already hold.
        String existing = claims.claimedByGuild(guild.id);
        if (existing != null)
        {
            ChatOutputHandler.chatError(src, "Your guild already owns a planet: %s. Unclaim it first.",
                    GeneratedPlanets.nameFor(existing));
            return Command.SINGLE_SUCCESS;
        }
        // the wild GARRISON gate: an unowned planet cannot be claimed while its visible defenders still live. The check
        // is identity-based and reconciles a phantom (see PlanetGarrison.claimBlocked), so a broken defender system can
        // never make a planet permanently unclaimable. Refused with its own translated message, matching this command's
        // refusal style.
        if (PlanetGarrison.claimBlocked(server, planetId))
        {
            // an explicit translated key (not a hashed plain string) so the exact lang entry can be shipped in en_us and
            // es_es, mirroring PlanetBusterModule's player-facing messages. Sent as a chat message, like this command's
            // other feedback.
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "message.dmz_ragnarok.core.planet_claim_defended"));
            return Command.SINGLE_SUCCESS;
        }
        claims.setClaim(planetId, guild.id);
        // the defenders are dead by definition here; clear the garrison bookkeeping so it does not linger on an owned
        // world.
        PlanetGarrison.onPlanetClaimed(server, planetId);
        // if a public conquest boss was mid-fight on this planet, the guild just took it: despawn the boss and drop the
        // pending record so the two paths never both resolve the same planet.
        PlanetConquest.onPlanetClaimed(server, planetId);
        // the owner changed: re-sync the layout so the client's generated-planet nameplate shows the new owner without a
        // relog (bodies are drawn client-side from the synced owner map, not from an entity label any more).
        SpaceLayoutSync.syncAll();
        ChatOutputHandler.chatConfirmation(src, "Claimed planet %s for your guild.", planetName);
        return Command.SINGLE_SUCCESS;
    }

    private int doUnclaim(CommandSourceStack src, ServerPlayer player, MinecraftServer server,
                          GeneratedPlanetClaims claims, String planetId, String planetName)
    {
        // PUBLIC personal claim: the conquering owner (and only them) may release their own planet, with no guild involved.
        String personalOwner = claims.personalOwner(planetId);
        if (personalOwner != null)
        {
            if (!personalOwner.equals(player.getUUID().toString()))
            {
                ChatOutputHandler.chatError(src, "You do not own this planet.");
                return Command.SINGLE_SUCCESS;
            }
            claims.unclaimPersonal(planetId);
            SpaceLayoutSync.syncAll();
            ChatOutputHandler.chatConfirmation(src, "Released your claim on planet %s.", planetName);
            return Command.SINGLE_SUCCESS;
        }
        Guild guild = GuildManager.guildOf(player.getUUID());
        if (guild == null)
        {
            ChatOutputHandler.chatError(src, "You are not in a guild.");
            return Command.SINGLE_SUCCESS;
        }
        String owner = claims.owner(planetId);
        if (owner == null || !owner.equals(guild.id))
        {
            ChatOutputHandler.chatError(src, "Your guild does not own this planet.");
            return Command.SINGLE_SUCCESS;
        }
        if (!guild.hasPermission(player.getUUID(), GuildPermission.UNCLAIM))
        {
            ChatOutputHandler.chatError(src, "Your guild rank lacks permission: unclaim");
            return Command.SINGLE_SUCCESS;
        }
        claims.unclaim(planetId);
        // owner cleared: re-sync so the client's nameplate reverts to "Unclaimed" without a relog.
        SpaceLayoutSync.syncAll();
        ChatOutputHandler.chatConfirmation(src, "Unclaimed planet %s.", planetName);
        return Command.SINGLE_SUCCESS;
    }

    // PUBLIC conquest trigger. Spawns the theme defender boss for the unowned planet the caller stands on, once its wild
    // defenders are cleared, and beating that boss claims the planet personally (handled in PlanetConquest on the boss's
    // death). Handles every state with its own translated line: feature off, already owned, defenders still alive, a boss
    // already fighting, or spawn success. This is what lets a keyless player conquer a planet whose garrison was disabled
    // or already cleared before this feature existed, on top of the automatic spawn when the last defender falls.
    private int doConquer(CommandSourceStack src, ServerPlayer player, MinecraftServer server,
                          GeneratedPlanetClaims claims, String planetId, String planetName)
    {
        if (!PlanetConquest.isEnabled())
        {
            ChatOutputHandler.chatError(src, "Planet conquest is disabled on this server.");
            return Command.SINGLE_SUCCESS;
        }
        if (claims.isPersonallyClaimed(planetId))
        {
            ChatOutputHandler.chatConfirmation(src, "Planet %s is owned by %s.", planetName,
                    ownerName(server, claims.personalOwner(planetId)));
            return Command.SINGLE_SUCCESS;
        }
        if (claims.owner(planetId) != null)
        {
            ChatOutputHandler.chatError(src, "This planet is already claimed by a guild.");
            return Command.SINGLE_SUCCESS;
        }
        // wild defenders must be cleared first: the boss is the FINAL stage, not the first.
        if (PlanetGarrison.claimBlocked(server, planetId))
        {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "message.dmz_ragnarok.core.planet_claim_defended"));
            return Command.SINGLE_SUCCESS;
        }
        ServerLevel surface = (ServerLevel) player.level();
        if (PlanetConquest.spawnBoss(server, surface, planetId, player, false))
        {
            return Command.SINGLE_SUCCESS; // the spawn announces itself; beating the boss claims the planet.
        }
        // spawnBoss refused: the most common reason once cleared and unowned is that a boss is already fighting.
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "message.dmz_ragnarok.core.planet_conquest_boss_present"));
        return Command.SINGLE_SUCCESS;
    }

    // admin/self-test: force-spawn the conquest boss for the planet the caller stands on, bypassing the wild-defender gate,
    // so the boss art, stat scaling and personal-claim-on-defeat can be exercised on demand. OP-gated by the node above.
    private int doDebugConquestBoss(CommandSourceStack src, ServerPlayer player, MinecraftServer server,
                                    GeneratedPlanetClaims claims, String planetId, String planetName)
    {
        if (claims.isOwned(planetId))
        {
            ChatOutputHandler.chatError(src, "That planet is already owned; unclaim it first to test a conquest boss.");
            return Command.SINGLE_SUCCESS;
        }
        ServerLevel surface = (ServerLevel) player.level();
        if (PlanetConquest.spawnBoss(server, surface, planetId, player, true))
        {
            ChatOutputHandler.chatConfirmation(src, "Spawned a conquest boss on planet %s.", planetName);
        }
        else
        {
            ChatOutputHandler.chatError(src, "Could not spawn a conquest boss (feature off, a boss is already present, or no boss id resolved).");
        }
        return Command.SINGLE_SUCCESS;
    }

    // resolve an owner uuid string to a display name via the server profile cache, falling back to the raw uuid.
    private static String ownerName(MinecraftServer server, String uuid)
    {
        if (server != null && uuid != null)
        {
            try
            {
                java.util.Optional<com.mojang.authlib.GameProfile> profile =
                        server.getProfileCache() == null ? java.util.Optional.empty()
                                : server.getProfileCache().get(java.util.UUID.fromString(uuid));
                if (profile.isPresent() && profile.get().getName() != null)
                {
                    return profile.get().getName();
                }
            }
            catch (Throwable ignored)
            {
                // malformed uuid or cache miss: fall through to the raw uuid.
            }
        }
        return uuid;
    }

    // manual escape hatch for a player stuck against the rim boundary: teleport them to their planet's cell centre,
    // ground-snapped, reusing the exact snap SpaceTravelModule's fall-catch uses (SurfaceSnap on the centre column,
    // which is pinned solid). Kills their momentum so ki flight does not fling them straight back into the wall and
    // zeroes the fall so the drop deals no damage. No permission gate: any stuck player may call it. planetId is the
    // surface record the caller-guard already resolved.
    private int doUnstuck(ServerPlayer player, String planetId)
    {
        ServerLevel surface = (ServerLevel) player.level();
        Vec3 centre = SurfaceDimension.cellCentre(planetId);
        Vec3 snapped = SurfaceSnap.snap(surface, centre.x, centre.z);
        player.teleportTo(surface, snapped.x, snapped.y, snapped.z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(0.0, 0.0, 0.0);
        player.resetFallDistance();
        player.hurtMarked = true;
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.dmz_ragnarok.core.space_unstuck"), false);
        return Command.SINGLE_SUCCESS;
    }

    private int doInfo(CommandSourceStack src, MinecraftServer server, GeneratedPlanetClaims claims,
                       String planetId, String planetName)
    {
        String personalOwner = claims.personalOwner(planetId);
        String owner = claims.owner(planetId);
        if (personalOwner != null)
        {
            ChatOutputHandler.chatConfirmation(src, "Planet %s is owned by %s.", planetName,
                    ownerName(server, personalOwner));
        }
        else if (owner == null)
        {
            ChatOutputHandler.chatConfirmation(src, "Planet %s is unclaimed.", planetName);
        }
        else
        {
            Guild ownerGuild = GuildManager.byId(owner);
            ChatOutputHandler.chatConfirmation(src, "Planet %s is owned by %s.", planetName,
                    ownerGuild == null ? owner : ownerGuild.name);
        }
        // admins get the raw planet id too, clickable to copy, so they can feed it straight to /planet destroy or
        // /planet restore. Gated on the same admin node as the destroy/restore subcommands so a normal player never sees
        // it, and built as a plain component (not a hashed chat string) because it carries a click event.
        if (hasPermission(src, ModuleSpacePlanetClaims.PERM_ADMIN))
        {
            ChatOutputHandler.sendMessage(src, idLine(planetId));
        }
        return Command.SINGLE_SUCCESS;
    }

    // "Planet id: <id>" where <id> is aqua and clickable to copy to the clipboard, with a hover hint. The single builder
    // for the copyable id line, reused by /planet info and the /planet look readout, so both read identically.
    static MutableComponent idLine(String planetId)
    {
        return Component.literal("Planet id: ").withStyle(ChatFormatting.GRAY).append(idComponent(planetId));
    }

    // the clickable id token on its own: aqua, COPY_TO_CLIPBOARD on click, a hover hint. Kept separate so the search
    // results can drop the same token into a longer line.
    static MutableComponent idComponent(String planetId)
    {
        return Component.literal(planetId).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, planetId))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Click to copy the planet id"))));
    }

    // admin: destroy a generated planet by id, through the single PlanetDestruction entry point. requireUnclaimed is
    // false here (an admin may destroy an owned planet); the phase B ki blast will decide its own policy. On success
    // the entry point already resynced clients, so the planet is gone for everyone immediately.
    private int doDestroy(CommandSourceStack src, MinecraftServer server, String planetId, ServerPlayer credit)
    {
        ServerLevel overworld = server.overworld();
        PlanetDestruction.Result result = PlanetDestruction.destroy(overworld, planetId, credit, false);
        switch (result)
        {
            case DESTROYED:
                ChatOutputHandler.chatConfirmation(src, "Destroyed planet %s.", GeneratedPlanets.nameFor(planetId));
                return Command.SINGLE_SUCCESS;
            case NOT_DESTRUCTIBLE:
                ChatOutputHandler.chatError(src, "That body cannot be destroyed. Only generated planets can, not fixed worlds or moons.");
                return Command.SINGLE_SUCCESS;
            case ALREADY_DESTROYED:
                ChatOutputHandler.chatError(src, "That planet is already destroyed.");
                return Command.SINGLE_SUCCESS;
            case NOT_FOUND:
            default:
                ChatOutputHandler.chatError(src, "Could not find that planet. Fly nearer to it and try again.");
                return Command.SINGLE_SUCCESS;
        }
    }

    // admin: restore a destroyed planet by id (same planet, no generation bump), through the same entry point.
    private int doRestore(CommandSourceStack src, MinecraftServer server, String planetId)
    {
        ServerLevel overworld = server.overworld();
        PlanetDestruction.Result result = PlanetDestruction.restore(overworld, planetId);
        switch (result)
        {
            case RESTORED:
                ChatOutputHandler.chatConfirmation(src, "Restored planet %s.", GeneratedPlanets.nameFor(planetId));
                return Command.SINGLE_SUCCESS;
            case NOT_DESTROYED:
            default:
                ChatOutputHandler.chatError(src, "That planet is not destroyed.");
                return Command.SINGLE_SUCCESS;
        }
    }

    // one search result before formatting: the id, its display name, its space position (or null if not known), the sort
    // distance from the caller (Double.MAX_VALUE when the caller is not in space, so order falls back to name), and the
    // pre-built state component (unclaimed / claimed by X / destroyed, regenerates in ...).
    private record SearchHit(String id, String name, Vec3 position, double sortDistance, MutableComponent state)
    {
    }

    // admin: find every planet, moon or destroyed body whose display name contains the query (case-insensitive). Lists the
    // name, the copyable id, the state and the position (plus distance from the caller when in space), capped at
    // SEARCH_LIMIT with a "refine the search" tail. Existing bodies come from the cached index (GeneratedPlanetIndex,
    // reusing the live derivation so ids match what destroy accepts); destroyed bodies come from the claim store so an
    // admin can find the id to /planet restore. Runs on the server thread: the walk is a couple of thousand cell
    // derivations, cached, well under a few milliseconds (see GeneratedPlanetIndex).
    private int doSearch(CommandSourceStack src, ServerPlayer player, MinecraftServer server, String query)
    {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty())
        {
            ChatOutputHandler.chatError(src, "Type part of a planet name to search for.");
            return Command.SINGLE_SUCCESS;
        }
        GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
        long gameTime = server.overworld().getGameTime();
        boolean inSpace = SpaceDimension.isSpace(player.level());
        Vec3 from = player.position();

        List<SearchHit> hits = new ArrayList<>();

        // existing generated planets (cached snapshot).
        for (GeneratedPlanets.Generated g : GeneratedPlanetIndex.existingPlanets(server))
        {
            String name = GeneratedPlanets.nameFor(g.id);
            if (name.toLowerCase(Locale.ROOT).contains(needle))
            {
                hits.add(new SearchHit(g.id, name, g.position, sortDistance(inSpace, from, g.position),
                        liveStateLabel(claims, g.id)));
            }
        }
        // existing moons (derived live, they orbit).
        for (MoonBody.Moon m : GeneratedPlanetIndex.existingMoons(server, gameTime))
        {
            String name = GeneratedPlanets.nameFor(m.id);
            if (name.toLowerCase(Locale.ROOT).contains(needle))
            {
                hits.add(new SearchHit(m.id, name, m.position, sortDistance(inSpace, from, m.position),
                        liveStateLabel(claims, m.id)));
            }
        }
        // currently-destroyed bodies (planets and moons), so the id for /planet restore is findable. Iterate the entries,
        // not just the values, because a destroyed generated planet's former position is re-derived from its CELL KEY (the
        // map key), which the DestroyedCell record itself does not carry.
        for (java.util.Map.Entry<String, GeneratedPlanetClaims.DestroyedCell> e : claims.destroyedCells().entrySet())
        {
            GeneratedPlanetClaims.DestroyedCell cell = e.getValue();
            String id = cell.destroyedPlanetId;
            String name = GeneratedPlanets.nameFor(id);
            if (name.toLowerCase(Locale.ROOT).contains(needle))
            {
                Vec3 pos = destroyedPosition(server, id, e.getKey(), cell, gameTime);
                hits.add(new SearchHit(id, name, pos, sortDistance(inSpace, from, pos), destroyedStateLabel(cell, gameTime)));
            }
        }

        if (hits.isEmpty())
        {
            ChatOutputHandler.chatError(src, "No planet matched \"%s\".", query.trim());
            return Command.SINGLE_SUCCESS;
        }

        // nearest first when the caller is in space, else alphabetical (every sortDistance is MAX_VALUE, so this leaves
        // them tied and the secondary name sort orders them).
        hits.sort((a, b) ->
        {
            int byDist = Double.compare(a.sortDistance, b.sortDistance);
            return byDist != 0 ? byDist : a.name.compareToIgnoreCase(b.name);
        });

        int shown = Math.min(hits.size(), SEARCH_LIMIT);
        ChatOutputHandler.sendMessage(src, Component.literal("Planet search: " + hits.size()
                + (hits.size() == 1 ? " match" : " matches")).withStyle(ChatFormatting.YELLOW));
        for (int i = 0; i < shown; i++)
        {
            ChatOutputHandler.sendMessage(src, searchLine(hits.get(i), inSpace, from));
        }
        if (hits.size() > shown)
        {
            ChatOutputHandler.sendMessage(src, Component.literal("... and " + (hits.size() - shown)
                    + " more, refine the search.").withStyle(ChatFormatting.GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }

    // one formatted search line: name, copyable id, state, position and (when the caller is in space) the distance to it.
    private static MutableComponent searchLine(SearchHit hit, boolean inSpace, Vec3 from)
    {
        MutableComponent line = Component.literal(hit.name).withStyle(ChatFormatting.GOLD);
        line.append(Component.literal("  ").withStyle(ChatFormatting.GRAY));
        line.append(idComponent(hit.id));
        line.append(Component.literal("  ").withStyle(ChatFormatting.GRAY));
        line.append(hit.state);
        if (hit.position != null)
        {
            line.append(Component.literal("  at " + posLabel(hit.position)).withStyle(ChatFormatting.DARK_GRAY));
            if (inSpace)
            {
                long dist = Math.round(from.distanceTo(hit.position));
                line.append(Component.literal("  (" + dist + " away)").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        return line;
    }

    // the live state of an existing body: unclaimed, or claimed by the owning guild's name (falling back to the raw id if
    // that guild has since disbanded, exactly like doInfo).
    private static MutableComponent liveStateLabel(GeneratedPlanetClaims claims, String id)
    {
        String owner = claims.owner(id);
        if (owner == null)
        {
            return Component.literal("unclaimed").withStyle(ChatFormatting.GREEN);
        }
        Guild guild = GuildManager.byId(owner);
        String name = guild == null ? owner : guild.name;
        return Component.literal("claimed by " + name).withStyle(ChatFormatting.AQUA);
    }

    // the state of a destroyed body: "destroyed" plus the time left until it regenerates, when that is knowable. A zero
    // debris window means it regenerates on the next slow sweep, so it reads as "regenerating soon".
    private static MutableComponent destroyedStateLabel(GeneratedPlanetClaims.DestroyedCell cell, long gameTime)
    {
        long windowTicks = (long) PlanetSpawnModule.planetDebrisSeconds() * 20L;
        long remaining = cell.destroyedAtGameTime + windowTicks - gameTime;
        String suffix = remaining <= 0 ? ", regenerating soon" : ", regenerates in " + formatTicks(remaining);
        return Component.literal("destroyed" + suffix).withStyle(ChatFormatting.RED);
    }

    // Double.MAX_VALUE keeps out-of-space callers (and null positions) from skewing the nearest-first sort; they fall back
    // to the alphabetical secondary key.
    private static double sortDistance(boolean inSpace, Vec3 from, Vec3 target)
    {
        if (!inSpace || target == null)
        {
            return Double.MAX_VALUE;
        }
        return from.distanceToSqr(target);
    }

    // the space position of a destroyed body: for a generated planet, the FORMER body re-derived from its cell key and
    // destruction generation (the wreck geometry, so no live body is needed); for a moon, its parent's current orbit
    // point. Null if neither can be resolved (a malformed record or a moon whose parent is not a current fixed body).
    private static Vec3 destroyedPosition(MinecraftServer server, String id, String cellKey,
            GeneratedPlanetClaims.DestroyedCell cell, long gameTime)
    {
        if (MoonBody.isMoon(id))
        {
            String parentKey = MoonBody.parentKeyOf(id);
            for (FixedBody fb : SpaceLayout.fixedBodies(server))
            {
                if (parentKey.equals(fb.key))
                {
                    return MoonBody.position(fb.position, fb.radius, gameTime);
                }
            }
            return null;
        }
        // a destroyed generated planet: re-derive where it stood from its cell key and the generation it was destroyed at,
        // through the same FormerBody path the wreck uses, so the coordinate matches the debris field exactly.
        int[] c = GeneratedPlanets.parseCellKey(cellKey);
        if (c == null)
        {
            return null;
        }
        return GeneratedPlanets.formerBody(c[0], c[1], c[2], cell.generation).position;
    }

    // a compact "Xd Yh" / "Xh Ym" / "Xm" / "<1m" label for a tick count, for the destroyed-body regeneration countdown.
    private static String formatTicks(long ticks)
    {
        long seconds = ticks / 20L;
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (days > 0)
        {
            return days + "d " + hours + "h";
        }
        if (hours > 0)
        {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0)
        {
            return minutes + "m";
        }
        return "<1m";
    }

    private static final String MSG = "message.dmz_ragnarok.core.";

    // admin: read-only status of all seven Super Dragon Ball bodies, so an operator can see the whole set and diagnose a
    // stuck body. For each: star, position, distance from Earth, its state (grey / ball dropped-but-uncollected / claimed)
    // and whether its Destroyer God is alive on the loaded surface. All server-authoritative, no state is changed.
    private int doSuperStatus(ServerPlayer player, MinecraftServer server)
    {
        SuperPlanetData data = SuperPlanetData.get(server);
        ServerLevel surface = SurfaceDimension.level(server);
        Vec3 earth = SuperPlanetPositions.earth();
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(MSG + "super_admin_status_header"));
        for (int star = 1; star <= SuperPlanetPositions.COUNT; ++star)
        {
            String id = SuperPlanetPositions.keyFor(star);
            Vec3 pos = data.position(id);
            long dist = pos == null ? 0L : Math.round(pos.distanceTo(earth));
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(MSG + "super_admin_status_line",
                    net.minecraft.network.chat.Component.literal(Integer.toString(star)),
                    net.minecraft.network.chat.Component.literal(posLabel(pos)),
                    net.minecraft.network.chat.Component.literal(Long.toString(dist)),
                    stateLabel(data, id),
                    net.minecraft.network.chat.Component.translatable(MSG
                            + (SuperPlanetGod.godAlive(surface, id) ? "super_admin_god_alive" : "super_admin_god_none"))));
        }
        return Command.SINGLE_SUCCESS;
    }

    // admin: force-relocate a genuinely-lost super body, reusing the exact relocate() path (revert to grey, new position,
    // god re-arms on the next landing). Accepts a star number 1..7 or "all". Reports each body's state BEFORE acting so an
    // operator can see whether it was actually stuck, and warns that relocating a ball a player still legitimately holds
    // would mint a SECOND copy. Deliberately NOT hard-blocked: a ball held offline cannot be detected, which is the whole
    // reason absence can never drive relocation automatically.
    private int doSuperRelocate(ServerPlayer player, MinecraftServer server, String arg)
    {
        SuperPlanetData data = SuperPlanetData.get(server);
        ServerLevel surface = SurfaceDimension.level(server);
        boolean all = "all".equalsIgnoreCase(arg);
        int one = 0;
        if (!all)
        {
            try
            {
                one = Integer.parseInt(arg.trim());
            }
            catch (NumberFormatException ignored)
            {
                one = 0;
            }
            if (one < 1 || one > SuperPlanetPositions.COUNT)
            {
                player.sendSystemMessage(
                        net.minecraft.network.chat.Component.translatable(MSG + "super_admin_relocate_usage"));
                return Command.SINGLE_SUCCESS;
            }
        }
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(MSG + "super_admin_relocate_warn"));
        for (int star = 1; star <= SuperPlanetPositions.COUNT; ++star)
        {
            if (!all && star != one)
            {
                continue;
            }
            String id = SuperPlanetPositions.keyFor(star);
            // report the state BEFORE relocating so the operator sees whether it was actually stuck.
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(MSG + "super_admin_relocate_before",
                    net.minecraft.network.chat.Component.literal(Integer.toString(star)),
                    stateLabel(data, id),
                    net.minecraft.network.chat.Component.translatable(MSG
                            + (SuperPlanetGod.godAlive(surface, id) ? "super_admin_god_alive" : "super_admin_god_none"))));
            Vec3 fresh = data.relocate(server, id);
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(MSG + "super_admin_relocate_done",
                    net.minecraft.network.chat.Component.literal(Integer.toString(star)),
                    net.minecraft.network.chat.Component.literal(posLabel(fresh))));
        }
        // a relocate changed positions and cleared claimed flags: repaint every client so the grey, landable body appears
        // at its new spot without a relog. Its god re-arms on the next landing through ensureGuardian.
        SpaceLayoutSync.syncAll();
        return Command.SINGLE_SUCCESS;
    }

    // the translated state word for a body: claimed, ball dropped-but-uncollected, or grey (god unbeaten).
    private static net.minecraft.network.chat.Component stateLabel(SuperPlanetData data, String id)
    {
        String key;
        if (data.isClaimed(id))
        {
            key = "super_admin_state_claimed";
        }
        else if (data.isBallDropped(id))
        {
            key = "super_admin_state_ball";
        }
        else
        {
            key = "super_admin_state_grey";
        }
        return net.minecraft.network.chat.Component.translatable(MSG + key);
    }

    // a compact "x, y, z" integer label for a body position, or a dash if unknown. Language neutral, so it needs no key.
    private static String posLabel(Vec3 pos)
    {
        if (pos == null)
        {
            return "-";
        }
        return Math.round(pos.x) + ", " + Math.round(pos.y) + ", " + Math.round(pos.z);
    }
}
