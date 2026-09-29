package net.shurui.dev.sdu.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Core hook for the Space module's generated-planet state, so the guild raid system (planet claims, the raid arena
 * on a planet surface, the destruction spoil) can read and act on planets without naming the Space classes directly.
 *
 * <p>This lives in core (sdu), which every module can read from. The Space module registers its implementation at
 * load; guilds (and the vanilla-teleport safe-landing mixin) read it. When Space is not installed the hook is unset:
 * there are no claims and no planets, so {@link #claimedPlanetOf} and {@link #owningGuildOf} return null,
 * {@link #surfaceLevel} returns null, {@link #isDestructible} / {@link #isDestroyed} return false, and the arrival
 * grace is a no-op. A guild raid therefore refuses to start when Space is absent (there is no claimed planet to raid,
 * surfaced through {@code RaidCheck}), while the rest of guilds keeps working, the same way a rift refuses to open
 * without Dungeons. So no module classloads Space, and Space can move to a separate jar later without touching guilds.
 *
 * <p>Signatures use vanilla / primitive types only, because core sdu/api must not import SU types.
 */
public final class SpaceHook
{
    /** Implemented by the Space module. */
    public interface Provider
    {
        /** The planet id this guild owns, or null when it owns none. */
        String claimedPlanetOf(MinecraftServer server, String guildId);

        /** The guild id that owns this planet, or null when it is unclaimed. */
        String owningGuildOf(MinecraftServer server, String planetId);

        /** The short display name for a planet id, or "" when unknown. */
        String planetName(String planetId);

        /** The stamped surface size (edge length in blocks) for a planet id, or 0 when unknown. */
        int stampedSize(MinecraftServer server, String planetId);

        /** Whether a planet id may ever be destroyed (a generated planet or a moon; a fixed world never). */
        boolean isDestructible(String planetId);

        /** Whether a planet id has already been destroyed (its slot is rubble). */
        boolean isDestroyed(MinecraftServer server, String planetId);

        /** The world-space centre of a planet's surface cell. */
        Vec3 cellCentre(String planetId);

        /** The shared planet-surface dimension, or null when it is missing (datapack absent). */
        ServerLevel surfaceLevel(MinecraftServer server);

        /** Stamp the surface once for a planet and return its ground-snapped centre landing position. */
        Vec3 ensureSurfaceAndLanding(MinecraftServer server, ServerLevel surface, String planetId);

        /** The configured guild-raid destruction-spoil window, in seconds (0 = never lapses). */
        int guildRaidSpoilsSeconds();

        /** Stamp the space-arrival grace on a player so a fresh arrival is not bounced straight back out. */
        void grantArrivalGrace(ServerPlayer player);
    }

    private static volatile Provider impl;

    private SpaceHook()
    {
    }

    /** Called once by the Space module at load. */
    public static void register(Provider p)
    {
        impl = p;
    }

    /** True when the Space module is present and has registered its provider. */
    public static boolean available()
    {
        return impl != null;
    }

    public static String claimedPlanetOf(MinecraftServer server, String guildId)
    {
        Provider p = impl;
        return p == null ? null : p.claimedPlanetOf(server, guildId);
    }

    public static String owningGuildOf(MinecraftServer server, String planetId)
    {
        Provider p = impl;
        return p == null ? null : p.owningGuildOf(server, planetId);
    }

    public static String planetName(String planetId)
    {
        Provider p = impl;
        return p == null ? "" : p.planetName(planetId);
    }

    public static int stampedSize(MinecraftServer server, String planetId)
    {
        Provider p = impl;
        return p == null ? 0 : p.stampedSize(server, planetId);
    }

    public static boolean isDestructible(String planetId)
    {
        Provider p = impl;
        return p != null && p.isDestructible(planetId);
    }

    public static boolean isDestroyed(MinecraftServer server, String planetId)
    {
        Provider p = impl;
        return p != null && p.isDestroyed(server, planetId);
    }

    /** The planet cell centre, or {@link Vec3#ZERO} when Space is absent (never reached in an active raid). */
    public static Vec3 cellCentre(String planetId)
    {
        Provider p = impl;
        return p == null ? Vec3.ZERO : p.cellCentre(planetId);
    }

    public static ServerLevel surfaceLevel(MinecraftServer server)
    {
        Provider p = impl;
        return p == null ? null : p.surfaceLevel(server);
    }

    /** The landing position, or {@link Vec3#ZERO} when Space is absent (never reached: surfaceLevel is null first). */
    public static Vec3 ensureSurfaceAndLanding(MinecraftServer server, ServerLevel surface, String planetId)
    {
        Provider p = impl;
        return p == null ? Vec3.ZERO : p.ensureSurfaceAndLanding(server, surface, planetId);
    }

    public static int guildRaidSpoilsSeconds()
    {
        Provider p = impl;
        return p == null ? 0 : p.guildRaidSpoilsSeconds();
    }

    public static void grantArrivalGrace(ServerPlayer player)
    {
        Provider p = impl;
        if (p != null)
        {
            p.grantArrivalGrace(player);
        }
    }
}
