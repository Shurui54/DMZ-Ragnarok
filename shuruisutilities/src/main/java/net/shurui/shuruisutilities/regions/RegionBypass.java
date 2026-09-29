package net.shurui.shuruisutilities.regions;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks which players currently have region bypass toggled on ({@code /serverclaim bypass}). Session-only
 * (cleared on logout / restart). Requires the {@link ModuleRegions#PERM_BYPASS} permission to toggle; while
 * on, {@link RegionEventHandler} skips build / pvp / entry restrictions for that player.
 */
public final class RegionBypass
{
    private RegionBypass() {}

    private static final Set<UUID> bypassing = new HashSet<>();

    public static boolean isBypassing(UUID uuid)
    {
        return bypassing.contains(uuid);
    }

    /** Flip the player's bypass state and return the new value. */
    public static boolean toggle(UUID uuid)
    {
        if (bypassing.remove(uuid))
            return false;
        bypassing.add(uuid);
        return true;
    }

    public static void clear(UUID uuid)
    {
        bypassing.remove(uuid);
    }
}
