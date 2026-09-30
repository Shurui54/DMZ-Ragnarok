package net.shurui.shuruisutilities.space;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.world.space.SurfaceTravelData;

/**
 * Dev/test entry points for the visual-test harness: land a player on a freshly-generated planet of a chosen kind so a
 * scripted shot can capture the per-planet surface sky, weather and overhead rings. It reuses the real landing
 * primitives ({@link SurfaceStamp#ensureAndLandingPos}, {@link SurfaceTravelData} and a teleport), so the planet it
 * lands on is a genuine v2 generated planet, stamped exactly as a normal landing would stamp it. Nothing here is wired
 * into a command or an event; it exists only to be called from the harness on the server thread.
 */
public final class SpaceSurfaceDebug
{
    private SpaceSurfaceDebug()
    {
    }

    // a plausible space-body anchor for the landed planet, so the client draws the sun and siblings around a sensible
    // point. The exact value only affects the sun's bearing and the sibling gather, not the weather or the own rings.
    private static final double DEBUG_BODY_X = 30_000.0;

    /**
     * Land the player on a fresh WET planet (an OVERWORLD-theme world, blue sky) that is NOT ringed, and return its id.
     * Good for the clear / rain / storm / snow shots. Returns null if none was found (should never happen).
     */
    public static String landWetPlanet(ServerPlayer player)
    {
        String id = findId(theme -> theme == SurfaceStamp.Theme.OVERWORLD, false);
        return id == null ? null : land(player, id);
    }

    /**
     * Land the player on a fresh RINGED planet with a coloured sky (OVERWORLD / NAMEK / KAIO), and return its id. Good
     * for the overhead-ring shot. Returns null if none was found.
     */
    public static String landRingedPlanet(ServerPlayer player)
    {
        String id = findId(theme -> theme == SurfaceStamp.Theme.OVERWORLD || theme == SurfaceStamp.Theme.NAMEK
                || theme == SurfaceStamp.Theme.KAIO, true);
        return id == null ? null : land(player, id);
    }

    /**
     * Land the player on a fresh planet that is BOTH a wet OVERWORLD theme (blue sky) AND ringed, and return its id. This
     * is what the visual-test harness uses so a single landing shows the weather (clear/rain/storm/snow on a blue sky)
     * and this planet's own overhead rings, with no second teleport that a lagging server could reorder. Returns null if
     * none was found (should never happen: an OVERWORLD ringed cell is common).
     */
    public static String landWetRingedPlanet(ServerPlayer player)
    {
        String id = findId(theme -> theme == SurfaceStamp.Theme.OVERWORLD, true);
        return id == null ? null : land(player, id);
    }

    // scan synthetic generated-planet ids for the first whose derived theme and ringed-ness match, skipping the Beerus
    // footprint cell near the origin. Theme and ring status are pure functions of the id, so this is a cheap search.
    private static String findId(java.util.function.Predicate<SurfaceStamp.Theme> themeOk, boolean wantRinged)
    {
        for (long n = 1; n < 200_000; ++n)
        {
            String id = GeneratedPlanets.ID_PREFIX + Long.toHexString(n * 0x9E3779B97F4A7C15L);
            if (PlanetRings.isRinged(id) != wantRinged)
            {
                continue;
            }
            if (!themeOk.test(SurfaceStamp.surfaceThemeFor(id)))
            {
                continue;
            }
            Vec3 c = SurfaceDimension.cellCentre(id);
            if (Math.abs(c.x) < 4000.0 && Math.abs(c.z) < 4000.0)
            {
                continue;   // keep clear of the Beerus footprint cell at the surface origin
            }
            return id;
        }
        return null;
    }

    /**
     * Land the player on a fresh planet whose derived theme is {@code themeName} (e.g. END or NETHER), NOT ringed, and
     * return its id. Used by the chorus/item-spam check (an END planet has chorus flora, a NETHER planet its own set), so
     * a scripted run can count the ItemEntity left in planet_surface after the stamp. Returns null if none was found.
     */
    public static String landThemePlanet(ServerPlayer player, String themeName)
    {
        SurfaceStamp.Theme want;
        try
        {
            want = SurfaceStamp.Theme.valueOf(themeName);
        }
        catch (Exception e)
        {
            return null;
        }
        String id = findId(theme -> theme == want, false);
        return id == null ? null : land(player, id);
    }

    /**
     * Geometry of a landed planet for the edge-wrap client check: {@code {centreX, centreZ, half, size, generated}} where
     * generated is 1.0 when the surface stamp has completed (so the wrap may fire), else 0.0. half is the real playable
     * half-extent (size / 2), the edge the seam wrap uses, NOT the stamped margin. Returns null if the id is unknown.
     */
    public static double[] planetWrapGeom(MinecraftServer server, String id)
    {
        if (server == null || id == null)
        {
            return null;
        }
        int size = GeneratedPlanetClaims.stampedSizeForId(server, id);
        if (size <= 0)
        {
            return null;
        }
        Vec3 c = SurfaceDimension.cellCentre(id);
        boolean gen = GeneratedPlanetClaims.get(server).isSurfaceGenerated(id);
        return new double[] { c.x, c.z, size / 2.0, size, gen ? 1.0 : 0.0 };
    }

    /**
     * Count how many of the {@code depth} columns just PAST the +x real edge (from half+1 to half+depth, at the cell
     * centre's z) carry a non-air surface block within a wide Y band, i.e. how far the stamped terrain continues into the
     * wrap margin beyond the playable edge. On a v4 planet the compact margin is 64 blocks, so this should return close to
     * {@code min(depth, 64)}. Returns -1 if the id is unknown or the surface dimension is unavailable.
     */
    public static int marginSolidColumns(MinecraftServer server, String id, int depth)
    {
        if (server == null || id == null)
        {
            return -1;
        }
        int size = GeneratedPlanetClaims.stampedSizeForId(server, id);
        if (size <= 0)
        {
            return -1;
        }
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            return -1;
        }
        Vec3 c = SurfaceDimension.cellCentre(id);
        int half = size / 2;
        int cx = (int) Math.floor(c.x);
        int cz = (int) Math.floor(c.z);
        int solid = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int d = 1; d <= depth; ++d)
        {
            boolean columnHasGround = false;
            // scan a generous vertical band around the surface layer for any non-air block in this margin column.
            for (int y = surface.getMinBuildHeight() + 1; y < surface.getMaxBuildHeight() && !columnHasGround; ++y)
            {
                pos.set(cx + half + d, y, cz);
                if (!surface.getBlockState(pos).isAir())
                {
                    columnHasGround = true;
                }
            }
            if (columnHasGround)
            {
                solid++;
            }
        }
        return solid;
    }

    /**
     * The number of loose {@link ItemEntity} currently in planet_surface within {@code radius} blocks of the planet cell
     * centre. Used by the chorus/plant item-spam check: after a stamp the surface should hold roughly zero dropped items
     * (a plant/decor that popped as an item would show up here). Returns -1 if the surface dimension is unavailable.
     */
    public static int surfaceItemCount(MinecraftServer server, String id, double radius)
    {
        if (server == null || id == null)
        {
            return -1;
        }
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            return -1;
        }
        Vec3 c = SurfaceDimension.cellCentre(id);
        AABB box = new AABB(c.x - radius, surface.getMinBuildHeight(), c.z - radius,
                c.x + radius, surface.getMaxBuildHeight(), c.z + radius);
        return surface.getEntitiesOfClass(ItemEntity.class, box).size();
    }

    private static String land(ServerPlayer player, String id)
    {
        MinecraftServer server = player.getServer();
        if (server == null)
        {
            return null;
        }
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            return null;
        }
        Vec3 landing = SurfaceStamp.ensureAndLandingPos(server, surface, id);
        SurfaceTravelData.setPlanet(player, id, DEBUG_BODY_X, 0.0, 0.0);
        player.teleportTo(surface, landing.x, landing.y, landing.z, player.getYRot(), player.getXRot());
        // push the surface-sky descriptor now so the client colours the sky and knows the planet key immediately.
        SurfaceSkySync.update(player, id);
        return id;
    }
}
