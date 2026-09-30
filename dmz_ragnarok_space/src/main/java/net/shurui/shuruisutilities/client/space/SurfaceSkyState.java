package net.shurui.shuruisutilities.client.space;

import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.space.SurfaceStamp;

/**
 * Client-only holder for WHICH generated planet the local player is currently standing on, and where it sits in space.
 * The surface dimension (dmz_ragnarok:planet_surface) is a single shared void world in which a generated planet's id
 * folds one-way to a grid cell (see {@link net.shurui.shuruisutilities.space.SurfaceDimension}), so the client cannot
 * invert its position back to a planet, its surface theme or its space-body position. The server knows all three (it
 * keeps them per player in {@code SurfaceTravelData} and resolves the stamped theme), so it pushes them here with
 * {@link net.shurui.shuruisutilities.space.PacketSurfaceSky}. This is the ONLY input the per-planet surface sky
 * ({@link PlanetSurfaceEffects}) and the surface sky bodies ({@link SpaceBodyRenderer}) need.
 *
 * <p>Absent (no theme, no body) means the client either is not on a surface planet, or has not been told yet. In that
 * case the surface sky falls back to the biome-driven sky ({@link net.shurui.shuruisutilities.client.planet.PlanetSkyTints},
 * which still colours Beerus violet and Vegeta green) and draws no sky bodies, which is exactly the pre-B1 behaviour.
 *
 * <p>All fields are volatile: written on the client network thread (the packet handler enqueues to the client thread)
 * and read on the client render thread, mirroring how {@link net.shurui.shuruisutilities.space.SpaceLayout} keeps its
 * synced snapshots.
 */
public final class SurfaceSkyState
{
    private SurfaceSkyState()
    {
    }

    // The surface theme of the planet the player is on, or null when unknown / not on a surface planet. Drives the
    // atmosphere colours through PlanetSkyPalette.
    private static volatile SurfaceStamp.Theme theme;

    // The stable key (generated id) of the planet the player is on, or "" when unknown. Keys the planet's own
    // deterministic weather (PlanetWeather) and whether it is ringed (PlanetRings), so the client can draw this planet's
    // weather and its ring system arcing across its own sky.
    private static volatile String planetKey = "";

    // The SPACE-body position of the planet the player is on (the anchor the sky bodies are drawn relative to), or null
    // when unknown. This is the planet's position in the open-space layout, NOT the player's surface coordinate.
    private static volatile Vec3 bodyPos;

    // Push the current surface planet descriptor (called by the packet handler on the client thread). A null theme AND a
    // null body clears the state.
    public static void set(String newPlanetKey, SurfaceStamp.Theme newTheme, Vec3 newBodyPos)
    {
        planetKey = newPlanetKey == null ? "" : newPlanetKey;
        theme = newTheme;
        bodyPos = newBodyPos;
    }

    public static void clear()
    {
        planetKey = "";
        theme = null;
        bodyPos = null;
    }

    // The current surface theme, or null.
    public static SurfaceStamp.Theme theme()
    {
        return theme;
    }

    // The current planet's stable key, or "" when unknown. Never null.
    public static String planetKey()
    {
        return planetKey;
    }

    // The current planet's space-body position, or null when unknown. Sky bodies are only drawn when this is non-null.
    public static Vec3 bodyPos()
    {
        return bodyPos;
    }

    public static boolean hasBody()
    {
        return bodyPos != null;
    }
}
