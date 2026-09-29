package net.shurui.shuruisutilities.permissions.core;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Spreads client command-tree resends (the {@code Commands.sendCommands} rebuild that our
 * {@link net.shurui.shuruisutilities.core.mixin.command.MixinCommands} turns into an SU-permission walk of every
 * visible node) over several server ticks instead of firing them for the whole playerbase in one tick.
 *
 * <p>Why this exists: every rebuild runs one uncached {@code checkUserPermission} per visible node (a few hundred
 * to a few thousand), which is roughly 10-100 ms per player. Doing that for every online player inside a single
 * tick, which is what the permission autosave and the shard permission apply used to do, is a 0.5-5 s stall at a
 * busy player count and shows up in the log as "Can't keep up". Nothing about WHICH tree a player receives
 * changes here: the same rebuild is produced, it is simply produced a few players at a time.
 *
 * <p>The queue is a {@link LinkedHashSet} of player UUIDs, so a player asked for twice before being drained is
 * still only rebuilt once, and the drain resolves the {@link ServerPlayer} at drain time and quietly skips anyone
 * who has since logged out. It is drained at most {@link #MAX_PER_TICK} players per tick, so a fifty player
 * broadcast settles inside about a second rather than freezing one tick.
 *
 * <p>Registered on the Forge bus through {@code @Mod.EventBusSubscriber} under the merged mod id, exactly like the
 * other suite tick handlers, so no separate registration wiring is needed.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class CommandTreeResendQueue
{
    private CommandTreeResendQueue() {}

    /** Player UUIDs whose command tree needs rebuilding. Insertion-ordered and deduplicated. */
    private static final LinkedHashSet<UUID> QUEUE = new LinkedHashSet<>();

    /**
     * How many players are rebuilt per server tick. Three keeps the per-tick cost of the walk (worst case a few
     * hundred milliseconds of node checks total, far less with the per-build memo in MixinCommands) well inside a
     * tick, while still draining a large broadcast within roughly a second.
     */
    private static final int MAX_PER_TICK = 3;

    /** Queue one player for a rebuild. Thread-safe: permission events fire on whichever thread made the change. */
    public static void enqueue(UUID id)
    {
        if (id == null)
            return;
        synchronized (QUEUE)
        {
            QUEUE.add(id);
        }
    }

    /** Queue one player for a rebuild. */
    public static void enqueue(ServerPlayer player)
    {
        if (player != null)
            enqueue(player.getUUID());
    }

    /**
     * Queue every player currently online. Used for changes that can affect anyone (a group or zone edit, or the
     * permission autosave, which cannot tell what changed). Still spread over ticks by the drain, so this never
     * costs a single-tick stall the way the old for-all loop did. A no-op before the server exists.
     */
    public static void enqueueAll()
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        synchronized (QUEUE)
        {
            for (ServerPlayer p : server.getPlayerList().getPlayers())
                QUEUE.add(p.getUUID());
        }
    }

    /** Drop a player from the queue, e.g. on logout, so the drain does not bother resolving a gone UUID. */
    public static void remove(UUID id)
    {
        if (id == null)
            return;
        synchronized (QUEUE)
        {
            QUEUE.remove(id);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        drain();
    }

    private static void drain()
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        // Rebuild one player at a time until either MAX_PER_TICK are done or the tick's TIME budget is spent. A
        // count alone does not bound the cost: one rebuild is a few ms with a small permission tree and far more
        // with a big one, so three could still be hundreds of ms. The first player always goes, so the queue
        // always makes progress. Each player is taken under the lock and rebuilt after releasing it, so the
        // expensive walk never stalls the permission-event threads that call enqueue.
        long deadline = System.nanoTime() + BUDGET_NANOS;
        for (int sent = 0; sent < MAX_PER_TICK; sent++)
        {
            ServerPlayer player = null;
            synchronized (QUEUE)
            {
                if (QUEUE.isEmpty())
                    return;
                if (server == null)
                {
                    QUEUE.clear();
                    return;
                }
                Iterator<UUID> it = QUEUE.iterator();
                while (it.hasNext() && player == null)
                {
                    UUID id = it.next();
                    it.remove();
                    player = server.getPlayerList().getPlayer(id); // null: logged out before we got to them
                }
            }
            if (player == null)
                return;
            server.getCommands().sendCommands(player);
            if (System.nanoTime() > deadline)
                return;
        }
    }

    /** Server-thread time one tick may spend on rebuilds before the rest waits for the next tick. */
    private static final long BUDGET_NANOS = 10_000_000L;
}
