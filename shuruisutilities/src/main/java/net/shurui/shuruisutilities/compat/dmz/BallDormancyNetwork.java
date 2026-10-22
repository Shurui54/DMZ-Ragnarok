package net.shurui.shuruisutilities.compat.dmz;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.shard.ShardStateSync;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Carries dragon ball dormancy between shards, so a set wished on one server is stone (and shows the same wake timer
 * on the radar) on every server, and wakes everywhere together.
 *
 * <p>Dormancy itself stays where it was: {@link BallDormancyStorage} on each server, with the shard that saw the wish
 * keeping its own game-time (or wall-clock, for Super) record and announcing the wake. What travels, through
 * {@link ShardStateSync} under {@link #SYNC_KEY}, is one entry per set: a change stamp and the wake time on the shared
 * database clock (0 = awake). A server that receives a newer entry records the set as dormant until that time (a
 * {@code foreign} wall-clock record, which wakes silently) or wakes it. The merge keeps the newer stamp per set, so
 * entries from different shards never overwrite each other's newer news. Off the network nothing travels and this is
 * inert, exactly as before.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class BallDormancyNetwork
{
    private BallDormancyNetwork() {}

    /** The ShardStateSync key. Persisted name: never rename. */
    public static final String SYNC_KEY = "dragonballs:dormancy";

    /** setId -> {stamp, wake time on the database clock (0 = awake)}. Server thread only. */
    private static final Map<String, long[]> ENTRIES = new HashMap<>();
    private static long signal;
    private static boolean registered;

    private static long dbNow()
    {
        return System.currentTimeMillis() + ShardStateSync.dbClockOffsetMillis();
    }

    /**
     * Record a dormancy change made on THIS server (a wish, an admin /awaken or /unawaken, a scheduled wake) so the
     * other shards follow. {@code remainingMillis} is how long until the set wakes, or a negative value when it is
     * awake now.
     */
    public static void publish(String setId, long remainingMillis)
    {
        if (setId == null || setId.isEmpty())
            return;
        long now = dbNow();
        long[] prev = ENTRIES.get(setId);
        long stamp = Math.max(now, prev == null ? 0L : prev[0] + 1L);
        ENTRIES.put(setId, new long[] { stamp, remainingMillis < 0L ? 0L : now + remainingMillis });
        signal++;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        if (registered)
            return;
        registered = true;
        try
        {
            ShardStateSync.register(SYNC_KEY, BallDormancyNetwork::read, BallDormancyNetwork::write, () -> signal);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dormancy] could not register the network carry: {}", t.toString());
        }
    }

    private static CompoundTag read()
    {
        if (ENTRIES.isEmpty())
            return null;
        CompoundTag tag = new CompoundTag();
        ENTRIES.forEach((set, e) -> {
            CompoundTag t = new CompoundTag();
            t.putLong("stamp", e[0]);
            t.putLong("until", e[1]);
            tag.put(set, t);
        });
        return tag;
    }

    private static void write(CompoundTag tag)
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || tag == null)
            return;
        boolean changed = false;
        BallDormancyStorage storage = BallDormancyStorage.get(server);
        long offset = ShardStateSync.dbClockOffsetMillis();
        for (String set : tag.getAllKeys())
        {
            CompoundTag t = tag.getCompound(set);
            long stamp = t.getLong("stamp");
            long until = t.getLong("until");
            long[] mine = ENTRIES.get(set);
            if (mine != null && mine[0] >= stamp)
                continue;
            ENTRIES.put(set, new long[] { stamp, until });
            changed = true;
            if (until > 0L)
            {
                // another shard turned this set to stone: dormant here until the same moment (local wall clock)
                if (!storage.isDormant(set))
                {
                    storage.putForeign(set, until - offset);
                    LoggingHandler.sulog.info("[dormancy] {} set is dormant network-wide (wished on another server)",
                            set);
                }
            }
            else if (storage.isDormant(set))
            {
                storage.clear(set);
                LoggingHandler.sulog.info("[dormancy] {} set woke on another server; awake here too", set);
            }
        }
        if (changed)
        {
            signal++;
            BallDormancySync.broadcast(server);
            BallDormancy.refreshRadarPublic(server);
        }
    }
}
