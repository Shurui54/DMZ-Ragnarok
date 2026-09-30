package net.shurui.shuruisutilities.space;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.world.space.SurfaceTravelData;

/**
 * Server-side sender for {@link PacketSurfaceSky}: it tells each client WHICH generated planet the player is standing on
 * (its stamped {@link SurfaceStamp.Theme} and its space-body anchor) so the client can draw the per-planet surface sky
 * and the sun and sibling planets. Only the CLIENT can turn that into a sky; only the SERVER can know it, because a
 * planet's id folds one-way to its surface cell.
 *
 * <p>It is edge-triggered: {@link #update} and {@link #cleared} are called every surface tick, but a packet is sent
 * ONLY when the answer changes (the player lands, walks into a different planet's cell, relogs onto the surface, or
 * leaves the surface). The per-player memo of the last-sent planet id makes that a cheap string compare on the common
 * (unchanged) tick, so this adds no per-tick traffic.
 */
public final class SurfaceSkySync
{
    private SurfaceSkySync()
    {
    }

    // Last planet id we told each player about. Absent from the map (or "") means we last told them "no surface planet".
    // Bounded to players currently on a surface planet: an entry is removed when we send them "absent".
    private static final Map<UUID, String> lastSent = new ConcurrentHashMap<>();

    /**
     * The player is on the generated planet {@code planetId}. Sends the theme and space-body anchor if this differs from
     * what the player was last told. Resolving the STAMPED theme (not the derived one) keeps the sky colour in step with
     * the ground even if the theme weights were retuned after the planet was stamped.
     */
    public static void update(ServerPlayer player, String planetId)
    {
        if (planetId == null || planetId.isEmpty())
        {
            cleared(player);
            return;
        }
        UUID id = player.getUUID();
        if (planetId.equals(lastSent.get(id)))
        {
            return;
        }
        MinecraftServer server = player.getServer();
        SurfaceStamp.Theme theme = GeneratedPlanetClaims.stampedThemeForId(server, planetId);
        Vec3 body = SurfaceTravelData.hasBody(player)
                ? new Vec3(SurfaceTravelData.bodyX(player), SurfaceTravelData.bodyY(player),
                        SurfaceTravelData.bodyZ(player))
                : null;
        NetworkUtils.sendTo(PacketSurfaceSky.present(planetId, theme, body), player);
        lastSent.put(id, planetId);
    }

    /**
     * The player is not on a resolved surface planet (off the surface dimension, on Beerus's footprint which uses the
     * biome sky, or on the surface with no planet resolved yet). Sends an "absent" clear only if the player was last
     * told they were on one.
     */
    public static void cleared(ServerPlayer player)
    {
        UUID id = player.getUUID();
        String prev = lastSent.get(id);
        if (prev == null || prev.isEmpty())
        {
            return;
        }
        NetworkUtils.sendTo(PacketSurfaceSky.absent(), player);
        lastSent.remove(id);
    }

    /** Drop a player's memo on logout so the map never grows without bound. */
    public static void forget(UUID id)
    {
        lastSent.remove(id);
    }
}
