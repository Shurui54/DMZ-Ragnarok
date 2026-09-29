package net.shurui.shuruisutilities.client.combat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.shurui.shuruisutilities.core.SUConfig;

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.character.Status;
import com.dragonminez.common.stats.skills.Skills;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Decides who is drawn as flying, and who is drawn as flying FAST, for every player this client can see.
 *
 * <h2>Two states, not one</h2>
 * <ul>
 *   <li><b>Flying</b> earns the line trail. Any player moving under DragonMineZ's flight skill gets it: no charging, no
 *       speed requirement beyond enough to make a line rather than a smear, no aura.</li>
 *   <li><b>Flying fast</b> additionally lays the aura over the screen, and supplies an aura to lay over for a player
 *       who never powered up. This one keeps the speed threshold, because a laid over aura is a statement about
 *       velocity and looks wrong on someone drifting.</li>
 * </ul>
 *
 * <h2>Why the verdict is not DMZ's own method</h2>
 * The obvious call, {@code FlySkillEvent.isFlyingFast}, cannot answer for anyone but the local player. Handed a remote
 * one it reads {@code getDeltaMovement}, and the server sends other players' POSITIONS but not their motion, so on this
 * side that vector is very nearly always zero. Everything downstream of it was therefore dead for onlookers, which left
 * the aura flag as the only part of the verdict that survived the trip: hence a flier had to be powered up before
 * anybody else saw a thing.
 *
 * <p>So the flight verdict is read from the flight skill's active flag in the stats capability, which DMZ syncs whole
 * to every client tracking the player, and the speed is measured from the OBSERVED position change. Both are answers an
 * onlooker can get, so everyone watching agrees with the flier's own screen.
 *
 * <p>Heading likewise comes from observed position change rather than from a DMZ API. It needs no API and therefore
 * cannot break when DMZ moves one.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class FastFlightAura
{
    private FastFlightAura() {}

    // How long the state is kept alive between refreshes. Long enough to cover a dropped tick, short enough that the
    // aura rights itself promptly when someone stops.
    private static final int HOLD_TICKS = 6;

    // Below this the player is not really going anywhere, and a trail drawn from a hovering player is a smear rather
    // than a streak. Only used to decide whether to DRAW, never to decide whether they are flying.
    private static final double MIN_TRAIL_SPEED = 0.12D;

    /**
     * Squared speed, in blocks per tick, at or below which a player is not moving fast enough to count as fast flight.
     *
     * <p>0.55 blocks per tick, squared. Taken from DragonMineZ's own fast flight threshold, which it applies to remote
     * players in {@code FlySkillEvent.isFlyingFast}, so the local player is now judged by the same number everyone
     * else already was.
     */
    private static final double FAST_FLIGHT_SPEED_SQR = 0.3025D;

    // DragonMineZ's flight skill, keyed by the same name its own flight code looks it up under.
    private static final String FLY_SKILL = "fly";

    private static final Map<Integer, Vec3> LAST_POS = new HashMap<>();
    // Who WE put into each state, so nothing else's state is ever torn down by this class. LIT is the laid over aura,
    // FLYING is the trail. LIT is always a subset of FLYING.
    private static final Set<Integer> LIT = new HashSet<>();
    private static final Set<Integer> FLYING = new HashSet<>();

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
        {
            LAST_POS.clear();
            LIT.clear();
            FLYING.clear();
            return;
        }
        for (Player p : mc.level.players())
        {
            int id = p.getId();
            Vec3 now = p.position();
            Vec3 prev = LAST_POS.put(id, now);
            if (prev == null)
                continue;
            // A real dash owns its own state and must not be cut short by this test disagreeing with it.
            if (DashAuraState.isRealDash(id))
                continue;

            Vec3 delta = now.subtract(prev);
            // Too slow to streak. A trail drawn from someone hovering in place is a smear rather than a line, so this
            // gates the DRAWING for both states, and neither state is entered.
            if (delta.length() < MIN_TRAIL_SPEED)
            {
                clear(id);
                continue;
            }
            boolean flying = isFlying(p);
            if (!flying)
            {
                clear(id);
                continue;
            }
            // Flight alone earns the trail. Refreshed every tick, so the hold keeps expiring forward for as long as
            // they keep moving and the streak ends a few ticks after they stop rather than the instant they do.
            DashAuraState.beginFlight(id, HOLD_TICKS);
            FLYING.add(id);
            // The laid-over aura is opt-out: with it off nobody gets the fast-flight aura and any that is already up is
            // dropped, but the trail above still runs. This gates ONLY our laid-over aura, never DMZ's own powered-up
            // one, which we do not set.
            if (!SUConfig.flightAura || !isFast(p, delta))
            {
                unlight(id);
                continue;
            }
            LIT.add(id);
            // Refreshed every tick rather than set once, so the heading tracks a turning flight.
            DashAuraState.beginFastFlight(id, delta.normalize(), HOLD_TICKS);
        }
        // Anyone who left view stops being tracked, so the maps cannot grow across a session.
        LAST_POS.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        LIT.removeIf(id -> mc.level.getEntity(id) == null);
        FLYING.removeIf(id -> mc.level.getEntity(id) == null);
    }

    /**
     * Whether this player is airborne under DragonMineZ's flight skill.
     *
     * <p>Read from the flight skill's own active flag in the synced stats rather than from any client side flight
     * handler, and that is the whole point: the stats capability is synced to EVERY client that can see the player, so
     * an onlooker gets the same answer the flier does. {@code FlySkillEvent.isFlyingFast} cannot do that. For a remote
     * player it falls back to {@code getDeltaMovement}, which on this side is very nearly always zero, because the
     * server sends other players' positions but not their motion. That single fact is why a flier used to have to power
     * up before anyone else could see anything: the aura flag was the only part of the verdict that survived the trip.
     */
    private static boolean isFlying(Player player)
    {
        try
        {
            // Off the ground first. This is what stops a runner being treated as a flier.
            if (player.onGround())
                return false;
            StatsData stats = StatsProvider.get(StatsCapability.INSTANCE, player).resolve().orElse(null);
            if (stats == null)
                return false;
            Skills skills = stats.getSkills();
            return skills != null && skills.isSkillActive(FLY_SKILL);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Whether a flying player is going fast enough, and in the right flight mode, to earn the laid over aura.
     *
     * <p>Speed is measured from the OBSERVED position change rather than {@code getDeltaMovement}, for the reason
     * above: a remote player's motion vector is zero on this side, their position delta is not. The threshold is
     * DragonMineZ's own, so the same number means the same thing for everyone.
     *
     * <p>Combat flight is excluded because DMZ excludes it: that mode is the hovering, precise one and never goes fast,
     * so a player nudged along quickly in it is not fast flying. An aura that is already up is accepted in its place,
     * since a powered up player moving at that speed reads as fast whatever mode they left the flight toggle in.
     */
    private static boolean isFast(Player player, Vec3 delta)
    {
        try
        {
            // Genuinely fast, by the same threshold DragonMineZ applies to remote players. Without it a sprint jump
            // clears the ground test for a few ticks and the aura strobes on and off with every stride. A sprint jump
            // peaks well under this; real fast flight is well over.
            if (delta.lengthSqr() <= FAST_FLIGHT_SPEED_SQR)
                return false;
            StatsData stats = StatsProvider.get(StatsCapability.INSTANCE, player).resolve().orElse(null);
            Status status = stats == null ? null : stats.getStatus();
            if (status == null)
                return false;
            if (status.isAuraActive() || status.isPermanentAura())
                return true;
            return status.getFlightMode() != Status.FLIGHT_COMBAT;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    // Stop treating this player as flying at all. Each half of the state is only cleared if WE were the ones who set
    // it, so a real dash in progress is never cut short.
    private static void clear(int id)
    {
        unlight(id);
        if (FLYING.remove(id))
            DashAuraState.endFlight(id);
    }

    // Drop the laid over aura but leave the trail alone: this is a flier who has slowed below fast flight, not one who
    // has landed.
    private static void unlight(int id)
    {
        if (LIT.remove(id) && !DashAuraState.isRealDash(id))
            DashAuraState.end(id);
    }
}
