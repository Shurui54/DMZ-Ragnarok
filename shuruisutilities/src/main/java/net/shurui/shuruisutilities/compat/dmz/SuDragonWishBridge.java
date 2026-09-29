package net.shurui.shuruisutilities.compat.dmz;

import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Forge-bus handler that keeps the wishes for Shurui's three dragons alive now that the external
 * {@code dragonballs/} pack (which used to carry their {@code wishes.json}) is gone.
 *
 * <p>WHY this event, at this priority. DMZ's {@code DragonWishRegistry} rebuilds the server wish map from the disk
 * pack on every datapack reload and, with the pack deleted, leaves our three dragons with empty wish lists. That
 * same class also listens for Forge's {@code OnDatapackSyncEvent} (fired right after every reload and on each
 * player login) to push the wishes to clients. By registering here at {@link EventPriority#HIGH} we run just
 * BEFORE DMZ's default-priority handler on that same event: we seed our wishes into the freshly rebuilt server map
 * first (seed-if-absent, so an operator's saved edit is never clobbered, and after re-overlaying the per-world wish
 * files on a reload), then DMZ's handler reads that map and syncs it out. This fixes both sides at once, the
 * server-side wish grant AND the client wish screen, and it is self-healing across reloads. The exact seeding
 * contract lives in {@link SuDragonBallDefinitions#reapplyWishes()}. HIGH priority is load-bearing here and must
 * not change: it is what guarantees the map is correct before DMZ's same-event sync reads it.
 *
 * <p>SAFETY. The whole body is wrapped in try/catch(Throwable) with a one-shot latched log, per the standing rule
 * for touching DMZ internals: a DMZ API shift degrades to "our dragons have no wishes" instead of breaking the
 * reload or the sync. This class is only ever registered on the bus when DMZ is present (see {@link SuWishCompat}),
 * so {@link SuDragonBallDefinitions} is never classloaded when DMZ is absent.
 */
public final class SuDragonWishBridge
{
    // one-shot latch so a DMZ API mismatch logs at most once instead of once per reload / login.
    private boolean loggedFailure = false;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onDatapackSync(OnDatapackSyncEvent event)
    {
        try
        {
            SuDragonBallDefinitions.reapplyWishes();
        }
        catch (Throwable t)
        {
            if (!loggedFailure)
            {
                loggedFailure = true;
                LoggingHandler.sulog.warn(
                        "[dragonballs] Could not re-apply wishes for Shurui's dragons; their wish menus may be "
                                + "empty. DMZ's own wishes are unaffected. Cause: {}",
                        t.toString());
            }
        }
    }
}
