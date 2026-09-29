package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

/**
 * Guard for registering our summoned weapons with DMZ's weapon registry, which is what gives them a DMZ combat pose.
 *
 * <p>The staff swings two-handed like a staff. The pose is DMZ's own clip from {@code combat.animation.json}, so the
 * weapon animates exactly as DMZ's do rather than through anything of ours.
 */
public final class KiWeaponPose
{
    private KiWeaponPose() {}

    /** DMZ's two-handed staff pose, used by the Angel's staff. */
    public static final String POSE_STAFF = "combat.pose_two_handed_staff";

    public static boolean register(ResourceLocation itemId, String pose, boolean twoHanded, double reach,
                                   String category)
    {
        if (itemId == null || pose == null || !ModList.get().isLoaded("dragonminez"))
            return false;
        return KiWeaponPoseImpl.register(itemId, pose, twoHanded, reach, category);
    }
}
