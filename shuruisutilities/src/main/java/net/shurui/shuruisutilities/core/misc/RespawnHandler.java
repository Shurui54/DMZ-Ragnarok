package net.shurui.shuruisutilities.core.misc;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.api.permissions.SUPermissions;
import net.shurui.shuruisutilities.api.permissions.GroupEntry;
import net.shurui.shuruisutilities.commons.selections.WarpPoint;
import net.shurui.shuruisutilities.multiworld.v2.MultiworldEngine;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.world.space.InhabitedPlanets;
import net.shurui.shuruisutilities.world.space.SpaceKeys;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class RespawnHandler
{

    protected Set<ServerPlayer> respawnPlayers = Collections
            .newSetFromMap(new WeakHashMap<>());

    public RespawnHandler()
    {
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static WarpPoint getSpawn(Player player, WarpPoint location, boolean doDefaultSpawn)
    {
        UserIdent ident = UserIdent.get(player);
        String spawnProperty = APIRegistry.perms.getPermission(ident, location == null ? null : location.toWorldPoint(),
                null, GroupEntry.toList(APIRegistry.perms.getPlayerGroups(ident)), SUPermissions.SPAWN_LOC, true);
        if (spawnProperty != null)
        {
            WarpPoint point = WarpPoint.fromString(spawnProperty);
            // if (point == null)
            // {
            // WorldPoint worldPoint = WorldPoint.fromString(spawnProperty);
            // if (worldPoint != null)
            // point = new WarpPoint(worldPoint, player.cameraYaw, player.cameraPitch);
            // }
            if (point != null)
            {
                // BELT-AND-BRACES GUARD: SU must NEVER resolve a spawn destination that sits in one of its own space,
                // planet-surface or planet dimensions. A stale or mistaken /setspawn run while standing on Planet Vegeta
                // (or in the space/surface dim) would otherwise strand every future spawn there, which is exactly the
                // reported bug. When the recorded SU spawn points at such a dimension we IGNORE it and fall through to the
                // ordinary default (bed / vanilla respawn), so nobody is ever sent to a planet by a spawn resolution.
                if (isSpaceOrPlanetDestination(point.getDimension()))
                {
                    LoggingHandler.sulog.warn(
                            "Ignoring SU spawn '{}' for player '{}': SU never spawns a player into its own "
                                    + "space/planet/surface dimensions; using the default spawn instead",
                            point.getDimension(), player.getGameProfile().getName());
                }
                else
                {
                    return point;
                }
            }
        }
        if (doDefaultSpawn)
            return null;
        else
            return new WarpPoint(((ServerPlayer) player).getRespawnDimension().location().toString(),
                    ((ServerPlayer) player).getRespawnPosition(), player.getXRot(), player.getYRot());
    }

    public static WarpPoint getSpawn(Player player, WarpPoint location)
    {
        return getSpawn(player, location, true);
    }

    // true when the dimension id names one of SU's own space, planet-surface or planet dimensions, which SU must never
    // hand out as a spawn destination. Covers: the space dim (shuruisutilities:space), the shared generated-planet
    // surface dim (shuruisutilities:planet_surface), every inhabited planet dim (InhabitedPlanets, e.g.
    // shuruisutilities:planet_vegeta), the authored Beerus body dim, and, by the SU planet naming convention, any future
    // shuruisutilities:planet_* dimension so a newly added planet is covered without editing this list.
    static boolean isSpaceOrPlanetDestination(String dim)
    {
        if (dim == null)
            return false;
        if (dim.equals(SpaceKeys.SPACE_ID.toString()))
            return true;
        if (dim.equals(SpaceKeys.SURFACE_ID.toString()))
            return true;
        if (InhabitedPlanets.isInhabited(dim))
            return true;
        // Compared against a STORED dimension string, so BOTH namespaces must be handled. The SU dimensions were
        // renamed from shuruisutilities to dmz_ragnarok in the dimension/biome rename stage: a migrated world stores
        // the new dmz_ragnarok form, but a player who set a respawn before migration has the old shuruisutilities
        // form persisted in their data. Match either so neither is ever handed out as a spawn destination. The
        // planet_ prefix also covers any future planet_* dimension without editing this list.
        if (dim.startsWith("dmz_ragnarok:planet_") || dim.startsWith("shuruisutilities:planet_"))
            return true;
        if (dim.equals("dmz_ragnarok:beerus_planet") || dim.equals("shuruisutilities:beerus_planet"))
            return true;
        return false;
    }

    public static WarpPoint getPlayerSpawn(Player player, WarpPoint location, boolean doDefaultSpawn)
    {
        UserIdent ident = UserIdent.get(player);

        boolean bedEnabled = APIRegistry.perms.checkUserPermission(ident, SUPermissions.SPAWN_BED);
        if (bedEnabled)
        {
            ServerPlayer entity = (ServerPlayer) player;
            BlockPos spawn = entity.getRespawnPosition();
            if (spawn != null)
            {
                // Bed seems OK, so just return null to let default MC code handle respawn
                if (doDefaultSpawn)
                    return null;
                return new WarpPoint(player.level().dimension().location().toString(), spawn, player.getXRot(), player.getYRot());
            }
        }

        return getSpawn(player, location, doDefaultSpawn);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onPlayerDeath(LivingDeathEvent e)
    {
        if (e.getEntity() instanceof ServerPlayer)
        {
            ServerPlayer player = (ServerPlayer) e.getEntity();
            PlayerInfo pi = PlayerInfo.get(player.getGameProfile().getId());
            pi.setLastDeathLocation(new WarpPoint(player));
            pi.setLastTeleportOrigin(pi.getLastDeathLocation());
        }
    }

    @SubscribeEvent
    public void doFirstRespawn(EntityJoinLevelEvent e)
    {
        if (!e.getEntity().getClass().equals(ServerPlayer.class))
            return;
        ServerPlayer player = (ServerPlayer) e.getEntity();
        if (respawnPlayers.remove(player))
        {
            WarpPoint p = getPlayerSpawn(player, null, true);
            if (p != null)
                teleportToSpawn(player, p);
        }
    }

    // teleport to a resolved spawn, robust against the spawn's dimension not resolving at the moment
    // respawn/first-join fires. the global spawn (/setspawn) often points at an SU multiworld dim; those
    // register at server start but during the earliest lifecycle events (or after unload) the level map may not
    // resolve it yet, so getWorld() returns null and doTeleport would silently leave the player at vanilla spawn.
    // recovery: resolve now -> ensure/reload the dim and retry -> defer one tick and retry once -> WARN and
    // leave them (never crash).
    private void teleportToSpawn(ServerPlayer player, WarpPoint p)
    {
        if (p.getWorld() != null)
        {
            TeleportHelper.doTeleport(player, p);
            return;
        }

        // didn't resolve. if SU knows this multiworld, ensure it's loaded and retry now.
        ensureSpawnDimensionLoaded(p);
        if (p.getWorld() != null)
        {
            TeleportHelper.doTeleport(player, p);
            return;
        }

        // still unresolved: defer one tick, retry once, then give up loudly
        TaskRegistry.runLater(() -> {
            if (!player.hasDisconnected())
            {
                ensureSpawnDimensionLoaded(p);
                if (p.getWorld() != null)
                {
                    TeleportHelper.doTeleport(player, p);
                    return;
                }
            }
            LoggingHandler.sulog.warn(
                    "Could not send player '{}' to spawn: dimension '{}' is not resolvable after a one-tick retry - "
                            + "player left at vanilla spawn",
                    player.getGameProfile().getName(), p.getDimension());
        });
    }

    // if the spawn targets a known SU multiworld, (re)register the dim so it resolves. no-op for non-SU dims,
    // cheap when already loaded.
    private static void ensureSpawnDimensionLoaded(WarpPoint p)
    {
        String dim = p.getDimension();
        if (dim == null)
            return;
        try
        {
            MultiworldEngine.manager().ensureWorldLoaded(dim);
        }
        catch (Exception ex)
        {
            LoggingHandler.sulog.warn("Failed while ensuring spawn dimension '{}' is loaded: {}", dim, ex.toString());
        }
    }

    @SubscribeEvent
    public void playerLoadFromFile(PlayerEvent.LoadFromFile event)
    {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        File f = new File(event.getPlayerDirectory(), event.getPlayerUUID() + ".dat");
        if (!f.exists())
        {
            // First appearance of this player IN THIS WORLD (no <uuid>.dat on disk). PlayerInfo is install-scoped,
            // so a stale actualLogOutPoint recorded on a PREVIOUS world (e.g. logging out on Planet Vegeta) survives
            // world deletion and would otherwise get replayed by PlayerInvalidRegistryLoginFix.playerLogin, warping a
            // brand-new player onto that old dimension. That is the "spawned on Vegeta after making a new world" bug.
            // A genuine same-world logout always writes a .dat, so this branch only ever runs on a truly fresh join:
            // clearing the origin here makes the default first spawn the overworld/bed/spawn resolution below, and it
            // leaves /setspawn and within-world logout-return behaviour completely untouched.
            PlayerInfo pi = PlayerInfo.get(player.getGameProfile().getId());
            if (pi.getActualLogOutPoint() != null)
            {
                LoggingHandler.sulog.warn(
                        "Discarding stale actualLogOutPoint '{}' for first-join player '{}' (no world-local player data); "
                                + "using the default spawn instead of replaying a previous world's logout location",
                        pi.getActualLogOutPoint().getDimension(), player.getGameProfile().getName());
                pi.setActualLogOutPoint(null);
                pi.save();
            }
            WarpPoint p = getPlayerSpawn(player, null, true);
            if (p != null)
            {
                if (!player.level().dimension().location().toString().equals(p.getDimension()))
                    respawnPlayers.add(player);
                else
                    player.moveTo(p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
            }
        }
    }

    @SubscribeEvent
    public void doRespawn(PlayerRespawnEvent event)
    {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        player.connection.player = player;

        WarpPoint lastDeathLocation = PlayerInfo.get(player.getGameProfile().getId()).getLastDeathLocation();
        if (lastDeathLocation == null)
            lastDeathLocation = new WarpPoint(player);

        WarpPoint p = getPlayerSpawn(player, lastDeathLocation, true);
        if (p != null)
            teleportToSpawn(player, p);
    }

}
