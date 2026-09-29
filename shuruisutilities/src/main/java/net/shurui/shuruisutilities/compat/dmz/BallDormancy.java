package net.shurui.shuruisutilities.compat.dmz;

import java.util.List;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.server.events.DragonBallsHandler;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.ritual.RitualAnnouncer;
import net.shurui.shuruisutilities.shard.ShardChat;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The dormancy mechanic for DragonMineZ ball sets: when a set is spent on a wish its balls turn to stone (grey,
 * translucent, radar hidden and unusable), and after a per set duration they wake, which is announced across the
 * whole network. This class is the server side hub: it owns the durations, marks a set dormant, sweeps for wakes,
 * and answers "is this set dormant" for the summon gate and the radar strip.
 *
 * <p>All DragonMineZ access lives behind this class, reached only from {@link BallDormancyEvents} and {@link
 * BallSetCommands} (both already DMZ facing). DMZ is a mandatory dependency, so importing its types directly is
 * fine, exactly as {@link CeruleanCleanupCommand} does.
 *
 * <h2>What "dormant" does and does NOT do</h2>
 *
 * <p>A dragon summon CONSUMES the seven balls, then DMZ re-scatters the set when the dragon despawns. We do NOT try
 * to stop that scatter: the freshly scattered set is exactly the stone the player is meant to see. Instead the set
 * is marked dormant here, which the three seams honour: the summon is refused ({@link MixinDmzDragonBallBlock}),
 * the balls are stripped from every radar ({@code MixinDmzRadarDormant}), and they draw grey and see through
 * ({@code MixinDmzDragonBallDormantRender}). Suppressing the scatter instead would leave no ball blocks at all,
 * which is incompatible with the grey stone the owner asked for, so we neutralise the set's USABILITY rather than
 * its existence. See the report for the full reasoning.
 */
public final class BallDormancy
{
    private BallDormancy() {}

    // Default dormancy for earth, namek, blackstar, cerulean: one Minecraft week of GAME time. 7 * 24000 ticks,
    // measured on level.getGameTime() so server downtime does not count (mirrors the grave system).
    public static final long WEEK_TICKS = 7L * 24000L;

    // Super only: one REAL life day of WALL clock time. Deliberately a different clock from the others (see
    // BallDormancyStorage.Clock): a real day passes whether or not the server is running.
    public static final long SUPER_WALL_MILLIS = 24L * 60L * 60L * 1000L;

    /** The one set measured on the wall clock. Every other set uses game time. */
    public static final String WALL_CLOCK_SET = "super";

    /** True when the set is currently dormant on this shard. Cheap, safe to call from render adjacent code. */
    public static boolean isDormant(MinecraftServer server, String setId)
    {
        if (server == null || setId == null)
            return false;
        try
        {
            return BallDormancyStorage.get(server).isDormant(setId);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** Every set dormant on this shard right now. */
    public static List<String> dormantSets(MinecraftServer server)
    {
        try
        {
            return BallDormancyStorage.get(server).dormantSets();
        }
        catch (Throwable t)
        {
            return List.of();
        }
    }

    /**
     * Mark a set dormant, choosing its clock and deadline. Idempotent: a set already dormant keeps its existing
     * deadline rather than having the timer restarted, so gathering and re-wishing during dormancy cannot extend
     * it (the summon gate should stop that anyway). Broadcasts the client sync and refreshes every radar so the
     * balls vanish from radar and go grey at once.
     */
    public static void markDormant(MinecraftServer server, String setId)
    {
        if (server == null || setId == null || setId.isEmpty())
            return;
        try
        {
            BallDormancyStorage storage = BallDormancyStorage.get(server);
            if (storage.isDormant(setId))
                return;

            if (WALL_CLOCK_SET.equals(setId))
            {
                long deadline = System.currentTimeMillis() + SUPER_WALL_MILLIS;
                storage.put(setId, BallDormancyStorage.Clock.WALL, deadline);
                LoggingHandler.sulog.info("[dormancy] {} set is now dormant until {} (wall clock, one real day)",
                        setId, deadline);
            }
            else
            {
                long deadline = overworldGameTime(server) + WEEK_TICKS;
                storage.put(setId, BallDormancyStorage.Clock.GAME, deadline);
                LoggingHandler.sulog.info("[dormancy] {} set is now dormant until game time {} (one Minecraft week)",
                        setId, deadline);
            }

            BallDormancySync.broadcast(server);
            refreshRadar(server);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dormancy] could not mark {} dormant: {}", setId, t.toString());
        }
    }

    /**
     * Force a set awake now (admin {@code /awaken}, or when {@code /scatter} recreates it). Clears the record,
     * re-syncs clients and radar. Silent (no wake announcement): the announcement is for the scheduled wake, not
     * an admin override. Returns true when the set was dormant.
     */
    public static boolean forceWake(MinecraftServer server, String setId)
    {
        if (server == null || setId == null)
            return false;
        try
        {
            boolean cleared = BallDormancyStorage.get(server).clear(setId);
            if (cleared)
            {
                BallDormancySync.broadcast(server);
                refreshRadar(server);
            }
            return cleared;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dormancy] could not wake {}: {}", setId, t.toString());
            return false;
        }
    }

    /** Force a set dormant now (admin {@code /unawaken}), reusing the normal per set duration and announcement path. */
    public static void forceDormant(MinecraftServer server, String setId)
    {
        // clear first so markDormant restarts the timer even if it was already dormant
        try
        {
            BallDormancyStorage.get(server).clear(setId);
        }
        catch (Throwable ignored)
        {
        }
        markDormant(server, setId);
    }

    /**
     * The periodic sweep: wake any dormant set whose deadline has passed on its own clock. Called every few ticks
     * from {@link BallDormancyEvents}. For each waking set it clears the record, re-syncs clients and radar, and
     * announces the wake network wide. Cheap when nothing is dormant.
     */
    public static void sweep(MinecraftServer server)
    {
        if (server == null)
            return;
        BallDormancyStorage storage;
        try
        {
            storage = BallDormancyStorage.get(server);
        }
        catch (Throwable t)
        {
            return;
        }
        List<String> sets = storage.dormantSets();
        if (sets.isEmpty())
            return;

        long gameTime = overworldGameTime(server);
        long now = System.currentTimeMillis();
        boolean anyWoke = false;
        for (String setId : sets)
        {
            BallDormancyStorage.Dormant d = storage.get(setId);
            if (d == null)
                continue;
            boolean due = d.clock() == BallDormancyStorage.Clock.WALL ? now >= d.deadline() : gameTime >= d.deadline();
            if (!due)
                continue;
            storage.clear(setId);
            anyWoke = true;
            announceWake(server, setId);
            LoggingHandler.sulog.info("[dormancy] {} set woke ({} clock)", setId, d.clock());
        }
        if (anyWoke)
        {
            BallDormancySync.broadcast(server);
            refreshRadar(server);
        }
    }

    /** The overworld game time, the monotonic tick counter every GAME clock deadline is measured against. */
    private static long overworldGameTime(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld == null ? 0L : overworld.getGameTime();
    }

    // Rebuild and resend every radar packet. syncRadar gathers all sets across all dimensions and sends to all
    // players, so any hosted level triggers a full refresh; the overworld is always present.
    private static void refreshRadar(MinecraftServer server)
    {
        try
        {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld != null)
                DragonBallsHandler.syncRadar(overworld);
        }
        catch (Throwable ignored)
        {
        }
    }

    // Announce a wake the way DMZ Plus announces its rare moments: a title and chat line locally (via
    // RitualAnnouncer, which the ritual and defiled events already use), plus the chat line to the rest of the
    // network (via ShardChat, since RitualAnnouncer is single shard). announceRemote does NOT echo locally, so the
    // line shows exactly once on every shard.
    private static void announceWake(MinecraftServer server, String setId)
    {
        try
        {
            Component setName = displayName(setId);
            RitualAnnouncer.announceServerWide(server,
                    "message.dmz_ragnarok.core.dormancy.wake.chat",
                    "message.dmz_ragnarok.core.dormancy.wake.title",
                    "message.dmz_ragnarok.core.dormancy.wake.subtitle",
                    setName, ChatFormatting.AQUA);
            ShardChat.announceRemote(Component.translatable(
                    "message.dmz_ragnarok.core.dormancy.wake.chat", setName).withStyle(ChatFormatting.AQUA));
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dormancy] wake announcement for {} failed: {}", setId, t.toString());
        }
    }

    /** A human name for a set: its declared display name if any, else the id with its first letter capitalised. */
    public static Component displayName(String setId)
    {
        try
        {
            DragonBallSetDefinition def = DragonBallDefinitions.getBallSet(setId);
            if (def != null && def.getDisplayName() != null && def.getDisplayName().isPresent())
                return Component.literal(def.getDisplayName().get());
        }
        catch (Throwable ignored)
        {
        }
        if (setId == null || setId.isEmpty())
            return Component.literal("Dragon Ball");
        return Component.literal(Character.toUpperCase(setId.charAt(0)) + setId.substring(1));
    }
}
