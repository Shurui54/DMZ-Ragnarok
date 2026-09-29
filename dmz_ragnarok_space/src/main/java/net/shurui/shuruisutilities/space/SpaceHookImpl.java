package net.shurui.shuruisutilities.space;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import net.shurui.dev.sdu.api.SpaceHook;

/**
 * The Space module's implementation of {@link SpaceHook}, backing the guild-raid planet reads and the space-arrival
 * grace with the real generated-planet state. Registered from {@code ShuruisUtilities} for now (batch B moves the
 * registration into the Space {@code @Mod} constructor). Every method delegates straight to the authoritative Space
 * class the guild code used to call directly, so behaviour is byte-identical to before the hook existed.
 */
public final class SpaceHookImpl implements SpaceHook.Provider
{
    private SpaceHookImpl()
    {
    }

    /** Register this implementation with the core hook. Called once at load. */
    public static void register()
    {
        SpaceHook.register(new SpaceHookImpl());
    }

    @Override
    public String claimedPlanetOf(MinecraftServer server, String guildId)
    {
        return GeneratedPlanetClaims.get(server).claimedByGuild(guildId);
    }

    @Override
    public String owningGuildOf(MinecraftServer server, String planetId)
    {
        return GeneratedPlanetClaims.get(server).owner(planetId);
    }

    @Override
    public String planetName(String planetId)
    {
        return GeneratedPlanets.nameFor(planetId);
    }

    @Override
    public int stampedSize(MinecraftServer server, String planetId)
    {
        return GeneratedPlanetClaims.stampedSizeForId(server, planetId);
    }

    @Override
    public boolean isDestructible(String planetId)
    {
        return GeneratedPlanets.isDestructible(planetId);
    }

    @Override
    public boolean isDestroyed(MinecraftServer server, String planetId)
    {
        return GeneratedPlanetClaims.get(server).isDestroyed(planetId);
    }

    @Override
    public Vec3 cellCentre(String planetId)
    {
        return SurfaceDimension.cellCentre(planetId);
    }

    @Override
    public ServerLevel surfaceLevel(MinecraftServer server)
    {
        return SurfaceDimension.level(server);
    }

    @Override
    public Vec3 ensureSurfaceAndLanding(MinecraftServer server, ServerLevel surface, String planetId)
    {
        return SurfaceStamp.ensureAndLandingPos(server, surface, planetId);
    }

    @Override
    public int guildRaidSpoilsSeconds()
    {
        return PlanetSpawnModule.guildRaidSpoilsSeconds();
    }

    @Override
    public void grantArrivalGrace(ServerPlayer player)
    {
        SpaceTravelModule.grantArrivalGrace(player);
    }
}
