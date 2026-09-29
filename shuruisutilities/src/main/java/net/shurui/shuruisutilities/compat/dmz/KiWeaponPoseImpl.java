package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.combat.logic.weapon.WeaponRegistry;
import com.dragonminez.common.combat.weapon.WeaponAttributes;

import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/** The only class naming DMZ's weapon registry. */
final class KiWeaponPoseImpl
{
    private KiWeaponPoseImpl() {}

    /**
     * Register one of our items with DMZ's weapon registry so DMZ picks its combat pose itself.
     *
     * <p>{@code CombatAnimationResolver.resolvePlayerPose} reads
     * {@code WeaponRegistry.getAttributes(mainHandItem).pose()}, so an entry here is all it takes - no packet, no
     * client-side animation call, and the pose follows the same path DMZ's own weapons use.
     */
    static boolean register(ResourceLocation itemId, String pose, boolean twoHanded, double reach, String category)
    {
        try
        {
            WeaponAttributes attributes = new WeaponAttributes(
                    reach, pose, pose, twoHanded, category, new WeaponAttributes.Attack[0]);
            WeaponRegistry.register(itemId, attributes);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[kiweapon] could not register pose for {}: {}", itemId, t.toString());
            return false;
        }
    }
}
