package net.shurui.shuruisutilities.clone;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Owner-keyed registry of live {@link MiniCloneEntity} instances, so a recast can discard a caster's existing clones
 * before summoning new ones (recasting replaces rather than accumulates). Modelled on {@code SaibamanPetRegistry}: a
 * Forge-bus subscriber that tracks a clone's UUID from the moment it joins a level and forgets it only on a PERMANENT
 * removal (killed, discarded, or expired), never on a chunk unload.
 *
 * <p>In-memory and per server, cleared on stop. It is never persisted (plain Java types only), which matches the
 * storage discipline; clones re-register through {@link #onJoin} as their chunks load after a restart.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MiniCloneRegistry
{
    private MiniCloneRegistry() {}

    // ownerUUID -> that owner's live clone UUIDs. Server-side, in-memory, per server.
    private static final Map<UUID, Set<UUID>> OWNED = new HashMap<>();

    public static synchronized void track(UUID owner, UUID clone)
    {
        if (owner == null || clone == null)
        {
            return;
        }
        OWNED.computeIfAbsent(owner, k -> new HashSet<>()).add(clone);
    }

    /** Forget a clone. Called only for a PERMANENT removal, never a chunk unload. */
    public static synchronized void untrack(UUID clone)
    {
        if (clone == null)
        {
            return;
        }
        Iterator<Map.Entry<UUID, Set<UUID>>> it = OWNED.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<UUID, Set<UUID>> entry = it.next();
            if (entry.getValue().remove(clone) && entry.getValue().isEmpty())
            {
                it.remove();
            }
        }
    }

    /**
     * Discard every live clone owned by {@code owner}, wherever it is loaded, and drop its tracking. Called before a
     * recast so the caster never accumulates more clones than the current cast summons. A clone in an unloaded chunk
     * cannot be discarded here; it is left tracked and enforces its own 90 second expiry the moment it ticks again.
     */
    public static synchronized void discardAll(MinecraftServer server, UUID owner)
    {
        if (server == null || owner == null)
        {
            return;
        }
        for (ServerLevel level : server.getAllLevels())
        {
            for (Entity e : level.getAllEntities())
            {
                if (e instanceof MiniCloneEntity clone && owner.equals(clone.getOwnerUUID()))
                {
                    clone.discard();
                }
            }
        }
        OWNED.remove(owner);
    }

    /**
     * Track a clone as it joins the level. LOWEST priority and ignoring cancelled events, so a join cancelled by a
     * content gate is never tracked (that entity is never added, so its {@code remove} never fires and tracking it
     * would leak an entry that could not be freed).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent event)
    {
        if (event.getLevel().isClientSide)
        {
            return;
        }
        if (event.getEntity() instanceof MiniCloneEntity clone)
        {
            track(clone.getOwnerUUID(), clone.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event)
    {
        synchronized (MiniCloneRegistry.class)
        {
            OWNED.clear();
        }
    }
}
