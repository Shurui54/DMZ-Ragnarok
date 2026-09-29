package net.shurui.dev.shuruis_dmz_dungeons.util;

import com.mojang.logging.LogUtils;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import org.slf4j.Logger;

/**
 * Shared guard for cancelling a {@link MobSpawnEvent.FinalizeSpawn}.
 *
 * Forge's {@code Mob#setSpawnCancelled} throws {@code UnsupportedOperationException} when the entity is already added
 * to the world, which happens on FinalizeSpawn firings that are not real spawns (a mob conversion). Cancelling there
 * crashes the server thread, so cancel only when legal.
 *
 * Mirrors shuruisutilities' helper of the same name; the two subprojects cannot share code, so it is duplicated.
 */
public final class SpawnCancelUtil
{
    private SpawnCancelUtil() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    // one-shot observability: conversions are common, so never log per occurrence.
    private static boolean warnedLate = false;

    /** Cancel only when the entity is not yet added to the world (the one context Forge permits). True if applied. */
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
            // Defensive: isAddedToWorld should already cover this. If Forge moves the throw, degrade to letting the
            // mob spawn instead of crashing the server thread.
            if (!warnedLate)
            {
                warnedLate = true;
                LOGGER.debug("Skipped a late Mob#setSpawnCancelled; letting the spawn proceed.");
            }
            return false;
        }
    }
}
