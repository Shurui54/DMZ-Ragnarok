package net.shurui.shuruisutilities.shard;

import java.util.Locale;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.commons.selections.WarpPoint;
import net.shurui.shuruisutilities.api.key.TeleportHooks;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Keeps each server to the dimensions it is responsible for. Core keeps the answers here (pure {@code shard.json}
 * reads, true for everything while the network is off, so keyless and single servers host every dimension); the
 * enforcement (the travel hand-off, the recovery sweep, the eviction) is {@code ShardDimensionGuard}, which only
 * acts while {@code ShardSync.active()} and moves into the key in Sh2.
 *
 * <h2>The problem this closes</h2>
 * Every server in the network loads every dimension the modpack registers, so the SMP's nether, end, Namek and
 * planets also exist, empty, on the open world shards. Nothing routes anybody there, but a portal, a command or
 * a bug can, and a base built in that empty copy is on a world nobody else will ever load. Unlike almost every
 * other failure in this system, that one destroys player work and cannot be recovered.
 *
 * <h2>Redirect rather than refuse, decided 2026-09-04</h2>
 * This used to REFUSE: cancel the travel and tell the player to use {@code /server}. The argument was that
 * forwarding would have to invent a position on arrival, and there is no honest answer for where a player stepping
 * into a nether portal on the wrong server should appear in the owning server's copy of that dimension. The operator
 * has overridden that call: a hop is wanted for ALL dimensions, not a refusal, and the trigger case shows why. A
 * player who dies on the SMP shard is sent by DMZ to the Otherworld, which the SMP does not host, so under the old
 * behaviour they hit an error instead of going where the game just sent them. That is not a place {@code /server} is
 * a reasonable answer to.
 *
 * <p>The "invent a position" objection is now ANSWERED rather than dodged: the DESTINATION decides. When we hand a
 * player across for a generic travel event we record only the target dimension ({@code EntityTravelToDimensionEvent}
 * exposes only the entity and the target {@code ResourceKey<Level>}, never a position), and on arrival the owning
 * server places them at that dimension's own {@code ServerLevel.getSharedSpawnPos()}, which is real data on the server
 * that actually holds the world. The origin never guesses against its empty copy. Instant Transmission, which DOES
 * know its coordinates, still carries them (see {@link DimensionHandoff}).
 *
 * <p>Refusal survives in exactly one case: a dimension with NO recorded owner (or one whose recorded owner is this
 * server) has nowhere to hop to, so it still cancels and sends the message below. Silently swallowing a hop with no
 * destination would be worse than telling the player where the place is.
 *
 * <h2>Two layers</h2>
 * The travel event catches the ways a player normally changes dimension. The tick sweep catches everything else:
 * a player already standing somewhere they should not be when the rules change or when they log in carrying a
 * stored position, which no event covers. Prevention alone would leave a way in; recovery alone would let people
 * build for a minute first.
 *
 * <h2>What the travel event actually covers, corrected 2026-09-04</h2>
 * This used to say a direct {@code teleportTo(ServerLevel, ...)} does not raise the event. That is WRONG for a
 * player, and it is worth being precise because the mistake makes the wrong half of this class look responsible.
 * Forge patches {@code ServerPlayer.teleportTo(ServerLevel, ...)} to go through
 * {@code ForgeHooks.onTravelToDimension}, so it DOES raise {@code EntityTravelToDimensionEvent}, and a cancel
 * skips the entire move:
 *
 * <pre>
 *     } else if (net.minecraftforge.common.ForgeHooks.onTravelToDimension(this, p_9000_.dimension())) {
 * </pre>
 *
 * <p>Two consequences. A cancelled cross-dimension teleport is a SILENT no-op at the call site: the caller gets no
 * exception and no return value, the player simply does not move, so anything that spends a resource before
 * teleporting has already spent it. DragonMineZ's Instant Transmission does exactly that, charging ki and starting
 * the cooldown before the call. And the sweep is not the thing catching stray {@code teleportTo} calls, because
 * they never land in the first place.
 *
 * <p>The old claim IS true of a bare {@code Entity}, whose {@code teleportTo} does not change dimension at all,
 * which is the likeliest source of the confusion.
 */
public final class ShardDimensions
{
    private ShardDimensions() {}

    /** {@code dimensionMode} in {@code shard.json}. */
    public enum Mode { OFF, DENY, ALLOW }

    /** The configured {@code dimensionMode}; OFF when unset or unreadable. */
    public static Mode mode()
    {
        try
        {
            return Mode.valueOf(ShardConfig.get().dimensionMode.trim().toUpperCase(Locale.ROOT));
        }
        catch (Exception e)
        {
            return Mode.OFF;
        }
    }

    /** May this server host the given dimension? */
    public static boolean hosts(ResourceKey<Level> dimension)
    {
        if (dimension == null)
            return true;
        Mode m = mode();
        if (m == Mode.OFF || !ShardSync.active())
            return true;
        String id = dimension.location().toString();
        boolean listed = ShardConfig.get().dimensions.contains(id);
        return m == Mode.DENY ? !listed : listed;
    }

    /** The server that does host it, or null when nothing has been recorded. */
    private static String ownerOf(ResourceKey<Level> dimension)
    {
        return ShardConfig.get().dimensionOwners.get(dimension.location().toString());
    }

    /**
     * The configured owner of a dimension this server does not host, or null when none is recorded. Public so the
     * space module can find the shard that hosts the space dimension (and, on the return trip, the shard that hosts
     * the origin) to hand a player across rather than teleport them into a local copy this server would then evict.
     */
    public static String owner(ResourceKey<Level> dimension)
    {
        return dimension == null ? null : ownerOf(dimension);
    }

    /**
     * May THIS server place WORLD CONTENT (a dimensional tear, an airdrop, a scheduled spawn) in the given
     * dimension?
     *
     * <h2>Not the same question as {@link #hosts}</h2>
     * {@link #hosts} asks whether a PLAYER may be here, and is answered by {@code dimensionMode} plus the
     * {@code dimensions} list. This asks whether anything we put here would ever be SEEN. Every server in the
     * network loads every dimension the modpack registers, so a dimension another shard owns also exists here,
     * empty, with nobody routed into it. Content placed in that copy is invisible to the whole playerbase, and
     * merely finding a spot for it pays full worldgen on the tick thread, because none of those chunks has ever
     * been generated. On 2026-09-19 that was 26 percent of the SMP's tick samples.
     *
     * <h2>What answers yes</h2>
     * <ul>
     *   <li>The shard system is off or keyless ({@link ShardSync#active()}). That is singleplayer, LAN and every
     *       ordinary single server: there is no owner table, so this server owns everything there is.</li>
     *   <li>No owner is recorded for the dimension. An absent entry must never silently delete content, the same
     *       way {@link #hosts} keeps an unlisted dimension reachable under DENY.</li>
     *   <li>The recorded owner is this server.</li>
     *   <li>The recorded owner is one of our {@link ShardConfig.Values#ghostPeers}. A ghost peer is BY DEFINITION
     *       running a copy of this same world, so the dimension is exactly as populated and exactly as warm here
     *       as it is there. The two open world shards each hold a live copy of the overworld and each run their
     *       own events in it, which is intended and long standing; it is the SMP's cold, never visited copy of
     *       somebody else's world that is not.</li>
     * </ul>
     */
    public static boolean ownsContentIn(String dimensionId)
    {
        if (!ShardSync.active() || dimensionId == null || dimensionId.isBlank())
            return true;
        ShardConfig.Values cfg = ShardConfig.get();
        String owner = cfg.dimensionOwners.get(dimensionId);
        if (owner == null || owner.isBlank())
            return true;
        if (owner.equalsIgnoreCase(cfg.serverId))
            return true;
        if (cfg.ghostPeers != null)
        {
            for (String peer : cfg.ghostPeers)
            {
                if (peer != null && peer.equalsIgnoreCase(owner))
                    return true;
            }
        }
        return false;
    }

    /** As {@link #ownsContentIn(String)}, for a dimension that has already been resolved to a key. */
    public static boolean ownsContentIn(ResourceKey<Level> dimension)
    {
        return dimension == null || ownsContentIn(dimension.location().toString());
    }

    /**
     * The configured {@code arrivalWarp}, when one is set and is actually usable here. Null otherwise.
     *
     * <h2>Why this is shared rather than private to the eviction</h2>
     * It answers "where do arrivals belong on this server", which is the same question for somebody being pulled
     * out of a dimension we should not be hosting and for somebody who has just walked in off {@code /server}.
     * The SMP is the case that makes it matter: it does not host the overworld, so the usual "put them at the
     * world spawn" answer is not available there, and {@code arrivalWarp} is the operator's statement of what to
     * do instead. Having one definition means the two paths cannot drift into landing people in different places.
     *
     * <p>Refuses a warp whose own dimension this server does not host. For the eviction that would move the loop
     * rather than end it; for an arrival it would drop somebody straight back into the case being avoided.
     */
    public static WarpPoint arrivalWarp()
    {
        String name = ShardConfig.get().arrivalWarp;
        if (name == null || name.isBlank())
            return null;
        try
        {
            WarpPoint point = TeleportHooks.get().warps().get(name);
            if (point == null)
            {
                LoggingHandler.sulog.warn("[shard] arrivalWarp '{}' does not exist; falling back.", name);
                return null;
            }
            ServerLevel level = point.getWorld();
            if (level == null)
            {
                LoggingHandler.sulog.warn("[shard] arrivalWarp '{}' names a dimension that is not loaded here; "
                        + "falling back.", name);
                return null;
            }
            if (!hosts(level.dimension()))
            {
                LoggingHandler.sulog.warn("[shard] arrivalWarp '{}' is in {}, which this server does not host; "
                        + "falling back.", name, level.dimension().location());
                return null;
            }
            return point;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[shard] arrivalWarp '{}' failed: {}", name, t.toString());
            return null;
        }
    }
}
