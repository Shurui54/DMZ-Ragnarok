package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

/**
 * Whether a player currently has a DragonMineZ ki weapon DRAWN (out in the hand), as DMZ itself decides it.
 *
 * <p>Guard half of the usual pair; {@link KiWeaponActiveImpl} is the only class naming a DMZ type
 * (the optional-dependency pattern). This is the signal a hand-style cosmetic accessory is gated on: the weapon props are
 * skins over the ki weapon, so they show exactly when the ki weapon shows.
 *
 * <h2>Which DMZ call, and why it is the right one</h2>
 * It calls {@code com.dragonminez.common.combat.logic.player.PlayerAttackHelper.isKiWeaponActive(Player)}, read from
 * the 2.1.3 bytecode. That method is DMZ's own answer to "is a ki weapon out": it returns true only when the main
 * hand is EMPTY, the {@code kimanipulation} skill is active, the selected ki weapon type is non-null and not
 * {@code "none"}, and the combat config actually defines that type. It is the exact test DMZ's own render and combat
 * paths use, so a hand accessory follows the same gate as DMZ's own weapon rather than a rule of ours. Do not confuse
 * it with {@code Status.getKiWeaponType()} (used by {@link DmzKiWeaponType}), which is only the SELECTED type and is
 * always non-null after validation, so it says which weapon, never whether one is drawn.
 *
 * <p>Client safe: {@code PlayerAttackHelper.isKiWeaponActive} reads the stats capability and the combat config,
 * both present on the client. Returns false when there is no DMZ or anything throws, so a hand accessory simply is
 * not drawn rather than crashing the render thread.
 */
public final class KiWeaponActive
{
    private KiWeaponActive() {}

    public static boolean isActive(Player player)
    {
        return player != null && ModList.get().isLoaded("dragonminez")
                && KiWeaponActiveImpl.isActive(player);
    }
}
