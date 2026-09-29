package net.shurui.shuruisutilities.npcregion;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.shurui.shuruisutilities.guilds.integration.DmzBridge;
import net.shurui.shuruisutilities.util.events.ServerEventHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import com.dragonminez.common.events.DMZEvent.TPGainEvent;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// anti-farm scaling for DMZ kill TP. two independent multipliers, combined multiplicatively and clamped to
// [0,1] (NEVER amplifies TP, only reduces):
//
// Feature A, multi-kill diminishing returns: per-player sliding window of recent qualifying kills. each
// extra kill in the window lowers the multiplier by FEATURE_A_STEP with a high floor of FEATURE_A_FLOOR.
// recovers automatically as timestamps age out.
//
// Feature B, per-region TP falloff by level: if the dead mob is in an NpcRegion with tpFalloffLevel set, a
// killer whose DMZ level exceeds that threshold earns linearly less TP, down to a 20% floor over
// tpFalloffRange levels.
//
// how it's applied: DMZ grants kill TP on LivingDeathEvent at NORMAL priority and every positive grant fires
// the cancelable TPGainEvent, but that event carries no kill/source info. so:
//  - LivingDeathEvent at HIGHEST (before DMZ's NORMAL grant) computes the per-kill multiplier for a real mob
//    kill and stashes it in PENDING_KILL_MULT.
//  - TPGainEvent at LOWEST (after SU's TpBoostState LOW handler and DMZ's HIGH handler) reads the ThreadLocal
//    and scales the final gain, so it stacks multiplicatively.
//  - LivingDeathEvent at LOWEST (after DMZ's NORMAL grant and NpcRegionRewards) clears the ThreadLocal so it
//    never leaks into the next kill.
// the whole death -> TP-grant chain is single-threaded and synchronous, so the ThreadLocal is reliable. any
// region-reward TP granted during the same death dispatch is scaled too, acceptable anti-farm behavior.
public class KillTpModifier extends ServerEventHandler
{
    /** Sliding-window length in milliseconds (~10 seconds). */
    public static final long FEATURE_A_WINDOW_MS = 10_000L;
    /** How much each recent kill (beyond the first) reduces the Feature A factor. */
    public static final double FEATURE_A_STEP = 0.08;
    /** Lower bound for the Feature A factor, no matter how many recent kills. */
    public static final double FEATURE_A_FLOOR = 0.5;

    /** Lower bound (20%) for the per-region level falloff factor. */
    public static final double FEATURE_B_FLOOR = 0.2;

    /** Per-killer recent-kill timestamps for Feature A. Server-thread only; transient (no persistence). */
    private static final Map<UUID, Deque<Long>> RECENT_KILLS = new HashMap<>();

    /** Multiplier stashed by the HIGHEST death handler and consumed by the LOWEST TPGainEvent handler. */
    private static final ThreadLocal<Double> PENDING_KILL_MULT = new ThreadLocal<>();

    /**
     * Runs before DMZ's NORMAL-priority kill-TP grant. For a qualifying mob kill by a ServerPlayer, computes
     * {@code factorA * factorB} (clamped to [0, 1]) and stashes it for the upcoming TPGainEvent(s).
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDeathStash(LivingDeathEvent event)
    {
        try
        {
            Entity dead = event.getEntity();
            if (dead.level().isClientSide)
                return;
            if (!isKillableMob(dead))
                return;
            if (!(event.getSource().getEntity() instanceof ServerPlayer killer))
                return;

            double factorA = featureA(killer.getUUID());
            double factorB = featureB(killer, dead);
            double mult = clamp(factorA * factorB, 0.0, 1.0);
            PENDING_KILL_MULT.set(mult);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[KillTp] death-stash failed: {}", t.toString());
        }
    }

    /**
     * Runs after DMZ's HIGH handler and SU's TpBoostState LOW handler, so it scales the final gain and stacks
     * multiplicatively. Only acts when a multiplier was stashed for the current kill. Never cancels the event.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTpGain(TPGainEvent event)
    {
        try
        {
            Double mult = PENDING_KILL_MULT.get();
            if (mult == null || mult == 1.0)
                return;
            // mult is clamped to [0, 1] so this only shrinks, but route through the shared clamp for consistency
            event.setTpGain(net.shurui.dev.sdu.util.TpMath.scaleGain(event.getTpGain(), mult));
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[KillTp] TP-gain scale failed: {}", t.toString());
        }
    }

    /**
     * Runs after DMZ's NORMAL grant and after {@link NpcRegionRewards}, clearing the stash so a multiplier can
     * never leak into an unrelated future TP grant.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeathClear(LivingDeathEvent event)
    {
        PENDING_KILL_MULT.remove();
    }

    /**
     * Prune the killer's window, count remaining recent kills (including this one), push this kill, and return
     * a gentle diminishing-returns factor with a high floor.
     */
    private static double featureA(UUID killer)
    {
        long now = System.currentTimeMillis();
        Deque<Long> window = RECENT_KILLS.computeIfAbsent(killer, k -> new ArrayDeque<>());
        while (!window.isEmpty() && now - window.peekFirst() > FEATURE_A_WINDOW_MS)
            window.pollFirst();
        int n = window.size() + 1; // include this kill
        window.addLast(now);
        return Math.max(FEATURE_A_FLOOR, 1.0 - FEATURE_A_STEP * (n - 1));
    }

    /**
     * Positional region attribution for any mob: find the first region containing the dead entity's position,
     * then scale by the killer's DMZ level relative to that region's threshold. Returns 1.0 when no region
     * matches or the region has the feature off.
     */
    private static double featureB(Player killer, Entity dead)
    {
        String dimId = dead.level().dimension().location().toString();
        double x = dead.getX();
        double y = dead.getY();
        double z = dead.getZ();

        NpcRegion region = null;
        // live(): an inactive eventOnly region applies no kill-TP falloff (it is not running).
        for (NpcRegion r : NpcRegionManager.instance().live())
        {
            if (r != null && r.contains(dimId, x, y, z))
            {
                region = r;
                break;
            }
        }
        return regionLevelFalloff(region, DmzBridge.level(killer));
    }

    /**
     * The per-region TP falloff factor in {@code [FEATURE_B_FLOOR, 1.0]} for a killer of the given DMZ level in
     * {@code region}. Returns 1.0 when the region is null or has the feature off ({@code tpFalloffLevel <= 0}), or
     * when the killer is at or below the threshold. Extracted so the Z orb rewards can apply the SAME falloff a
     * kill would (per the plan's {@code applyRegionTpFalloff}), sharing this one definition rather than copying it.
     */
    public static double regionLevelFalloff(NpcRegion region, int playerLevel)
    {
        if (region == null || region.tpFalloffLevel <= 0)
            return 1.0;
        if (playerLevel <= region.tpFalloffLevel)
            return 1.0;
        int over = playerLevel - region.tpFalloffLevel;
        double range = Math.max(1, region.tpFalloffRange);
        return clamp(1.0 - (over / range) * (1.0 - FEATURE_B_FLOOR), FEATURE_B_FLOOR, 1.0);
    }

    /** Mirror DMZ's killable set: real living mobs. Skips players and non-living junk. */
    private static boolean isKillableMob(Entity dead)
    {
        return dead instanceof Mob || (dead instanceof LivingEntity && !(dead instanceof Player));
    }

    /** Forget a player's transient anti-farm window on logout. */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        RECENT_KILLS.remove(event.getEntity().getUUID());
    }

    private static double clamp(double v, double lo, double hi)
    {
        return v < lo ? lo : Math.min(v, hi);
    }
}
