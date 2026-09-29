package net.shurui.dev.sdu.combat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.dragonminez.common.init.MainEffects;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Status;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;

/**
 * Frees a player whose DragonMineZ strike lock has latched on with nothing left to clear it.
 *
 * <h2>The bug this exists for</h2>
 *
 * <p>Reported for a long time as "I cannot damage anything", and finally pinned by the hit-refusal instrument
 * that once lived here: across 11 live captures, every refusal came from DMZ's own gate and NONE from any of the
 * suite's nine. Four of them carried the signature this class targets, {@code strikeLocked=true} with
 * {@code stunMobEffect=ABSENT}.
 *
 * <p>{@code StrikeAttackHandler} sets {@code strikeLocked} on BOTH attacker and victim when a strike technique
 * fires, and clears it in exactly three places: {@code endStrike}, {@code onPlayerLoggedOut} (the attacker's,
 * which also clears the victim's) and {@code onPlayerLoggedIn}. The flag carries no expiry of its own. So if
 * {@code endStrike} never runs for a pairing, and it need not, {@code clearVictimStrikeLock} resolves the victim
 * out of the online player list and otherwise falls back to a possibly stale entity reference, the victim is
 * left locked with nothing that will ever clear it short of a relog.
 *
 * <p>What makes it invisible rather than merely annoying: {@code CombatAttackRequestC2S} refuses the punch
 * server-side whenever {@code Status.isStunned()} is true, BEFORE any Forge event is fired. No attack, hurt or
 * damage event is raised, so no protection, region, guild or jail gate ever runs, and the log stays silent. The
 * player just cannot hit anything, forever, and every gate looks innocent because every gate IS innocent.
 *
 * <h2>The stuck signature, and why it cannot false-positive</h2>
 *
 * <p>Two conditions held together, every tick:
 * <ol>
 *   <li>{@code isStrikeLocked()} is true, and</li>
 *   <li>the {@code dragonminez:stun} MOB EFFECT is absent.</li>
 * </ol>
 *
 * <p>The second is what separates a stuck latch from an ordinary stun. A real stun is the mob effect, and a mob
 * effect has a duration that runs down on its own; five of the eleven captures were exactly that, with 1.5 to 3
 * seconds left to go, and they are none of our business. A strike lock with no effect behind it has no clock at
 * all.
 *
 * <p>The dwell time is derived, not guessed. Every strike duration is hardcoded in
 * {@code PredefinedTechniques.registerStrike} and the longest of the eight is {@code dragon_fist} at 50 ticks,
 * with {@code spawnStrike} flooring the value at 20. {@value #STUCK_TICKS} ticks is therefore twice the longest
 * lock DMZ can legitimately hold, which no single strike can reach. (Do not look for these in
 * {@code techniques.json}: that file carries cost and {@code cooldownTicks} only.) The counter resets the
 * instant the flag drops, so back-to-back strikes re-arm it rather than accumulating.
 *
 * <h2>It clears one flag, deliberately</h2>
 *
 * <p>Only {@code strikeLocked}. Not {@code stunEffect}, because DMZ's {@code TickHandler} recomputes that every
 * tick as {@code hasEffect(STUN) || isStrikeLocked()} and writes it back BOTH ways, so it falls to false by
 * itself on the next tick once the lock is gone. Not {@code knockedDown} either: that is a separate mechanic
 * which already has its own expiry in {@code TickHandler.clearExpiredKnockdown}, so forcing it would cut a
 * legitimate knockdown short. {@code ShardSync.clearCombatLockOnArrival} does clear all three, but an arrival is
 * a different situation, where nothing in flight is worth preserving.
 *
 * <p>This is a complement to that arrival clear, not a replacement. That one handles a lock carried in over the
 * vault and only fires when the vault actually delivered a payload; this one handles getting stuck mid-session,
 * which nothing covered. The live evidence needed both: one player was seen still locked eleven minutes and one
 * shard hop after it began.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class StrikeLockWatchdog {

    private StrikeLockWatchdog() {
    }

    /**
     * Consecutive ticks of the stuck signature before the lock is forced off. Twice {@code dragon_fist}'s 50,
     * the longest strike DMZ registers, so a legitimate strike cannot reach it. Five seconds is also short
     * enough that a player who hits this barely notices, which matters because the alternative today is being
     * unable to fight until they relog.
     */
    private static final int STUCK_TICKS = 100;

    /** Player uuid -> consecutive ticks matching the stuck signature. Bounded by the online player count. */
    private static final Map<UUID, Integer> LOCKED_FOR = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        try {
            tick(player);
        } catch (Throwable t) {
            // Never let a watchdog throw on the combat path. Worst case it does not fire and the player relogs,
            // which is exactly today's behaviour.
            LOCKED_FOR.remove(player.getUUID());
            DmzNpc.LOGGER.debug("[{}] strike-lock watchdog failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    private static void tick(ServerPlayer player) {
        UUID id = player.getUUID();
        StatsData data = DmzForms.stats(player);
        if (data == null) {
            LOCKED_FOR.remove(id);
            return;
        }
        Status status = data.getStatus();
        if (!status.isStrikeLocked()) {
            LOCKED_FOR.remove(id); // not locked at all: the common case, and the counter must not carry over
            return;
        }
        if (player.hasEffect(MainEffects.STUN.get())) {
            // A real stun is running. It has a duration and will end on its own, so this is not our case and
            // the dwell must not accumulate underneath it.
            LOCKED_FOR.remove(id);
            return;
        }
        int ticks = LOCKED_FOR.merge(id, 1, Integer::sum);
        if (ticks < STUCK_TICKS) {
            return;
        }
        LOCKED_FOR.remove(id);
        status.setStrikeLocked(false);
        // Push DMZ's own sync so the client's cached StatsData agrees, else it keeps gating its own left click
        // off the stale stunned copy it is holding.
        NetworkHandler.sendToPlayer(new StatsSyncS2C(player), player);
        DmzNpc.LOGGER.info("[{}] Cleared a stuck DMZ strike lock on {} after {} ticks with no stun effect behind "
                        + "it; they could not have damaged anything until this.",
                DmzNpc.MODID, player.getGameProfile().getName(), ticks);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LOCKED_FOR.remove(event.getEntity().getUUID());
    }
}
