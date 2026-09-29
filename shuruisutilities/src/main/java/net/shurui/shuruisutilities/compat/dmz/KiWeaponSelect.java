package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Guard for DMZ's ki weapon gate.
 *
 * <p>"Lock them behind the same gate as other ki weapons" is exactly this skill: DMZ's own
 * {@code PlayerAttackHelper.isKiWeaponActive} requires {@code kimanipulation} to be active before any ki weapon
 * renders at all, so checking the same skill means our weapons are gated identically rather than by a rule of ours.
 */
public final class KiWeaponSelect
{
    private KiWeaponSelect() {}

    public static boolean hasKiWeaponSkill(ServerPlayer player)
    {
        return player != null && ModList.get().isLoaded("dragonminez")
                && KiWeaponSelectImpl.hasKiWeaponSkill(player);
    }
}
