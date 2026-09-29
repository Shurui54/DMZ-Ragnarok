package net.shurui.shuruisutilities.client.combat;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.phys.Vec3;

/**
 * Client side record of who is currently being drawn as dashing, and, for a real dash, the route they are flying.
 *
 * <p>This exists because the aura reorientation happens deep inside DragonMineZ's own aura draw, which knows nothing
 * about our dash. The draw needs two answers at that moment, "should this player's aura be laid over the screen rather
 * than stood upright" and "along what heading", and it has no way to ask the server mid frame. So the dash tells this
 * class when it starts and stops, and the render side reads it.
 *
 * <h2>Two kinds of entry, and why the difference matters</h2>
 * A REAL dash comes from {@link net.shurui.shuruisutilities.combat.DashService} over the wire and carries a route.
 * Fast flight, from {@link FastFlightAura}, sets the same decoration but is not a dash. Three things must only ever
 * happen for a real one:
 * <ul>
 *   <li>DragonMineZ's dash animation clip. Firing it for ordinary fast flight is what made every takeoff play what
 *       looks like a dodge.</li>
 *   <li>Forcing {@code isFlyingFast} true. Fast flight is detected THROUGH that method, so forcing it there too would
 *       be a loop that never let go.</li>
 *   <li>Driving the player's own motion.</li>
 * </ul>
 *
 * <p>Ordinary FLIGHT is a third and weaker state, tracked in its own map below. It earns the line trail and nothing
 * else, so it is deliberately invisible to {@link #isDashing} and therefore to the aura work that reads it.
 *
 * <p>Entity id keyed rather than player keyed because the aura draw is handed a player it resolved itself, and ids are
 * what the dash packets carry for remote players.
 */
public final class DashAuraState
{
    private DashAuraState() {}

    /**
     * A dash the server planned, as the client needs to fly it.
     *
     * @param clientTicks how many client ticks this entry has been alive, which is the dash's clock on this side
     */
    public static final class Route
    {
        public final int modeId;
        public final int targetId;
        public final int windup;
        public final int travelTicks;
        // Not final. The server plans the route from where the player was standing when the request arrived, but by
        // the time the packet lands the player has kept moving, so flying the curve from that stale start point pulls
        // them BACK to where they were before it carries them forward. Re-anchored to the player's real position on
        // the first travelling tick, exactly as the server re-anchors its own copy.
        public Vec3 origin;
        public final Vec3 control;
        public final Vec3 side;
        public final double maxTravel;
        public int clientTicks;
        public boolean anchored;
        public double travelled;

        Route(int modeId, int targetId, int windup, int travelTicks, Vec3 origin, Vec3 control, Vec3 side,
                double maxTravel)
        {
            this.modeId = modeId;
            this.targetId = targetId;
            this.windup = windup;
            this.travelTicks = travelTicks;
            this.origin = origin;
            this.control = control;
            this.side = side;
            this.maxTravel = maxTravel;
        }
    }

    private static final class Entry
    {
        Vec3 heading;
        long expiresAtMillis;
        Route route; // null for fast flight
    }

    private static final Map<Integer, Entry> ACTIVE = new HashMap<>();

    // Players who are merely FLYING, kept apart from the map above on purpose. Ordinary flight earns the line trail and
    // nothing else: no laid over aura, no aura queued on a player who never powered up, no dash animation. Folding it
    // into ACTIVE would hand it all three, because every one of those reads isDashing.
    private static final Map<Integer, Long> FLYING = new HashMap<>();

    /**
     * Mark a player as flying, which earns the line trail and nothing more. Held on a short expiry for the same reason
     * the dash entries are: one dropped tick should not chop the streak in half.
     */
    public static void beginFlight(int entityId, int durationTicks)
    {
        FLYING.put(entityId, System.currentTimeMillis() + Math.max(1, durationTicks) * 50L);
    }

    public static void endFlight(int entityId)
    {
        FLYING.remove(entityId);
    }

    /** Whether this player should have a trail drawn behind them, from flight or from either kind of dash. */
    public static boolean isTrailing(int entityId)
    {
        if (isDashing(entityId))
            return true;
        Long expires = FLYING.get(entityId);
        if (expires == null)
            return false;
        if (System.currentTimeMillis() > expires)
        {
            FLYING.remove(entityId);
            return false;
        }
        return true;
    }

    /**
     * Mark a player as flying fast, which is decorated the same way a dash is but is not one. No route, so nothing that
     * belongs to a real dash fires.
     */
    public static void beginFastFlight(int entityId, Vec3 heading, int durationTicks)
    {
        if (heading == null)
            return;
        Entry existing = ACTIVE.get(entityId);
        // Never downgrade a live dash into fast flight. The two overlap constantly, because a dash IS moving fast.
        if (existing != null && existing.route != null)
            return;
        Entry entry = existing != null ? existing : new Entry();
        entry.heading = heading;
        entry.expiresAtMillis = System.currentTimeMillis() + Math.max(1, durationTicks) * 50L;
        entry.route = null;
        ACTIVE.put(entityId, entry);
    }

    /**
     * Mark a player as mid dash, with the route the server planned for them. The expiry is a safety net, not the normal
     * way a dash ends: if the stop packet is lost or the player leaves view mid dash, the aura still rights itself
     * instead of staying laid over forever.
     */
    public static void beginDash(int entityId, Vec3 heading, int durationTicks, int modeId, int targetId, int windup,
            int travelTicks, Vec3 origin, Vec3 control, Vec3 side, double maxTravel)
    {
        if (heading == null)
            return;
        Entry entry = new Entry();
        entry.heading = heading;
        entry.expiresAtMillis = System.currentTimeMillis() + Math.max(1, durationTicks) * 50L;
        entry.route = new Route(modeId, targetId, windup, travelTicks, origin, control, side, maxTravel);
        ACTIVE.put(entityId, entry);
    }

    public static void end(int entityId)
    {
        ACTIVE.remove(entityId);
    }

    /** Whether this player should be drawn with the dash treatment, from either cause. */
    public static boolean isDashing(int entityId)
    {
        return get(entityId) != null;
    }

    /** Whether this is a REAL dash rather than ordinary fast flight. */
    public static boolean isRealDash(int entityId)
    {
        Entry entry = get(entityId);
        return entry != null && entry.route != null;
    }

    /** The route of a real dash, or null. */
    public static Route route(int entityId)
    {
        Entry entry = get(entityId);
        return entry == null ? null : entry.route;
    }

    // Heading of an active dash, or null. Used to decide whether a REMOTE player's aura should be laid over: a dash
    // coming at the camera reads correctly laid over, one crossing the view does not.
    public static Vec3 heading(int entityId)
    {
        Entry entry = get(entityId);
        return entry == null ? null : entry.heading;
    }

    /** Update the direction a dash is currently travelling, as the client flies its curve. */
    public static void setHeading(int entityId, Vec3 heading)
    {
        Entry entry = get(entityId);
        if (entry != null && heading != null)
            entry.heading = heading;
    }

    private static Entry get(int entityId)
    {
        Entry entry = ACTIVE.get(entityId);
        if (entry == null)
            return null;
        if (System.currentTimeMillis() > entry.expiresAtMillis)
        {
            ACTIVE.remove(entityId);
            return null;
        }
        return entry;
    }

    public static void clear()
    {
        ACTIVE.clear();
        FLYING.clear();
    }
}
