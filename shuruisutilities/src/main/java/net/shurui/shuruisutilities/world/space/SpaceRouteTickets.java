package net.shurui.shuruisutilities.world.space;

import java.util.HashSet;
import java.util.UUID;

import net.minecraftforge.common.world.ForgeChunkManager;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The Forge forced-chunk loading-validation callback for the autopilot route loader ({@code SpaceRouteChunks}), split
 * out of that class so it is REGISTERED FROM CORE. Registration and ticket validation must run whether or not the
 * space feature is its own module, so it lives in core; the per-tick force/release window (which only runs while a pod
 * is actually flying) stays with {@code SpaceRouteChunks} in the space package.
 *
 * <p>The ticket owner mod id is resolved from the active SU container ({@code dmz_ragnarok} since the suite merge), the
 * SAME id {@code SpaceRouteChunks} passes to {@link ForgeChunkManager#forceChunk}, so Forge validates and cleans up the
 * exact tickets the route loader created. Handing Forge the historical "shuruisutilities" id (now only a registry
 * namespace, no longer a loaded mod) would make it drop the callback and every ticket.
 */
public final class SpaceRouteTickets
{
    private SpaceRouteTickets()
    {
    }

    // Owner id for every forced-chunk ticket the route driver creates. ForgeChunkManager validates this against the
    // set of LOADED mod containers, so it must be the container that actually ships SU: since the suite merge that is
    // the single dmz_ragnarok container. Resolved from the active container SU cached at construction, so it tracks
    // the real container id and survives a future rename. Kept identical to SpaceRouteChunks' own owner id.
    static String chunkOwnerModId()
    {
        return ShuruisUtilities.MOD_CONTAINER.getModId();
    }

    // Drop every persisted route ticket on world load. An autopilot resumes on the next drive tick and re-forces a
    // fresh window, so a ticket that outlived a restart is always stale: clearing them here is the backstop that stops
    // an abnormal shutdown mid-trip from pinning chunks forever. Called once from SU common setup.
    public static void registerLoadingCallback()
    {
        ForgeChunkManager.setForcedChunkLoadingCallback(chunkOwnerModId(), (level, ticketHelper) ->
        {
            try
            {
                // copy the key set first: removeAllTickets mutates the backing map.
                for (UUID owner : new HashSet<>(ticketHelper.getEntityTickets().keySet()))
                {
                    ticketHelper.removeAllTickets(owner);
                }
            }
            catch (Throwable t)
            {
                LoggingHandler.sulog.warn("[SpacePod] route chunk ticket cleanup failed on load: " + t);
            }
        });
    }
}
