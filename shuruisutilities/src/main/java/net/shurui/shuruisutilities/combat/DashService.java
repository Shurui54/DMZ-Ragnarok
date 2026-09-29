package net.shurui.shuruisutilities.combat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The dash: a committed run at whoever you are aiming at, along a planned path, ending in contact.
 *
 * <p>Server authoritative in the strong sense. The client sends a request and may name what it thinks it is aiming at,
 * but the server picks the target itself, plans the route itself, and decides where the dash ends. Nothing here trusts
 * the client for anything that matters.
 *
 * <h2>Why the target is acquired here and not on the client</h2>
 * The first version asked the client who it was looking at, and the client answered with {@code crosshairPickEntity},
 * which is limited to the player's ATTACK REACH. Past about four blocks it is always empty, so a dash across a fight
 * essentially never found a target, and every dash silently degraded into a straight travel burst. That is the whole of
 * "the double tap does not position and arc to the entity you are targeting". Acquisition now happens below, over the
 * dash's full range, against entity boxes inflated a little, with a look cone as the fallback for a target that is far
 * enough away that the crosshair cannot realistically be held on it.
 *
 * <h2>Why the path is sampled rather than steered</h2>
 * A dash used to be a heading plus a speed, re-aimed at the target every tick. That cannot arc: re-aiming straight at
 * the target immediately undoes whatever lean the arc mode applied, so every mode travelled in a straight line and only
 * the first tick differed. A dash now plans a curve when it starts, samples the point it should be at this tick, and
 * moves toward that point. The curve's END is recomputed each tick so it still tracks a target that moves, but its
 * SHAPE is fixed at the start, which is what makes an arc read as a deliberate route rather than a wobble.
 *
 * <p>Movement is still applied as velocity toward the sampled point rather than by setting the position outright. That
 * keeps the player's own client prediction agreeing with the server, and it keeps terrain collision working: a dash
 * that runs into a wall stops there instead of passing through it.
 *
 * <h2>Contact</h2>
 * A dash that reaches its target hits them. Two dashes that meet head on instead become a clash, which is
 * {@link MeleeClashService}'s job; this service only stays out of its way while that forms.
 */
public final class DashService
{
    private DashService() {}

    // Ticks between committing and moving on a dash AT SOMEONE. Also the window in which a mutual dash can be detected
    // as a clash. A dash with no target does not wind up at all: there is nobody for the tell to warn and nobody it
    // could clash with, so freezing the player mid air first only made a travel dash feel like a stutter.
    public static final int WINDUP_TICKS = 3;

    // Hard ceiling on how long one dash can run, so a dash that never arrives still ends.
    public static final int MAX_DASH_TICKS = 58;

    // Ticks of cooldown after a dash ends. Five seconds: long enough that a dash is a decision rather than a way of
    // moving around, which is what a cheap one turns into.
    public static final int COOLDOWN_TICKS = 100;

    // Furthest a dash will reach for a target.
    public static final double MAX_RANGE = 104.0D;

    // Something small enough that dashing at it from across the map would be an accident rather than an intent. Only
    // targetable up close.
    public static final double SMALL_TARGET_RANGE = 9.0D;

    // How far a dash with NO target travels. A dash is a burst, not a flight: without this bound an untargeted dash ran
    // its full duration, which is roughly a hundred and fifteen blocks.
    public static final double FREE_DASH_DISTANCE = 18.0D;

    // Blocks per tick a free dash travels.
    public static final double SPEED = 3.2D;

    // How far the player may drift from where the route says they should be before the server hauls them back.
    //
    // This is a leash, not a correction. The CLIENT flies the route now (see DashPath and DashLocalMotion), because a
    // dash driven by one velocity packet per tick is always a round trip behind the player's own input and reads as
    // jumpy and late. So the server plans, watches, and only intervenes when the two have genuinely parted company,
    // which is what a client that is not driving the dash at all looks like. Generous on purpose: a couple of ticks of
    // ordinary latency is several blocks at dash speed, and yanking on that is the very stutter this removes.
    private static final double LEASH_DISTANCE = 10.0D;

    // Extra ticks the server lets a route run past its planned length before calling it over. The client's clock starts
    // when the route packet lands, so it is always a couple of ticks behind the server's; without this the server would
    // end the dash while the client still had the last of the curve to fly, and every dash would stop just short.
    private static final int ROUTE_GRACE_TICKS = 4;

    // Contact happens here. Slightly wider than the standoff, so arrival is decided by being close rather than by
    // landing exactly on a point that a moving target keeps shifting.
    public static final double IMPACT_DISTANCE = 4.2D;

    // While two mutual dashers are this close, contact is left alone: the clash forming between them owns the meeting.
    public static final double CLASH_YIELD_DISTANCE = 6.2D;

    // How square onto the look vector something has to be to be dashed at when the crosshair is not exactly on it.
    // Generous on purpose: at range you cannot hold a crosshair on a moving player, and a dash you have to aim like a
    // rifle is not a dash.
    private static final double AIM_CONE = 0.94D;

    // Entity boxes are grown by this much for the aim ray, so a dash forgives the pixel either side of a hitbox.
    private static final double AIM_INFLATE = 0.4D;

    // What counts as wedged: barely moving at all, for long enough that it cannot be a slow stretch of a route.
    //
    // Deliberately strict on both counts. An earlier, looser version aborted dashes that were merely lagging their
    // curve, which ended them mid flight and dropped the player short of where they were going, and a flank or a climb
    // that is cut off halfway leaves them in exactly the spot the mode existed to avoid. A player genuinely against a
    // wall moves essentially zero; a player mid arc always moves more than a block a tick.
    private static final double WALL_PROGRESS = 0.35D;
    private static final int WALL_TICKS_BEFORE_ABORT = 6;

    // How far a dash carries on after its target stops existing, so it glides out instead of stopping dead in the air.
    private static final double LOST_TARGET_GLIDE = 4.0D;

    // Contact damage floor, used when the dasher has no meaningful attack damage of their own.
    private static final float MIN_IMPACT_DAMAGE = 1.05F;

    // How hard contact throws the target, and how much of that is lift.
    private static final double IMPACT_KNOCKBACK = 2.1D;
    private static final double IMPACT_LIFT = 0.38D;

    private static final class Dash
    {
        int ticks;
        int windup;
        DashMode mode;

        // Targeted route. travelTicks is 0 for a free dash.
        int targetId;
        int travelTicks;
        Vec3 origin;   // eye-space start of the route
        Vec3 control;  // eye-space arc control point, fixed for the whole dash
        Vec3 side;     // which side of the target this dash finishes on, fixed for the whole dash

        // Free burst.
        Vec3 heading;
        double travelled;
        double maxTravel;

        // Shared bookkeeping.
        boolean hitTarget;
        boolean travelling;
        Vec3 lastPos;
        int stuckTicks;
    }

    private static final Map<UUID, Dash> ACTIVE = new HashMap<>();
    private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();

    /**
     * Ask for a dash. Rejected silently when the player is already dashing, still cooling down, or has no usable
     * heading. Returns whether the dash was accepted, which the caller uses to decide whether to tell clients.
     *
     * @param clientHintId what the client believes it is aiming at. A hint only: it is re-validated, and acquisition
     *                     runs regardless, so a client that names nothing still gets a proper targeted dash.
     */
    public static boolean request(ServerPlayer player, int clientHintId, DashMode mode)
    {
        if (player == null || mode == null)
            return false;
        // Public since 2026-08-30, but still asked, because the question is now "is the combat layer in this
        // server's feature set" rather than "does this server hold the full key". Refused before anything is spent
        // or broadcast, so no route is planned, no cost is taken and no client is told to draw an aura or a trail
        // for a dash that is not happening.
        if (!net.shurui.shuruisutilities.core.config.PublicContent.allows(
                net.shurui.shuruisutilities.core.config.PublicContent.FEATURE_COMBAT_LAYER))
            return false;
        UUID id = player.getUUID();
        if (ACTIVE.containsKey(id) || COOLDOWN.getOrDefault(id, 0) > 0)
            return false;

        LivingEntity target = acquireTarget(player, clientHintId);
        Dash dash = new Dash();
        dash.ticks = 0;
        dash.mode = mode;
        dash.travelling = false;
        dash.lastPos = player.position();
        dash.stuckTicks = 0;

        if (target == null)
        {
            Vec3 look = player.getLookAngle();
            if (look.lengthSqr() < 1.0E-6D)
                return false;
            // A dash with nobody to dash at just goes where you are looking. The modes describe where you end up
            // RELATIVE TO SOMEONE, so with no one there they have nothing to mean, and bending a travel dash sideways
            // because a movement key happened to be held is exactly the surprise this used to produce.
            dash.targetId = 0;
            dash.windup = 0;
            dash.heading = look.normalize();
            dash.travelled = 0.0D;
            dash.maxTravel = FREE_DASH_DISTANCE;
        }
        else
        {
            dash.targetId = target.getId();
            dash.windup = WINDUP_TICKS;
            dash.heading = aimAt(player, target);
            // Planned at request time so the length is known now: the client is told how long to decorate the dash for,
            // and the route is what decides that.
            planRoute(player, target, dash);
        }

        // Charged last, once everything else has agreed the dash can happen, so a refused or impossible dash never
        // costs anything and a paid one always produces one.
        if (!DashCost.tryPay(player))
            return false;

        ACTIVE.put(id, dash);
        playStartSound(player);
        return true;
    }

    /**
     * The dash's own noise. Audible to everyone nearby, not just the dasher: a dash coming at you is something you
     * should be able to hear before you see it, and the windup is exactly the moment that warning is useful.
     *
     * <p>DragonMineZ's own fast flight cue is used for the body of it, because a dash IS entering fast flight as far as
     * the player is concerned and borrowing the sound they already associate with that is worth more than a new one.
     * Same event and same pitch DMZ plays when its own sprint flight kicks in. The vanilla sweep stays underneath it as
     * the impact of the launch itself, quiet enough to read as texture rather than as a second sound.
     */
    private static void playStartSound(ServerPlayer player)
    {
        try
        {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP, net.minecraft.sounds.SoundSource.PLAYERS,
                    0.5F, 0.6F);
        }
        catch (Throwable ignored)
        {
        }
        try
        {
            // Resolved through DMZ's registry object rather than by name so a rename is a compile error here rather
            // than a silent miss at runtime. Wrapped separately so losing it does not cost the sweep as well.
            net.minecraft.sounds.SoundEvent flightStart = com.dragonminez.common.init.MainSounds.TRANSFORM_ON.get();
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), flightStart,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, 1.2F);
        }
        catch (Throwable ignored)
        {
        }
    }

    /** The planned route of an active dash, or null. Sent to clients so they can fly it themselves. */
    public static Route routeOf(Entity entity)
    {
        Dash dash = entity == null ? null : ACTIVE.get(entity.getUUID());
        if (dash == null)
            return null;
        return new Route(dash.mode.ordinal(), dash.targetId, dash.windup, dash.travelTicks,
                dash.origin, dash.control, dash.side, dash.heading, dash.maxTravel);
    }

    /**
     * Everything a client needs to fly the same curve the server planned.
     *
     * @param targetId    0 for a plain travel burst, in which case only heading and maxTravel matter
     * @param origin      eye-space start of the curve
     * @param control     eye-space control point; the destination is recomputed each tick from the live target
     */
    public record Route(int modeId, int targetId, int windup, int travelTicks, Vec3 origin, Vec3 control,
            Vec3 side, Vec3 heading, double maxTravel) {}

    /**
     * Who this dash is for, or null for a plain travel burst.
     *
     * <p>Two passes, in order of how deliberate the aim was. First an actual ray down the look vector against slightly
     * grown entity boxes, stopped at the first solid block, which is the precise answer and the one that wins when the
     * crosshair really is on someone. Then a look cone, which is what catches a target far enough away that holding the
     * crosshair on them is not a reasonable thing to ask.
     */
    private static LivingEntity acquireTarget(ServerPlayer player, int clientHintId)
    {
        try
        {
            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getLookAngle();
            if (look.lengthSqr() < 1.0E-6D)
                return null;
            look = look.normalize();
            // The aim ray stops at terrain: you cannot dash at someone through a hill, and letting the ray continue
            // would let a dash pick a target on the far side of a wall it is then going to bounce off.
            Vec3 far = eye.add(look.scale(MAX_RANGE));
            HitResult blocked = player.level().clip(
                    new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            Vec3 rayEnd = blocked != null && blocked.getType() != HitResult.Type.MISS ? blocked.getLocation() : far;

            List<Entity> candidates = player.level().getEntities(player,
                    new AABB(eye, rayEnd).inflate(2.0D),
                    e -> e instanceof LivingEntity && e.isAlive() && e != player
                            && EntitySelector.NO_SPECTATORS.test(e) && e.isPickable());
            if (candidates.isEmpty())
                return null;

            LivingEntity byRay = null;
            double bestRay = Double.MAX_VALUE;
            LivingEntity byCone = null;
            double bestCone = AIM_CONE;

            for (Entity e : candidates)
            {
                if (!(e instanceof LivingEntity living))
                    continue;
                double distance = player.distanceTo(living);
                if (distance > MAX_RANGE)
                    continue;
                // Something the size of a bat or an item-sized mob is almost never what a dash across a battlefield was
                // meant for, so it only counts as a target when it is close enough to have been aimed at on purpose.
                if (isSmall(living) && distance > SMALL_TARGET_RANGE)
                    continue;

                var hit = living.getBoundingBox().inflate(AIM_INFLATE).clip(eye, rayEnd);
                if (hit.isPresent())
                {
                    double d = eye.distanceToSqr(hit.get());
                    if (d < bestRay)
                    {
                        bestRay = d;
                        byRay = living;
                    }
                    continue;
                }
                // Cone fallback. Alignment first, distance only as the tie break, so the thing you are most squarely
                // pointed at wins rather than merely the nearest thing in front of you.
                Vec3 toTarget = living.getEyePosition().subtract(eye);
                if (toTarget.lengthSqr() < 1.0E-6D)
                    continue;
                double alignment = toTarget.normalize().dot(look);
                if (alignment > bestCone && hasLineOfSight(player, living))
                {
                    bestCone = alignment;
                    byCone = living;
                }
            }
            if (byRay != null)
                return byRay;
            if (byCone != null)
                return byCone;
            // Last resort, the client's own guess, still range and sight checked. It costs nothing and covers the case
            // where the player's view moved between the press and the packet arriving.
            if (clientHintId > 0 && player.level().getEntity(clientHintId) instanceof LivingEntity hinted
                    && hinted != player && hinted.isAlive() && player.distanceTo(hinted) <= MAX_RANGE
                    && hasLineOfSight(player, hinted))
            {
                return hinted;
            }
            return null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static boolean isSmall(LivingEntity entity)
    {
        return entity.getBbHeight() < 1.2F || entity.getBbWidth() < 0.5F;
    }

    private static boolean hasLineOfSight(ServerPlayer player, LivingEntity target)
    {
        try
        {
            HitResult hit = player.level().clip(new ClipContext(player.getEyePosition(), target.getEyePosition(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            return hit == null || hit.getType() == HitResult.Type.MISS;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    // Work out the curve this dash will fly and how long it will take. Called once, at request time, because the whole
    // route is then sent to clients and they fly it from there.
    private static void planRoute(ServerPlayer player, LivingEntity target, Dash dash)
    {
        Vec3 origin = player.getEyePosition();
        // Decided once, here. Recomputing which side to finish on every tick is what turned a flank into a lap.
        dash.side = DashPath.pinnedSide(origin, target.getEyePosition(), DashPath.bodyFacing(target), dash.mode);
        Vec3 destination = destination(player, target, dash);
        dash.origin = origin;
        dash.control = DashPath.control(origin, destination, target.getEyePosition(), dash.side, dash.mode,
                target.getBbWidth() * 0.5D, target.getBbHeight() * 0.5D);
        // Timed off the curve rather than the straight line, or an arc would be asked to cover noticeably more ground
        // in the same time and would visibly speed up the further it leaned.
        dash.travelTicks = DashPath.travelTicks(DashPath.length(origin, dash.control, destination));
    }

    private static Vec3 destination(ServerPlayer player, LivingEntity target, Dash dash)
    {
        return DashPath.destination(player.getEyePosition(), target.getEyePosition(), DashPath.bodyFacing(target),
                target.getBbWidth() * 0.5D, target.getBbHeight() * 0.5D, dash.mode, dash.side);
    }

    /** Advance every dash by one tick. */
    public static void tick(MinecraftServer server)
    {
        if (server == null)
            return;
        for (Iterator<Map.Entry<UUID, Integer>> it = COOLDOWN.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry<UUID, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left <= 0)
                it.remove();
            else
                e.setValue(left);
        }

        for (Iterator<Map.Entry<UUID, Dash>> it = ACTIVE.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry<UUID, Dash> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Dash dash = entry.getValue();
            // A player who logged out or died mid dash simply stops being dashed. Their cooldown is not set, because a
            // dash that never happened should not be charged for. Note there is deliberately NO check that the player is
            // in some particular level: the dash is looked up from the server's player list, so it follows the player.
            if (player == null || !player.isAlive())
            {
                it.remove();
                if (player != null)
                    PacketDashState.broadcastEnd(player);
                continue;
            }
            dash.ticks++;
            if (dash.ticks <= dash.windup)
            {
                // Committed but not moving. The client holds itself still for the same window, so this is only the
                // backstop for a client that is not, and it is applied without hurtMarked so a cooperating client is
                // never fought over.
                player.setDeltaMovement(Vec3.ZERO);
                player.fallDistance = 0.0F;
                continue;
            }
            if (!dash.travelling)
            {
                // First travelling tick. Measured from here so the windup's enforced stillness is not read as a wall,
                // and the route re-anchored here so a dash that was shoved during its windup still starts from where
                // the player actually is.
                dash.travelling = true;
                dash.lastPos = player.position();
                if (dash.targetId > 0)
                    dash.origin = player.getEyePosition();
            }
            if (dash.ticks > MAX_DASH_TICKS)
            {
                end(it, entry.getKey(), player, dash, true);
                continue;
            }
            boolean finished = dash.targetId > 0 ? tickTargeted(player, dash) : tickFree(player, dash);
            if (finished)
                end(it, entry.getKey(), player, dash, true);
        }
    }

    // One tick of a dash at somebody. Returns whether the dash is over.
    private static boolean tickTargeted(ServerPlayer player, Dash dash)
    {
        LivingEntity target = targetOf(player, dash);
        if (target == null)
        {
            // Target gone, killed or vanished mid dash. Rather than stopping dead in mid air, the dash finishes as a
            // short glide along the way it was already going, which is far less jarring than a hard stop. Measured as
            // an addition to what has been travelled so far, because that counter keeps running.
            dash.targetId = 0;
            dash.maxTravel = dash.travelled + LOST_TARGET_GLIDE;
            return dash.heading == null;
        }

        // Measured from the target's SURFACE, not its centre. Both of these are centre to centre distances, and against
        // anything wider than a player a fixed radius sits inside the body, so contact fired while still buried in it
        // and a flank was judged to have arrived before it had cleared the far side.
        double reach = target.getBbWidth() * 0.5D;
        double distance = player.distanceTo(target) - reach;
        // A clash forming between two mutual dashers owns the meeting. Standing off here stops contact from resolving
        // the exchange a tick before MeleeClashService has had the chance to.
        boolean yieldToClash = distance <= CLASH_YIELD_DISTANCE && isDashing(target)
                && dash.mode.canClash() && closing(player, target);
        // Every mode lands its blow. Only the straight run stops there; the others hit on the way past and carry on to
        // the position they were asked for. Latched, so a flank that stays inside striking range for several ticks
        // still only hits once.
        if (!dash.hitTarget && !yieldToClash && distance <= IMPACT_DISTANCE)
        {
            dash.hitTarget = true;
            impact(player, target, dash.mode.stopsOnContact());
            if (dash.mode.stopsOnContact())
                return true;
        }

        // Where the route says the player should be by the end of this tick. The client is flying this same curve from
        // the same numbers, so this is a comparison, not a command.
        Vec3 destination = destination(player, target, dash);
        double t = (double) (dash.ticks - dash.windup) / (double) Math.max(1, dash.travelTicks);
        Vec3 want = DashPath.sample(dash.origin, dash.control, destination, t);
        // Sampled in eye space, applied to the body.
        Vec3 wantFeet = want.subtract(0.0D, player.getEyeHeight(), 0.0D);
        Vec3 step = wantFeet.subtract(player.position());
        double length = step.length();
        dash.heading = length < 1.0E-6D ? dash.heading : step.normalize();

        boolean stuck = advance(player, dash, step, length);
        // Past the end of the planned route without having made contact: the target outran it. Ended rather than
        // extended, so a dash cannot follow someone indefinitely. The grace is the client's clock being a couple of
        // ticks behind ours; contact is decided by distance, so those ticks are where a close call actually lands.
        return stuck || (dash.ticks - dash.windup) > dash.travelTicks + ROUTE_GRACE_TICKS;
    }

    // One tick of a plain travel burst. Returns whether the dash is over.
    private static boolean tickFree(ServerPlayer player, Dash dash)
    {
        double remaining = dash.maxTravel - dash.travelled;
        if (remaining <= 0.0D)
            return true;
        Vec3 step = dash.heading.scale(Math.min(SPEED, remaining));
        // A free burst has no route to compare against, so the leash is measured against the step it should have taken.
        return advance(player, dash, step, step.length());
    }

    /**
     * Watch one tick of movement, and only take the wheel if the player is not where the route says.
     *
     * <p>The dash used to write the player's velocity here every tick with {@code hurtMarked} set, which forces the
     * server's answer down to the client. That is what made it feel jumpy: the client's own smooth motion was being
     * overwritten every tick by a value computed a round trip ago. The client now flies the route itself, so the normal
     * case is that this method looks, agrees, and does nothing at all.
     *
     * <p>{@code gap} is how far from the route the player currently is. Past the leash, or with no client driving at
     * all, the server takes over and pushes, which is both the correction and the fallback.
     *
     * @return true when the player is wedged against something and the dash should give up
     */
    private static boolean advance(ServerPlayer player, Dash dash, Vec3 step, double gap)
    {
        Vec3 before = player.position();
        double moved = dash.lastPos == null ? 0.0D : before.distanceTo(dash.lastPos);
        dash.lastPos = before;
        dash.travelled += moved;
        player.fallDistance = 0.0F;

        if (gap > LEASH_DISTANCE)
        {
            // Well off the route. Clamped so the correction is a firm pull rather than a teleport, and marked so it
            // actually reaches the client, because at this point the client's own idea is the thing that is wrong.
            Vec3 pull = step.scale(Math.min(1.0D, SPEED * 1.6D / Math.max(1.0E-6D, gap)));
            player.setDeltaMovement(pull);
            player.hurtMarked = true;
        }

        // The first travelling tick is measured from a standing start and always reads as no progress, so it is never
        // counted against the player.
        if (dash.ticks <= dash.windup + 1)
            return false;
        // Stalled against terrain, and only that. Not merely behind the curve, which is an ordinary consequence of the
        // client's clock running a little behind the server's.
        if (moved < WALL_PROGRESS)
            dash.stuckTicks++;
        else
            dash.stuckTicks = 0;
        return dash.stuckTicks >= WALL_TICKS_BEFORE_ABORT;
    }

    // Whether these two are actually moving toward each other, as opposed to one chasing the other.
    private static boolean closing(ServerPlayer player, LivingEntity target)
    {
        Vec3 theirHeading = heading(target);
        if (theirHeading == null)
            return false;
        Vec3 between = target.position().subtract(player.position());
        if (between.lengthSqr() < 1.0E-6D)
            return false;
        return theirHeading.dot(between.normalize()) < -0.5D;
    }

    /**
     * What a dash that reaches its target does to them: a hit, and a shove along the line the dash came in on.
     *
     * <p>The damage is the dasher's own melee weight rather than a number of ours, so a dash stays worth what the
     * player is worth and does not need retuning every time the stat curve moves. It is dealt through the ordinary
     * player attack source, so DragonMineZ's own damage pipeline scales and resists it exactly as it would a punch.
     */
    private static void impact(ServerPlayer player, LivingEntity target, boolean stopping)
    {
        try
        {
            float damage = (float) Math.max(MIN_IMPACT_DAMAGE, player.getAttributeValue(Attributes.ATTACK_DAMAGE));
            // Dealt first, and the result decides the rest. Anything that refuses the hit, the PvP toggle, a region
            // rule, a protection handler, does so by cancelling the damage event, and a shove landing anyway would be
            // this system quietly walking around the answer it was just given.
            boolean landed = target.hurt(player.damageSources().playerAttack(player), damage);

            Vec3 away = target.position().subtract(player.position());
            Vec3 direction = away.lengthSqr() < 1.0E-6D ? player.getLookAngle() : away.normalize();
            // A standing quest giver or shopkeeper is hit like anything else, but never shoved: knocked once it has no
            // way back to where it stood, and a player could strand a master (Old Kai) and lock a progression path.
            // Real enemies and combat mobs are not in here, so dashing into something you fight still throws it.
            if (landed && !CombatBystanders.isProtected(target))
            {
                target.setDeltaMovement(direction.scale(IMPACT_KNOCKBACK).add(0.0D, IMPACT_LIFT, 0.0D));
                target.hurtMarked = true;
                if (target instanceof ServerPlayer hitPlayer)
                {
                    hitPlayer.connection.send(
                            new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(hitPlayer));
                }
            }

            // Only a dash that ENDS here loses its momentum. A flank is still on its way somewhere, and rewriting its
            // velocity mid route would fight the curve it is flying and stall it in front of the target, which is the
            // exact position that mode exists to avoid.
            if (stopping)
            {
                player.setDeltaMovement(direction.scale(0.35D));
                player.hurtMarked = true;
            }

            if (landed && player.level() instanceof ServerLevel level)
            {
                level.playSound(null, target.getX(), target.getY(), target.getZ(),
                        net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_STRONG,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.2F, 0.85F);
            }
        }
        catch (Throwable ignored)
        {
            // Contact is the payoff, not the mechanism. A failure here must still let the dash end cleanly.
        }
    }

    private static void end(Iterator<Map.Entry<UUID, Dash>> it, UUID id, ServerPlayer player, Dash dash,
            boolean charge)
    {
        it.remove();
        if (charge)
        {
            COOLDOWN.put(id, COOLDOWN_TICKS);
            // Mirrored into the effect bar, where DMZ already puts its own dash and ki blast cooldowns. The cooldown
            // itself lives in the map above; this is only how the player reads it.
            DashCooldownEffect.apply(player, COOLDOWN_TICKS);
        }
        // Bleed off rather than stopping dead, so a dash ends in a glide instead of a wall.
        Vec3 drift = dash.heading == null ? Vec3.ZERO : dash.heading.scale(0.15D);
        player.setDeltaMovement(drift);
        player.hurtMarked = true;
        // Told explicitly rather than left to the client side safety expiry, or the aura stays laid over for the
        // remainder of the dash's nominal duration after the player has already stopped.
        PacketDashState.broadcastEnd(player);
    }

    private static LivingEntity targetOf(ServerPlayer player, Dash dash)
    {
        if (dash.targetId <= 0)
            return null;
        Entity e = player.level().getEntity(dash.targetId);
        return e instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    private static Vec3 aimAt(ServerPlayer player, LivingEntity target)
    {
        Vec3 to = target.getEyePosition().subtract(player.getEyePosition());
        return to.lengthSqr() < 1.0E-6D ? player.getLookAngle() : to.normalize();
    }

    /** Whether this player is mid dash, in either phase. */
    public static boolean isDashing(Entity entity)
    {
        return entity != null && ACTIVE.containsKey(entity.getUUID());
    }

    /**
     * Whether this player is COMMITTED to a dash and has not started travelling yet. This is the window a mutual clash
     * is detected in, so it is exposed separately from {@link #isDashing}.
     */
    public static boolean isWindingUp(Entity entity)
    {
        Dash dash = entity == null ? null : ACTIVE.get(entity.getUUID());
        return dash != null && !dash.travelling;
    }

    /** Current dash heading, or null if not dashing. Unit length. */
    public static Vec3 heading(Entity entity)
    {
        Dash dash = entity == null ? null : ACTIVE.get(entity.getUUID());
        return dash == null ? null : dash.heading;
    }

    /**
     * Longest this dash can possibly run, in ticks. Sent to clients as the safety expiry on the aura and the trail: a
     * flat {@link #MAX_DASH_TICKS} left a short travel burst decorated for three seconds after it had already stopped,
     * whenever the end packet was the thing that went missing.
     */
    public static int plannedDuration(Entity entity)
    {
        Dash dash = entity == null ? null : ACTIVE.get(entity.getUUID());
        if (dash == null)
            return 0;
        int travel = dash.targetId > 0 ? dash.travelTicks : (int) Math.ceil(dash.maxTravel / SPEED);
        // A few ticks of slack on top. This is the fallback for a LOST end packet, so it must never expire while the
        // dash is genuinely still running.
        return Math.min(MAX_DASH_TICKS, dash.windup + travel + 4);
    }

    /** The entity this dash is aimed at, or 0. */
    public static int targetId(Entity entity)
    {
        Dash dash = entity == null ? null : ACTIVE.get(entity.getUUID());
        return dash == null ? 0 : dash.targetId;
    }

    /**
     * Stop a dash now, without charging cooldown. Used when something else takes over the player, for example a clash
     * seizing both dashers.
     */
    public static void cancel(Entity entity)
    {
        if (entity == null)
            return;
        ACTIVE.remove(entity.getUUID());
        if (entity instanceof ServerPlayer sp)
            PacketDashState.broadcastEnd(sp);
    }

    /** Put a player on dash cooldown, for callers that end a dash themselves. */
    public static void startCooldown(Entity entity, int ticks)
    {
        if (entity != null)
            COOLDOWN.put(entity.getUUID(), Math.max(0, ticks));
    }

    public static void clear()
    {
        ACTIVE.clear();
        COOLDOWN.clear();
    }
}
