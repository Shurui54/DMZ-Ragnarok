package net.shurui.shuruisutilities.compat.dmz;

import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.core.SUConfig;

/**
 * Guard entry point for seeding SU's Planet Vegeta gravity entry into DragonMineZ's per-dimension gravity config. This
 * class holds no DMZ imports: it only confirms DMZ is present before touching {@link PlanetGravityBridge}, which does.
 * Follows the optional-dependency pattern.
 *
 * <p>DMZ already owns the whole per-dimension gravity pipeline (config + tick logic + client sync); SU just contributes
 * one entry so Planet Vegeta ships with 10x gravity out of the box instead of needing a hand-edited config file. See
 * {@link PlanetGravityBridge} for why the value has to reach the on-disk file and not only the live map.
 */
public final class PlanetGravityCompat
{
    private PlanetGravityCompat() {}

    /** The dimension id whose gravity we seed. Matches data/dmz_ragnarok/dimension/planet_vegeta.json (renamed from
     *  shuruisutilities in the dimension/biome rename stage). */
    public static final String PLANET_VEGETA_DIM = "dmz_ragnarok:planet_vegeta";

    /**
     * Seed the Planet Vegeta gravity entry into DMZ's config if it is not already there. Seed-if-absent, so an admin's
     * tuned value is never stomped. Server-side only, no-op when DMZ is absent, never throws. Call at ServerStarted,
     * after DMZ's ConfigManager has loaded general-server.json and before any player joins (the client sync reads the
     * file on login).
     */
    public static void seedOnServerStart()
    {
        if (!ModList.get().isLoaded("dragonminez"))
            return;
        PlanetGravityBridge.seed(SUConfig.planetVegetaGravity);
    }
}
