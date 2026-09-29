package net.shurui.shuruisutilities.compat.dmz;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;

/**
 * Guard entry point for re-applying Shurui's dragon wishes from code. Holds no DMZ imports of its own: it only
 * checks that DMZ is present before registering {@link SuDragonWishBridge}, which is the class that references DMZ
 * types (via {@link SuDragonBallDefinitions}). Follows the optional-dependency pattern.
 *
 * <p>Separate from {@link WishTrackingCompat} on purpose: that class is the corrupted-dragon-ball feature (it
 * toggles DMZ's ball generation flag), whereas this one only keeps our three hardcoded dragons' wish lists alive.
 */
public final class SuWishCompat
{
    private SuWishCompat() {}

    private static boolean registered;

    public static void init()
    {
        if (registered)
        {
            return;
        }
        if (!ModList.get().isLoaded("dragonminez"))
        {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new SuDragonWishBridge());
        // Registered here too, behind the same DMZ guard, because it names DMZ's wish sync packet directly.
        MinecraftForge.EVENT_BUS.register(new SuWishVisibility());
        registered = true;
    }
}
