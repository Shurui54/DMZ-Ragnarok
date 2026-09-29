package net.shurui.shuruisutilities.world.space;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * Per-player record of WHICH generated planet a player is currently standing on, kept in PlayerPersisted NBT so it
 * survives relog (Forge copies the sub-tag onto the respawn clone), exactly like {@link SpaceTravelData}. We need the
 * planet id on the surface side because the id-&gt;cell mapping in {@link SurfaceDimension} is a one-way hash fold and
 * cannot be inverted from the player's position: the boundary check and the fly-up-to-leave both need to know which
 * cell centre and which surface size to measure against, and this is the only place that answer is recorded.
 *
 * <p>It also records the SPACE-body position the player came down from, so leaving the surface can put them back
 * beside that exact body. The space body's position derives from the planet's SECTOR coordinates, but the id embeds
 * only the sector hash (a one-way fold), so the body position cannot be recovered from the id alone without an
 * unbounded search. Recording it at landing time (when we have the body in hand) avoids that search entirely. This is
 * transient TRIP state on the player, not authoritative planet state: the authoritative claim and surface-generated
 * flag live in {@link GeneratedPlanetClaims} keyed by the id, never here.
 *
 * <p>Set on landing, cleared on leaving the surface. It can legitimately be ABSENT while a player is on the surface:
 * any arrival that is not a normal landing (a /tp to a player standing on a planet, a login or respawn onto the
 * surface) reaches the surface with no record. In that case the surface tick does NOT give up: it RESOLVES the planet
 * from the player's position (their cell inverts back to the stamped-surface set even though the id itself cannot,
 * see {@link GeneratedPlanetClaims#surfaceGeneratedIds}) and writes this record, after which the border and the
 * return-to-space path work exactly as for a player who landed. Only a genuinely empty coordinate (an admin teleported
 * to a cell with no stamped surface) has no planet to resolve, and only that case keeps the old defensive
 * overworld-spawn fallback.
 */
public final class SurfaceTravelData
{
    private SurfaceTravelData()
    {
    }

    private static final String TAG = "su_surface_planet";
    private static final String TAG_BODY = "su_surface_body";

    private static CompoundTag persisted(Player player)
    {
        CompoundTag data = player.getPersistentData();
        CompoundTag pt = data.getCompound(Player.PERSISTED_NBT_TAG);
        if (!data.contains(Player.PERSISTED_NBT_TAG))
        {
            data.put(Player.PERSISTED_NBT_TAG, pt);
        }
        return pt;
    }

    // the generated planet id the player is on, or "" if none.
    public static String planetId(Player player)
    {
        return persisted(player).getString(TAG);
    }

    public static boolean hasPlanet(Player player)
    {
        return !planetId(player).isEmpty();
    }

    // record the planet id AND the space-body position to return beside. The caller has the body in hand at landing.
    public static void setPlanet(Player player, String planetId, double bodyX, double bodyY, double bodyZ)
    {
        CompoundTag pt = persisted(player);
        pt.putString(TAG, planetId);
        CompoundTag body = new CompoundTag();
        body.putDouble("x", bodyX);
        body.putDouble("y", bodyY);
        body.putDouble("z", bodyZ);
        pt.put(TAG_BODY, body);
    }

    // record the planet id but NO space-body position. Used when a teleported-in player is resolved from their position
    // and no real space body could be derived for them (GeneratedPlanets.findGenerated found no anchor near the target's
    // space cell). Deliberately does not stamp a body: leaveSurface then takes its existing no-body branch and drops the
    // player on the standard space arrival column, a graceful fallback, rather than a bogus body position. We must NOT
    // reuse the surface cell centre as the body here (the way GuildRaid.bringRaiderIn does) because that centre lives in
    // the surface dimension near 29 million blocks, and unlike a raider (whose fly-up is a forfeit that never runs
    // leaveSurface) this player's fly-up DOES run leaveSurface, which would then fling them to that far-off coordinate
    // in space. Clears any stale body so hasBody reads false.
    public static void setPlanetWithoutBody(Player player, String planetId)
    {
        CompoundTag pt = persisted(player);
        pt.putString(TAG, planetId);
        pt.remove(TAG_BODY);
    }

    public static boolean hasBody(Player player)
    {
        return persisted(player).contains(TAG_BODY);
    }

    public static double bodyX(Player player)
    {
        return persisted(player).getCompound(TAG_BODY).getDouble("x");
    }

    public static double bodyY(Player player)
    {
        return persisted(player).getCompound(TAG_BODY).getDouble("y");
    }

    public static double bodyZ(Player player)
    {
        return persisted(player).getCompound(TAG_BODY).getDouble("z");
    }

    public static void clear(Player player)
    {
        CompoundTag pt = persisted(player);
        pt.remove(TAG);
        pt.remove(TAG_BODY);
    }
}
