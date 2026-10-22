package net.shurui.shuruisutilities.compat.dmz;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.common.dragonball.DragonDefinition;
import com.dragonminez.common.init.entities.dragon.DragonWishEntity;
import com.dragonminez.server.events.DragonBallsHandler;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.shard.ShardConfig;
import net.shurui.shuruisutilities.shard.ShardDimensions;
import net.shurui.shuruisutilities.shard.ShardStateSync;
import net.shurui.shuruisutilities.shard.ShardSync;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Makes sure a dragon ball set that was spent on a wish always comes back, in its OWN home dimension, on whichever
 * server hosts that dimension.
 *
 * <h2>The bug</h2>
 *
 * <p>DragonMineZ re-scatters a spent set from {@code DragonWishEntity.onDespawn}, and only there:
 * {@code if (generateDragonBalls) scatterDragonBalls(<the DRAGON's level>, setId)}. Two things turn that into "the
 * balls never respawn":
 * <ul>
 *   <li>{@code scatterDragonBalls} returns at once on a level the set does not scatter in. Every set except Black Star
 *       summons its dragon wherever it is gathered ({@code MixinDmzDragonBallBlock}: the dimension guard is relaxed
 *       and the dragon lookup falls back to the set's own dragon), so an Earth set wished on Namek, in space, on the
 *       SMP's own world or on any shard that does not host the overworld was never scattered again. That scatter
 *       call (from the dragon's despawn and from DMZ 2.2's abandoned-summon expiry) is now turned into a debt here
 *       by {@code MixinDmzDragonBallScatter}.</li>
 *   <li>The corrupted cycle ({@link WishTrackingBridge}) turns {@code generateDragonBalls} OFF while it is armed, and
 *       DMZ re-reads that flag inside {@code onDespawn}. Every set wished while armed (the arming wish itself
 *       included) skipped its re-scatter, and switching the flag back on at the end of the cycle re-scatters
 *       nothing, so those sets were gone until an admin ran {@code /scatter}.</li>
 * </ul>
 * On DragonMineZ 2.1.3 a dragon removed with {@code /kill} (reason KILLED) never reached {@code onDespawn} at all,
 * which lost the set the same way; 2.2 runs {@code onDespawn} for it too.
 *
 * <h2>The fix</h2>
 *
 * <p>When a wish dragon is removed as DISCARDED (its normal despawn) or KILLED ({@code MixinDmzWishDragonRemove}),
 * this checks whether DMZ's own re-scatter is about to run (ball generation on). When it is, nothing happens here: at
 * home DMZ scatters, away from home the scatter mixin records the debt. When it is not, the set is recorded as OWED
 * a re-scatter. A periodic pass then pays each owed set by
 * calling DMZ's own {@code scatterDragonBalls} on the set's home level, so placement, counts, the border clamp and
 * every other existing rule apply exactly as for a normal wish. A debt waits while {@code generateDragonBalls} is
 * off (the corrupted cycle or an operator's choice) and is paid as soon as it is back on.
 *
 * <h2>Across shards</h2>
 *
 * <p>The debt is a per set request stamp (the shared database clock) that travels through {@link ShardStateSync}
 * as a max-merge store, so a wish on the SMP reaches the open world shard that hosts the overworld. Exactly one
 * server pays it: the configured owner of the home dimension ({@code dimensionOwners}); with no owner recorded, the
 * lowest server id among this server and its {@code ghostPeers}, when this server hosts the dimension. The twin is
 * then fed by {@code DragonBallAuthority} exactly like a normal scatter. Off the network (keyless, single server)
 * the local server pays everything. The served stamp is kept locally, so a debt is paid once per server.
 *
 * <p>Persisted as the SavedData {@code dmzr_ball_rescatter} in the overworld data folder, written only once a debt
 * has ever existed, so a world that never needs this stays byte-identical.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class BallRescatter
{
    private BallRescatter() {}

    /** The ShardStateSync key. Persisted name: never rename. */
    public static final String SYNC_KEY = "dragonballs:rescatter";

    private static final String DATA_NAME = "dmzr_ball_rescatter";

    private static final int SERVE_INTERVAL_TICKS = 100;

    private static boolean syncRegistered = false;

    /* ---------------------------------------------------------------- detection */

    /**
     * Called (from {@code MixinDmzWishDragonRemove}) as a wish dragon is removed, before DMZ's own
     * {@code onDespawn}. Records a debt only when DMZ's re-scatter will not run. Never throws.
     */
    public static void onDragonRemoved(Entity entity, Entity.RemovalReason reason)
    {
        if (!(entity instanceof DragonWishEntity dragon) || !(entity.level() instanceof ServerLevel level))
            return;
        try
        {
            if (reason != Entity.RemovalReason.DISCARDED && reason != Entity.RemovalReason.KILLED)
                return; // unloaded with its chunk or changed dimension: it is still around, nothing was spent yet
            DragonDefinition def = dragon.getDragonDefinition();
            String setId = def == null ? null : def.getBallSetId();
            if (setId == null || setId.isBlank())
                return;
            DragonBallSetDefinition set = DragonBallDefinitions.getBallSet(setId);
            if (set == null || set.getCopies() <= 0)
                return; // a zero copy set (Black Star, Super, Cerulean) is placed by its own module, never scattered

            // DMZ's own onDespawn re-scatters only with generateDragonBalls on, and DragonMineZ 2.2 runs it for every
            // destroying removal (DISCARDED and KILLED alike; 2.1.3 only on DISCARDED). In the set's home it does the
            // work itself; away from home its scatter call is turned into a debt by MixinDmzDragonBallScatter. Either
            // way that path is taken care of, so only the generation-off case is owed here.
            boolean dmzScatters = reason.shouldDestroy() && generateOn();
            if (dmzScatters)
                return;
            request(level.getServer(), setId, level.dimension().location() + " (" + reason
                    + (generateOn() ? "" : ", ball generation off") + ")");
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[ballrescatter] could not check a removed dragon: {}", t.toString());
        }
    }

    /** Record that {@code setId} is owed one re-scatter. Server thread. */
    public static void request(MinecraftServer server, String setId, String where)
    {
        if (server == null || setId == null)
            return;
        Data data = Data.get(server);
        if (data == null)
            return;
        long stamp = Math.max(System.currentTimeMillis() + ShardStateSync.dbClockOffsetMillis(),
                data.requests.getOrDefault(setId, 0L) + 1L);
        data.requests.put(setId, stamp);
        data.bump();
        LoggingHandler.sulog.info("[ballrescatter] {} set was spent in {} without DMZ re-scattering it; "
                + "a re-scatter in its home dimension is owed (stamp {})", setId, where, stamp);
        serve(server);
    }

    /* ---------------------------------------------------------------- paying the debt */

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null)
            return;
        if (event.getServer().getTickCount() % SERVE_INTERVAL_TICKS != 0)
            return;
        serve(event.getServer());
    }

    /** Pay every owed re-scatter this server is responsible for. Never throws. */
    public static void serve(MinecraftServer server)
    {
        try
        {
            Data data = Data.get(server);
            if (data == null || data.requests.isEmpty() || !generateOn())
                return; // nothing owed, or ball generation is off: keep the debt until it is back on
            for (Map.Entry<String, Long> e : new HashMap<>(data.requests).entrySet())
            {
                String setId = e.getKey();
                long stamp = e.getValue();
                if (stamp <= data.served.getOrDefault(setId, 0L))
                    continue;
                DragonBallSetDefinition set = DragonBallDefinitions.getBallSet(setId);
                if (set == null || set.getCopies() <= 0)
                {
                    data.served.put(setId, stamp);
                    data.setDirty();
                    continue;
                }
                ServerLevel home = servableHome(server, set);
                if (home == null)
                    continue; // another server hosts this set's home: it pays
                DragonBallsHandler.scatterDragonBalls(home, setId);
                data.served.put(setId, stamp);
                data.setDirty();
                LoggingHandler.sulog.info("[ballrescatter] re-scattered the {} set in {} (owed stamp {})", setId,
                        home.dimension().location(), stamp);
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[ballrescatter] serve pass failed: {}", t.toString());
        }
    }

    /** The set's home level this server should scatter into, or null when another server is responsible. */
    private static ServerLevel servableHome(MinecraftServer server, DragonBallSetDefinition set)
    {
        for (ResourceLocation dim : set.getValidDimensions())
        {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dim);
            ServerLevel level = server.getLevel(key);
            if (level != null && responsibleFor(key))
                return level;
        }
        return null;
    }

    private static boolean responsibleFor(ResourceKey<Level> key)
    {
        if (!ShardSync.active())
            return true;
        String self = ShardConfig.selfId();
        String owner = ShardDimensions.owner(key);
        if (owner != null && !owner.isBlank())
            return owner.equalsIgnoreCase(self);
        if (!ShardDimensions.hosts(key))
            return false;
        List<String> peers = ShardConfig.get().ghostPeers;
        if (peers != null)
            for (String peer : peers)
                if (peer != null && !peer.isBlank() && self != null && peer.compareToIgnoreCase(self) < 0)
                    return false; // a twin with a lower id pays
        return true;
    }

    private static boolean generateOn()
    {
        try
        {
            Boolean on = ConfigManager.getServerConfig().getWorldGen().getGenerateDragonBalls();
            return on == null || on;
        }
        catch (Throwable t)
        {
            return true;
        }
    }

    /* ---------------------------------------------------------------- network carry */

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        if (syncRegistered)
            return;
        syncRegistered = true;
        try
        {
            ShardStateSync.register(SYNC_KEY, BallRescatter::readSync, BallRescatter::writeSync,
                    () -> {
                        Data d = Data.get(ServerLifecycleHooks.getCurrentServer());
                        return d == null ? 0L : d.signal;
                    });
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[ballrescatter] could not register the network carry: {}", t.toString());
        }
    }

    private static CompoundTag readSync()
    {
        Data d = Data.get(ServerLifecycleHooks.getCurrentServer());
        if (d == null || d.requests.isEmpty())
            return null;
        CompoundTag tag = new CompoundTag();
        d.requests.forEach(tag::putLong);
        return tag;
    }

    // Max-merge: a request is a stamp that only ever grows, so taking the larger of the two values per set can never
    // lose a debt another server recorded, whatever order the rows arrive in.
    private static void writeSync(CompoundTag tag)
    {
        Data d = Data.get(ServerLifecycleHooks.getCurrentServer());
        if (d == null || tag == null)
            return;
        boolean changed = false;
        for (String setId : tag.getAllKeys())
        {
            long incoming = tag.getLong(setId);
            if (incoming > d.requests.getOrDefault(setId, 0L))
            {
                d.requests.put(setId, incoming);
                changed = true;
            }
        }
        if (changed)
            d.bump();
    }

    /* ---------------------------------------------------------------- storage */

    /** Owed (requests) and paid (served) stamps per set. Lives in the overworld's data folder. */
    static final class Data extends SavedData
    {
        final Map<String, Long> requests = new HashMap<>();
        final Map<String, Long> served = new HashMap<>();
        long signal;

        static Data get(MinecraftServer server)
        {
            if (server == null)
                return null;
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld == null)
                return null;
            return overworld.getDataStorage().computeIfAbsent(Data::load, Data::new, DATA_NAME);
        }

        void bump()
        {
            signal++;
            setDirty();
        }

        static Data load(CompoundTag tag)
        {
            Data d = new Data();
            CompoundTag req = tag.getCompound("requests");
            for (String k : req.getAllKeys())
                d.requests.put(k, req.getLong(k));
            CompoundTag srv = tag.getCompound("served");
            for (String k : srv.getAllKeys())
                d.served.put(k, srv.getLong(k));
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag)
        {
            CompoundTag req = new CompoundTag();
            requests.forEach(req::putLong);
            CompoundTag srv = new CompoundTag();
            served.forEach(srv::putLong);
            tag.put("requests", req);
            tag.put("served", srv);
            return tag;
        }
    }
}
