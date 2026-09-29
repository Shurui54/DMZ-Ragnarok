package net.shurui.dev.sdu.event;

import com.dragonminez.common.events.DMZEvent.TPGainEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.block.GravityChamberBlockEntity;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.registry.ModBlocks;

/**
 * Forge-bus handlers for the two data-carrying blocks:
 *
 * <ul>
 *   <li><b>Level Barrier (Feature 6)</b>: {@link BlockEvent.BreakEvent} cancels the break, and
 *       {@link PlayerEvent.BreakSpeed} freezes mining progress, for any non-op/non-creative player below
 *       the barrier's required DMZ level, so it can't be punched through until they qualify.</li>
 *   <li><b>Gravity Chamber (Feature 5)</b>: {@link TPGainEvent} at {@link EventPriority#LOW} multiplies
 *       the gain for a player training inside a chamber and shares a fraction with everyone else inside
 *       the SAME chamber, with a {@link ThreadLocal} reentrancy guard so shared awards don't re-trigger.</li>
 * </ul>
 * Everything here is server-side; DMZ reads are wrapped in try/catch with safe defaults.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class BlockFeatureEvents {

    /** Reentrancy guard: true while we are awarding shared-pool TP, so those awards don't recurse. */
    private static final ThreadLocal<Boolean> IS_SHARING_TP = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private BlockFeatureEvents() {
    }

    /**
     * The Level Barrier is a force field: only a creative-mode player can break it. Survival players (and
     * everyone else) have the break cancelled. Level-gating is now a walk-through phase gate on collision
     * (see {@code BarrierBlock#getCollisionShape}), not a break gate.
     */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getState().getBlock() != ModBlocks.LEVEL_BARRIER.get()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || player.level().isClientSide) {
            return;
        }
        if (!canBreakBarrier(player)) {
            event.setCanceled(true);
        }
    }

    /** Freeze mining progress for non-creative players so the force field shows no break animation. */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (event.getState().getBlock() != ModBlocks.LEVEL_BARRIER.get()) {
            return;
        }
        Player player = event.getEntity();
        if (player == null) {
            return;
        }
        if (!canBreakBarrier(player)) {
            event.setCanceled(true);
        }
    }

    /** Only creative-mode players may break the barrier. */
    private static boolean canBreakBarrier(Player player) {
        try {
            return player.getAbilities().instabuild;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Multiply organic TP gains for a player inside a chamber, then share a fraction of the final gain
     * with everyone else inside the SAME chamber. Runs at LOW so it sees DMZ's already-rewritten value.
     * The shared awards are guarded against recursion via {@link #IS_SHARING_TP}.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onTpGain(TPGainEvent event) {
        // Skip the awards we ourselves triggered when sharing the pool.
        if (Boolean.TRUE.equals(IS_SHARING_TP.get())) {
            return;
        }
        try {
            Player player = event.getPlayer();
            if (player == null || player.level().isClientSide || !(player instanceof ServerPlayer sp)) {
                return;
            }
            if (!(sp.level() instanceof ServerLevel serverLevel)) {
                return;
            }

            // Find the chamber this player is standing in, restricted to this player's level. If several
            // chambers overlap, chamberContaining() returns the FIRST match and we use only its config.
            GravityChamberBlockEntity chamber = chamberContaining(serverLevel, sp);
            if (chamber == null) {
                return;
            }

            // MULTIPLIER on the organic gain, taken from THIS chamber's per-block config (not the global).
            double mult = chamber.getMultiplier();
            int boosted = event.getTpGain();
            if (mult != 1.0) {
                // clamp to the int range: a 2e9 saga reward * mult wraps negative and drains TP otherwise
                boosted = net.shurui.dev.sdu.util.TpMath.scaleGain(boosted, mult);
                event.setTpGain(boosted);
            }

            // SHARED POOL: award a fraction of the post-multiplier gain to every OTHER player in the SAME
            // chamber area, using THIS chamber's per-block share fraction. Guard against recursion so those
            // awards don't re-enter this handler.
            double frac = chamber.getShareFraction();
            if (frac > 0.0 && boosted > 0) {
                float share = (float) (boosted * frac);
                if (share > 0f) {
                    shareToOthers(serverLevel, chamber, sp, share);
                }
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Gravity Chamber TP handling failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    /** The active chamber (in this level) whose area contains the player, or null. */
    private static GravityChamberBlockEntity chamberContaining(ServerLevel level, ServerPlayer player) {
        for (GravityChamberBlockEntity chamber : GravityChamberBlockEntity.active()) {
            if (chamber == null || chamber.isRemoved() || chamber.getLevel() != level) {
                continue;
            }
            if (chamber.contains(player.position())) {
                return chamber;
            }
        }
        return null;
    }

    /** Directly award {@code share} TP to every other player inside the same chamber area. */
    private static void shareToOthers(ServerLevel level, GravityChamberBlockEntity chamber,
                                      ServerPlayer gainer, float share) {
        IS_SHARING_TP.set(Boolean.TRUE);
        try {
            for (ServerPlayer other : level.players()) {
                if (other == gainer || !other.isAlive()) {
                    continue;
                }
                if (!chamber.contains(other.position())) {
                    continue;
                }
                try {
                    var stats = DmzForms.stats(other);
                    if (stats != null && stats.getResources() != null) {
                        stats.getResources().addTrainingPoints(share);
                    }
                } catch (Throwable ignored) {
                    // Skip this recipient; never let a shared award abort the original gain.
                }
            }
        } finally {
            IS_SHARING_TP.set(Boolean.FALSE);
        }
    }
}
