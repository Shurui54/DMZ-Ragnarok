package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Guard for reading DMZ's ki weapon damage, so the summoned staff hits for what a DMZ ki weapon hits
 * for rather than for a number of our own.
 */
public final class KiWeaponStats
{
    private KiWeaponStats() {}

    /** DMZ's own ki weapon types. The staff borrows the scythe's profile; see the item classes for which is which. */
    public static final String TYPE_BLADE = "blade";
    public static final String TYPE_SCYTHE = "scythe";
    public static final String TYPE_CLAWLANCE = "clawlance";

    /** @return the damage a DMZ ki weapon of this type would deal for this player, or 0 when unavailable. */
    public static float damageFor(ServerPlayer player, String weaponType)
    {
        if (player == null || !ModList.get().isLoaded("dragonminez"))
            return 0.0f;
        return KiWeaponStatsImpl.damageFor(player, weaponType);
    }
}
