package net.shurui.shuruisutilities.space;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * The "God of Destruction" faces a super body's single guardian can wear. Every super guardian is a
 * {@link PlanetGarrisonDefenderEntity} (SU's {@code DBSagasEntity} saga-fighter chassis) wearing an rgnpc GeckoLib
 * model: mint, {@link PlanetGarrisonDefenderEntity#setModelId}, hand back. Full saga AI, dies to a player, canonical
 * Hakaishin face.
 *
 * <p>The eight model ids used: {@code beerus} (U7), {@code champa} (U6), {@code godbelmod} (Belmod, U11),
 * {@code godsidra} (Sidra, U9), {@code godheles} (Heles, U2), {@code godquitela} (Quitela, U4), {@code godrumsshi}
 * (Rumsshi, U10), {@code godmosco} (Mosco, U3). Each is an installed rgnpc entry with a collision-box row in
 * {@link net.shurui.shuruisutilities.ragnarok.RgNpcModelSizes}, so no new geo or size row is needed. The face is chosen
 * deterministically from the body's star, so a respawn after a chunk unload mints the same god.
 */
public final class SuperPlanetGodRoster
{
    private SuperPlanetGodRoster()
    {
    }

    // Indexed by (star - 1) mod length. Today stars 1..7 map to the first seven; mosco is only reached if the star
    // count grows.
    private static final String[] GOD_MODEL_IDS = {
            "beerus",
            "champa",
            "godbelmod",
            "godsidra",
            "godheles",
            "godquitela",
            "godrumsshi",
            "godmosco"
    };

    /**
     * Fresh, un-added god for a super body, chosen deterministically from its star. Null if the type could not be
     * created (never expected). Never throws.
     */
    public static LivingEntity create(ServerLevel level, int star)
    {
        String modelId = GOD_MODEL_IDS[Math.floorMod(star - 1, GOD_MODEL_IDS.length)];
        PlanetGarrisonDefenderEntity god = PlanetGarrisonDefenderEntities.type().create(level);
        if (god == null)
        {
            return null;
        }
        god.setModelId(modelId);
        return god;
    }
}
