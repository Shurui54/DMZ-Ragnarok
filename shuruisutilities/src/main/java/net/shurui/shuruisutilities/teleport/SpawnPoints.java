package net.shurui.shuruisutilities.teleport;

import net.shurui.shuruisutilities.commons.selections.WarpPoint;
import net.shurui.shuruisutilities.core.misc.RespawnHandler;
import net.shurui.shuruisutilities.core.misc.SafeSpotResolver;
import net.shurui.shuruisutilities.core.misc.TeleportHelper;
import net.shurui.shuruisutilities.shard.ShardConfig;
import net.shurui.shuruisutilities.shard.ShardDimensions;
import net.shurui.shuruisutilities.shard.ShardRouter;
import net.shurui.shuruisutilities.shard.ShardSync;
import net.shurui.shuruisutilities.shard.ShardTransfer;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Where "spawn" is, for everyone that asks: the {@code /spawn} command (in the Ragnarok Key since S10) and core's
 * public server-spawn placement ({@link #placeAtServerSpawn}, reached through {@code ShardSync.placeAtServerSpawn} by
 * the dungeons module's time-out return and spawn-target portals, and {@link #spawnOnArrival} for a network arrival
 * that asked for spawn). Both moved here from {@code ShardSync} unchanged (Sh1), because the keyless local path is
 * public behaviour and the network branch only runs while {@code ShardSync.active()}. Moved out of {@code CommandSpawn} unchanged when the command left core, because the
 * public placement path still needs the world-spawn answer with no key installed.
 */
public final class SpawnPoints
{
    private SpawnPoints()
    {
    }

    /**
     * Send a player to an open world server so they land at its spawn. True when the hop was started.
     *
     * <p>Only ever does anything on a shard network, and only from a server that is NOT itself an open world: an
     * open world with no spawn set has a configuration problem this cannot paper over, and routing away from it
     * would hide that. {@code ShardRouter.pickTarget} chooses the same server a fresh login would, and
     * {@code ShardTransfer.SPAWN_ON_ARRIVAL} tells the far side to place them at spawn rather than on the
     * coordinates they are carrying.
     *
     * <p>Records nothing for {@code /back}: see the overload, which the player's own {@code /spawn} uses.
     */
    public static boolean routeToOpenWorldSpawn(ServerPlayer player)
    {
        return routeToOpenWorldSpawn(player, false);
    }

    /**
     * As above, with {@code recordOrigin} deciding whether the place being left becomes the player's {@code /back}
     * point before the hop starts.
     *
     * <h2>Why it is a parameter</h2>
     * The back point must be written BEFORE {@code ShardTransfer.connect}, because the player record is serialised
     * into the payload as they leave; writing it on the far side is too late, and the arrival placement there has no
     * idea where they came from. Only a move the player ASKED for should be recorded, though, and this method serves
     * both: the {@code /spawn} command (record, so {@code /back} returns them across the network to where they stood)
     * and the system placements (a fresh login, a dungeon eject, a spawn-target portal), which must not overwrite a
     * real back point with a login position or with the inside of a dungeon that is closing behind them.
     *
     * <p>Off a shard network this is still a no-op: the {@code ShardSync.active()} check below returns false on
     * singleplayer, LAN and any ordinary server, so nothing is recorded and nothing moves.
     */
    public static boolean routeToOpenWorldSpawn(ServerPlayer player, boolean recordOrigin)
    {
        try
        {
            if (!ShardSync.active() || ShardConfig.role() == ShardConfig.Role.OPENWORLD)
            {
                return false;
            }
            String target = ShardRouter.pickTarget();
            if (target == null)
            {
                return false;
            }
            if (recordOrigin)
            {
                // Same three writes TeleportHelper.checkedTeleport makes for a local teleport, done here because
                // this move never reaches it. The server id is stamped inside setLastTeleportOrigin, so the record
                // says where it was taken and /back knows to come back across the network for it.
                PlayerInfo pi = PlayerInfo.get(player.getGameProfile().getId());
                pi.setLastTeleportOrigin(new WarpPoint(player));
                pi.setLastTeleportTime(System.currentTimeMillis());
                pi.setLastDeathLocation(null);
            }
            ShardTransfer.expect(player.getUUID(), target, ShardTransfer.SPAWN_ON_ARRIVAL);
            ChatOutputHandler.chatConfirmation(player, "Taking you to spawn on %s.", target);
            ShardTransfer.connect(player, target);
            return true;
        }
        catch (Throwable t)
        {
            // Never let this turn /spawn into an error: falling through to the ordinary message is a fine outcome.
            return false;
        }
    }

    /**
     * This server's own overworld world spawn, snapped to safe ground, or null when there is no overworld to read.
     *
     * <p>Always uses the server's OVERWORLD explicitly, never the player's current level, because
     * {@code ServerLevel.getSharedSpawnPos()} returns the overworld spawn for every other dimension anyway: a player
     * standing in the Nether or a planet dim still gets a real, standable overworld coordinate. {@code SafeSpotResolver}
     * relocates the raw spawn onto solid ground so a spawn that is buried, flooded or over the void still lands the
     * player somewhere they can stand. Shared by {@code /spawn}'s last-resort fallback and by spawn-targeting portals,
     * so both answer "take me to spawn" the same way when no SU spawn is configured.
     */
    public static WarpPoint worldSpawnPoint(ServerPlayer player)
    {
        try
        {
            if (player.getServer() == null)
            {
                return null;
            }
            net.minecraft.server.level.ServerLevel overworld = player.getServer().overworld();
            if (overworld == null)
            {
                return null;
            }
            net.minecraft.core.BlockPos spawn = overworld.getSharedSpawnPos();
            SafeSpotResolver.Result safe = SafeSpotResolver.resolve(overworld, spawn.getX() + 0.5, spawn.getY(),
                    spawn.getZ() + 0.5);
            return new WarpPoint(overworld, safe.x, safe.y, safe.z, player.getXRot(), player.getYRot());
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /**
     * Place a player at THIS server's spawn using the ONE resolution a fresh login and {@code /spawn} use, so every
     * "go to spawn" lands in the same place. Order: a server with no spawn of its own (the SMP) hands the player to an
     * open world with {@code SPAWN_ON_ARRIVAL}; otherwise, if the resolved SU spawn names a dimension this shard does
     * not host but another owns, the player is handed to that owner the same way; otherwise {@link #spawnOnArrival}
     * places them locally at the SU spawn, or the overworld world spawn when none is set. Never routes to another
     * shard unless this shard has no spawn of its own.
     *
     * <p>Public so the dungeon time-out return and spawn-target portals reuse it instead of each rolling their own
     * placement (a world-spawn safe-spot search or a raw shared spawn), which is what left those two landing players
     * at inconsistent spots rather than at the server spawn. Runs synchronously on the caller's (server) thread.
     */
    public static void placeAtServerSpawn(ServerPlayer p)
    {
        try
        {
            // A server with no open world spawn of its own (the SMP) routes the player to an open world, which
            // places them at its spawn on arrival. This is a no-op (returns false) on an open world, which has
            // a spawn of its own to use below.
            if (routeToOpenWorldSpawn(p))
                return;

            // The SU spawn /spawn resolves. When it names a dimension this shard does not host but another
            // shard owns, hand the player there with the same SPAWN_ON_ARRIVAL signal /spawn uses across
            // servers, rather than dropping them into a local empty copy this shard would then evict.
            WarpPoint point = RespawnHandler.getSpawn(p, null);
            if (point != null && ShardSync.active())
            {
                ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                        new ResourceLocation(point.getDimension()));
                if (!ShardDimensions.hosts(dim))
                {
                    String self = ShardConfig.get().serverId;
                    String owner = ShardDimensions.owner(dim);
                    if (owner != null && !owner.isBlank() && !owner.equalsIgnoreCase(self))
                    {
                        ShardTransfer.expect(p.getUUID(), owner, ShardTransfer.SPAWN_ON_ARRIVAL);
                        ShardTransfer.connect(p, owner);
                        LoggingHandler.sulog.info("[shard] Handing {} to '{}' to land at spawn there.",
                                p.getGameProfile().getName(), owner);
                        return;
                    }
                    // Not hosted and nowhere to hand off: fall through to the local best-effort spawn, which
                    // ends at the overworld world spawn, a dimension every shard hosts.
                }
            }

            spawnOnArrival(p);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[shard] Could not place {} at this server's spawn; leaving them where "
                    + "they are: {}", p.getGameProfile().getName(), t.toString());
        }
    }

    /**
     * Put a player who asked for spawn on another server at THIS server's spawn.
     *
     * <p>Prefers the SU spawn an operator set with {@code /setspawn}, and falls back to the world spawn so the
     * command still does something sensible on a server where nobody has set one. Failing silently is not an
     * option here: the player has already been moved to this server expecting to land at spawn, so leaving them
     * wherever the world put them would be the worst of both.
     *
     * <h2>Why this places with the UNCHECKED path</h2>
     * Every placement here is INVOLUNTARY: the player was already moved, and this only decides where they stand.
     * {@code TeleportHelper.teleport} is the voluntary pipeline and can decline at four points (teleport permission,
     * the cross-dimension pair, the per-kind cooldown, and the stand-still warmup), each of which silently leaves an
     * arriving player wherever the world dropped them. The warmup is the one that bit: a dungeon eject routes here,
     * and a player being ejected is being hit, so {@code TeleportInfo.check} cancelled the move 0.2 blocks later.
     * {@code forcedTeleport} keeps the safe-spot landing and the zone event and drops the consent checks.
     *
     * <p>It also leaves the {@code /back} record alone, which matters for a cross-server {@code /spawn}: the origin
     * recorded before the hop travels in the payload and is already restored by the time this runs, and the checked
     * path would overwrite it with wherever the player happened to load on this server.
     */
    public static void spawnOnArrival(ServerPlayer player)
    {
        try
        {
            // The operator's arrival warp wins when one is set, because it is the explicit answer to "where do
            // people who arrive here belong". The SMP is why this is first: it does not host the overworld, so
            // the world spawn fallback below cannot serve it, and its arrivalWarp is the same point /warp smp
            // goes to. An open world normally sets none and falls through to its own spawn, which is correct.
            WarpPoint arrival = ShardDimensions.arrivalWarp();
            if (arrival != null)
            {
                TeleportHelper.forcedTeleport(player, arrival);
                player.sendSystemMessage(Component.literal("Teleported to spawn."));
                return;
            }
            WarpPoint point = RespawnHandler.getSpawn(player, null);
            if (point != null)
            {
                TeleportHelper.forcedTeleport(player, point);
                player.sendSystemMessage(Component.literal("Teleported to spawn."));
                return;
            }
            ServerLevel overworld = player.getServer() == null ? null : player.getServer().overworld();
            if (overworld != null)
            {
                // On a shard that does not host the overworld (the SMP), a raw teleport into the local copy is caught
                // by ShardDimensions.onTravel and bounced cross-shard, the exact loop that stranded players who could
                // not reach the SMP. Route to a shard that hosts the overworld and let it place them at spawn rather
                // than dropping them into an unhosted dimension here.
                if (!ShardDimensions.hosts(overworld.dimension()))
                {
                    if (routeToOpenWorldSpawn(player))
                        return;
                    // Nowhere to route: leave the player where they loaded rather than start a bounce loop.
                    LoggingHandler.sulog.warn("[shard] {} asked for spawn on arrival, but this server does not host "
                            + "the overworld and there is nowhere to route them; leaving them where they loaded.",
                            player.getGameProfile().getName());
                    return;
                }
                // Resolve the world spawn the SAME way /spawn's last-resort fallback does (SpawnPoints.worldSpawnPoint):
                // it reads THIS overworld's shared spawn and snaps it onto safe standing ground with SafeSpotResolver, so a
                // spawn-target portal, a fresh login and a dungeon time-out all land on exactly the spot /spawn would, not
                // on a raw shared-spawn Y that can sit inside terrain or over a hole. Only if that resolution fails do we
                // fall back to the raw shared spawn rather than leaving the player where they were.
                WarpPoint worldSpawn = worldSpawnPoint(player);
                if (worldSpawn != null)
                {
                    TeleportHelper.doTeleport(player, worldSpawn);
                    player.sendSystemMessage(Component.literal("Teleported to spawn."));
                    return;
                }
                BlockPos spawn = overworld.getSharedSpawnPos();
                player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                        player.getYRot(), player.getXRot());
                player.sendSystemMessage(Component.literal("Teleported to spawn."));
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[shard] Could not place {} at spawn on arrival: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }
}
