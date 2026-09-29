package net.shurui.shuruisutilities.character;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Records where each player arrives, and says so when the chunk they arrived in never turns up.
 *
 * <h2>What this is for</h2>
 * Players get stuck on the loading screen at join, and moving them to somebody else fixes it. That symptom already
 * says a great deal: the client waits for the chunk it is STANDING IN before it leaves that screen, so a teleport
 * curing it means their player data loaded, their connection is fine, and the fault is the one chunk they happened
 * to log out in. What has been missing every time is WHICH chunk, because by the time anyone reports it they have
 * been moved and the evidence is gone.
 *
 * <p>So the position is written down on the way in, unconditionally, and a watch is left running. If the chunk turns
 * up promptly, which is the normal case, that is the end of it. If it does not, the warning names the coordinates,
 * the chunk and the region file on disk, which is enough to go and look at the file, or to run the area through
 * {@code /purgechunks}.
 *
 * <h2>A watch only means anything while the player is still standing in it</h2>
 * The question here is "did the chunk this player is WAITING ON ever load", and they stop waiting on it the moment
 * something moves them. On a shard that is routine: {@link net.shurui.shuruisutilities.shard.ShardSync} places an
 * arriving player at their carried position a tick or two after login, and the eviction sweep in
 * {@code ShardDimensions} can move them too. The chunk they were snapshotted in is then unloaded, exactly as it
 * should be, because nobody is there to hold a ticket on it.
 *
 * <p>Polling the frozen snapshot regardless is how this reported a 180 second stuck login against a dimension the
 * player had already been moved out of and was playing happily outside of. So each poll checks that they are still
 * in the watched chunk first, and drops the watch when they are not. A moved player is not a stuck player.
 *
 * <h2>Why it cannot cause what it is watching for</h2>
 * {@code hasChunk} is a lookup of the chunk holder map and nothing else: it neither loads nor generates. Asking with
 * anything that could load would be the diagnostic causing the stall it exists to observe, on the server thread, at
 * exactly the wrong moment.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class JoinChunkWatch
{
    private JoinChunkWatch() {}

    /** How long a chunk may take to arrive before it is worth complaining about. Generous: a cold region file, a
     *  slow disk and a busy server can all cost a few seconds legitimately. */
    private static final long SLOW_MILLIS = 10_000L;

    /** When to stop watching. Past this the player has almost certainly given up and closed the game. */
    private static final long GIVE_UP_MILLIS = 180_000L;

    /** Watched once a second. The thing being measured takes seconds, so a tighter poll would only cost. */
    private static final int POLL_TICKS = 20;

    private static final class Arrival
    {
        final String name;
        final ServerLevel level;
        final double x;
        final double y;
        final double z;
        final ChunkPos chunk;
        final long startedAt;
        boolean warned;

        Arrival(ServerPlayer player, ServerLevel level)
        {
            this.name = player.getGameProfile().getName();
            this.level = level;
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.chunk = new ChunkPos(player.blockPosition());
            this.startedAt = System.currentTimeMillis();
        }

        /** The file this chunk actually lives in, so it can be found on disk without arithmetic. */
        String regionFile()
        {
            return "r." + (chunk.x >> 5) + "." + (chunk.z >> 5) + ".mca";
        }

        String where()
        {
            return String.format("%.1f, %.1f, %.1f in %s (chunk %d, %d in %s)",
                    x, y, z, level.dimension().location(), chunk.x, chunk.z, regionFile());
        }
    }

    // Insertion ordered so the log reads in join order when several people are stuck at once.
    private static final Map<UUID, Arrival> WATCHING = new LinkedHashMap<>();

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        if (!(player.level() instanceof ServerLevel level))
            return;
        try
        {
            Arrival arrival = new Arrival(player, level);
            WATCHING.put(player.getUUID(), arrival);
            // One line per join, always. This is the line that is missing when somebody reports being stuck: it is
            // written before anything can go wrong and it survives them being moved afterwards.
            LoggingHandler.sulog.info("[join] {} arriving at {}; chunk loaded already: {}",
                    arrival.name, arrival.where(), chunkPresent(arrival));
        }
        catch (Throwable t)
        {
            // A diagnostic must never be the reason a login fails.
            LoggingHandler.sulog.debug("[join] could not record arrival: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        Arrival arrival = WATCHING.remove(event.getEntity().getUUID());
        if (arrival == null)
            return;
        // Leaving before the chunk arrived is the shape of the bug: somebody waited, nothing happened, they quit.
        // Said plainly here because this is the one moment we can be sure they never got in.
        if (!chunkPresent(arrival))
        {
            LoggingHandler.sulog.warn(
                    "[join] {} disconnected after {}s with their arrival chunk still not loaded: {}",
                    arrival.name, elapsedSeconds(arrival), arrival.where());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || WATCHING.isEmpty())
            return;
        MinecraftServer server = event.getServer();
        if (server == null || server.getTickCount() % POLL_TICKS != 0)
            return;
        for (Iterator<Map.Entry<UUID, Arrival>> it = WATCHING.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<UUID, Arrival> entry = it.next();
            Arrival arrival = entry.getValue();
            try
            {
                // Checked BEFORE the chunk, because a player who has been moved makes the chunk question moot and
                // the chunk they left is expected to unload. Asking in the other order is what produced a stuck
                // login warning about somebody who was already up and playing somewhere else.
                if (hasMovedOn(server, entry.getKey(), arrival))
                {
                    if (arrival.warned)
                    {
                        // Only when we already complained, so the earlier warning is not left as the last word on
                        // a join that turned out fine.
                        LoggingHandler.sulog.warn("[join] {}: moved out of the chunk they arrived in after {}s, so "
                                        + "the wait ended rather than the chunk loading. Not stuck.",
                                arrival.name, elapsedSeconds(arrival));
                    }
                    it.remove();
                    continue;
                }
                if (chunkPresent(arrival))
                {
                    // Only worth a line if it was slow enough to have been noticed. A join that worked is not news.
                    if (arrival.warned)
                    {
                        LoggingHandler.sulog.warn("[join] {}: arrival chunk finally loaded after {}s.",
                                arrival.name, elapsedSeconds(arrival));
                    }
                    it.remove();
                    continue;
                }
                long elapsed = System.currentTimeMillis() - arrival.startedAt;
                if (elapsed > GIVE_UP_MILLIS)
                {
                    LoggingHandler.sulog.warn("[join] {}: giving up watching; arrival chunk never loaded in {}s: {}",
                            arrival.name, elapsedSeconds(arrival), arrival.where());
                    it.remove();
                    continue;
                }
                if (!arrival.warned && elapsed > SLOW_MILLIS)
                {
                    arrival.warned = true;
                    LoggingHandler.sulog.warn(
                            "[join] {} has been waiting {}s for their arrival chunk. This is the stuck-on-loading "
                                    + "case: {}. Teleporting them elsewhere will let them in; the chunk itself is "
                                    + "what to look at.",
                            arrival.name, elapsedSeconds(arrival), arrival.where());
                }
            }
            catch (Throwable t)
            {
                it.remove();
            }
        }
    }

    /** Dropped wholesale on shutdown: a watch is only meaningful for the server it was opened on. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event)
    {
        WATCHING.clear();
    }

    /**
     * Has the player stopped waiting on this chunk, either by being moved out of it or by leaving?
     *
     * <p>A different dimension or a different chunk both count: the client only holds the loading screen for the
     * chunk it is standing in, so once they are anywhere else the watch is answering a question nobody asked. Gone
     * from the player list counts too, as a safety net; {@link #onLogout} normally takes that case first and says
     * something more useful about it.
     */
    private static boolean hasMovedOn(MinecraftServer server, UUID id, Arrival arrival)
    {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null)
            return true;
        if (player.level() != arrival.level)
            return true;
        return !new ChunkPos(player.blockPosition()).equals(arrival.chunk);
    }

    /**
     * Is the arrival chunk loaded RIGHT NOW?
     *
     * <p>{@code hasChunk} reads the chunk holder map. It does not load and it does not generate, which is the whole
     * reason it is the call used here.
     */
    private static boolean chunkPresent(Arrival arrival)
    {
        return arrival.level.getChunkSource().hasChunk(arrival.chunk.x, arrival.chunk.z);
    }

    private static long elapsedSeconds(Arrival arrival)
    {
        return (System.currentTimeMillis() - arrival.startedAt) / 1000L;
    }
}
