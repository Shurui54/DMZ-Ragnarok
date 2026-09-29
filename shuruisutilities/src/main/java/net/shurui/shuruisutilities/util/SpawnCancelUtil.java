package net.shurui.shuruisutilities.util;

import com.mojang.logging.LogUtils;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import org.slf4j.Logger;

/**
 * Shared guard for cancelling a {@link MobSpawnEvent.FinalizeSpawn}.
 *
 * Forge's {@code Mob#setSpawnCancelled} throws {@code UnsupportedOperationException} when the entity is
 * already added to the world ("Late invocations of Mob#setSpawnCancelled are not permitted."). That happens
 * on FinalizeSpawn firings that are not real spawns, most notably a villager converting into a zombie
 * villager after being killed by a zombie. Calling setSpawnCancelled there killed the live server thread.
 *
 * The guilds, protection and regions handlers all want the same thing: cancel a hostile-mob spawn inside a
 * claim / region only when cancelling is legal. When the invocation is late we do nothing and let the
 * conversion proceed, because deleting the entity would permanently destroy a player's villager.
 */
public final class SpawnCancelUtil
{
    private SpawnCancelUtil() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    // one-shot observability: villager conversions are common, so never log per occurrence.
    private static boolean warnedLate = false;

    /**
     * Cancel the spawn only when the entity is not yet added to the world, which is the one context Forge
     * permits. Returns true if the cancellation was applied.
     */
    public static boolean cancelIfLegal(MobSpawnEvent.FinalizeSpawn event)
    {
        if (event.getEntity().isAddedToWorld())
            return false;
        try
        {
            event.setSpawnCancelled(true);
            return true;
        }
        catch (UnsupportedOperationException late)
        {
            // Defensive: the isAddedToWorld check should already have covered this. If a future Forge change
            // moves the throw condition, degrade to "the mob spawns" instead of crashing the server thread.
            if (!warnedLate)
            {
                warnedLate = true;
                LOGGER.debug("Skipped a late Mob#setSpawnCancelled; letting the spawn proceed.");
            }
            return false;
        }
    }
}
