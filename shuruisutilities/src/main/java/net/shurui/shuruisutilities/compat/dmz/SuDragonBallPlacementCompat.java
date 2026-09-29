package net.shurui.shuruisutilities.compat.dmz;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;

/**
 * Guard entry point for the Super dragon ball placement-spacing rule. Holds no DMZ imports of its own: it only
 * checks that DMZ is present before registering {@link SuSuperBallPlacementHandler}, which is the class that reaches
 * DMZ types (via {@link DragonBallSets}). Follows the optional-dependency pattern, mirroring {@link SuWishCompat}.
 */
public final class SuDragonBallPlacementCompat
{
    private SuDragonBallPlacementCompat()
    {
    }

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
        MinecraftForge.EVENT_BUS.register(new SuSuperBallPlacementHandler());
        registered = true;
    }
}
