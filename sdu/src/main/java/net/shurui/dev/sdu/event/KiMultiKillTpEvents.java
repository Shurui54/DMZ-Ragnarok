package net.shurui.dev.sdu.event;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import com.dragonminez.common.init.entities.ki.AbstractKiProjectile;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ResourceSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Multi-kill TP division for ki attacks.
 *
 * <p>Owner request: when a single ki attack (one projectile) kills several enemies, the TP the killer earns from
 * those kills should be divided by the number of enemies killed, so a multi-kill blast is not worth many single
 * kills. On top of that, the divided amount is multiplied by 1.5 as a small multi-kill bonus. A blast that kills
 * exactly ONE enemy is left completely untouched (no division, no 1.5); the 1.5 only applies when {@code N >= 2}.
 *
 * <h2>The rule (per projectile, over its whole lifetime)</h2>
 *
 * <p>Every ki projectile is one "blast", identified by its entity id. We count {@code N}, the enemies it kills over
 * its whole life, and the real TP the killer actually gained from those kills, {@code sumTp}. When the projectile is
 * removed from the world we settle: the killer should keep {@code sumTp * 1.5 / N} for {@code N >= 2} (and exactly
 * {@code sumTp} for {@code N == 1}), so we subtract the difference back off. Counting over the projectile's lifetime
 * (rather than per tick) is the reading the owner would recognise: a detonation kills everyone in one tick, but a
 * beam or a charging orb's area pulse can kill across several ticks, and all of those are "kills by that one blast".
 *
 * <h2>Why measure the killer's TP delta instead of the TP-gain event</h2>
 *
 * <p>DMZ grants kill TP inside {@code TPGainEvents.onEntityDeath} at NORMAL priority via
 * {@code Resources.addTrainingPoints}, which fires {@code DMZEvent.TPGainEvent}. Several suite handlers already sit on
 * that event at LOWEST (token buff, shadow-dummy suppression, the npc-region anti-farm scaler) and DMZ's own HIGH
 * handler re-applies the story multiplier and shares with the party. Reading the event value would tie us to the
 * order of those LOWEST handlers. Instead we bracket the whole {@link LivingDeathEvent}: read the killer's training
 * points at HIGHEST (before DMZ's grant) and again at LOWEST (after DMZ's grant and every TP-gain modifier), and the
 * difference is exactly the TP the killer really banked from this kill, whatever those other systems did. We compose
 * with all of them for free and break none of them.
 *
 * <p>We only ever REDUCE the killer's own training points at settle. Party TP sharing happens inside DMZ's HIGH
 * handler at grant time and is left as-is (each member keeps their configured share of each kill); the owner's
 * request is about the killer's own TP.
 *
 * <p>Server side only. State is a small per-projectile map drained the moment a projectile is removed, with a
 * belt-and-braces age cap and a clear on server stop, so nothing leaks.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class KiMultiKillTpEvents {

    /** Multi-kill bonus factor applied to the divided TP when a blast kills two or more enemies. */
    private static final double MULTI_KILL_BONUS = 1.5;

    /** Hard safety cap: drop a buffer whose projectile somehow never reports removed (ms). */
    private static final long MAX_AGE_MS = 60_000L;

    /** Per-projectile accumulators, keyed by projectile entity id. Server thread only. */
    private static final Map<Integer, Buffer> BUFFERS = new HashMap<>();

    /** Set at the HEAD of a qualifying ki kill's death dispatch, read and cleared at its tail. Server thread only. */
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    private KiMultiKillTpEvents() {
    }

    /** Records the killer's TP before DMZ grants the kill TP. Runs before DMZ's NORMAL-priority death handler. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeathBefore(LivingDeathEvent event) {
        PENDING.remove();
        try {
            Entity dead = event.getEntity();
            if (dead == null || dead.level().isClientSide()) {
                return;
            }
            // Mirror DMZ's kill-TP target set (Monster/Animal/FlyingMob/Mob and Player): anything that is a Mob or a
            // Player. Other living things (armour stands, etc.) earn no kill TP in DMZ, so they cannot multi-kill.
            if (!(dead instanceof Mob) && !(dead instanceof Player)) {
                return;
            }
            DamageSource source = event.getSource();
            if (!(source.getDirectEntity() instanceof AbstractKiProjectile projectile)) {
                return;
            }
            if (!(source.getEntity() instanceof ServerPlayer killer)) {
                return;
            }
            // The raw Capability cast erases the generic, so StatsProvider.get(...) is a raw optional here: resolve
            // and cast to StatsData explicitly, exactly as DMZ's own code does.
            StatsData data = (StatsData) StatsProvider.get((Capability) StatsCapability.INSTANCE, (Entity) killer)
                    .orElse(null);
            if (data != null) {
                PENDING.set(new Pending(projectile, killer, data, data.getResources().getTrainingPoints()));
            }
        } catch (Throwable t) {
            PENDING.remove();
        }
    }

    /**
     * Reads the killer's TP after DMZ's grant and every TP-gain modifier, and folds the difference into this
     * projectile's buffer. Receives canceled deaths only to guarantee the ThreadLocal is always cleared; a canceled
     * death is not a kill and is not counted.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onDeathAfter(LivingDeathEvent event) {
        Pending pending = PENDING.get();
        PENDING.remove();
        if (pending == null || event.isCanceled()) {
            return;
        }
        try {
            float gained = pending.data.getResources().getTrainingPoints() - pending.beforeTp;
            Buffer buffer = BUFFERS.computeIfAbsent(pending.projectile.getId(),
                    id -> new Buffer(pending.killer.getUUID(), pending.killer.getServer()));
            buffer.projectile = pending.projectile;
            buffer.kills++;
            if (gained > 0.0f) {
                buffer.sumTp += gained;
            }
        } catch (Throwable ignored) {
            // Never let a TP-bookkeeping failure disturb the death pipeline.
        }
    }

    /** Settles every buffer whose projectile has been removed (or has aged out). Cheap: the map is usually empty. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || BUFFERS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Buffer> it = BUFFERS.values().iterator();
        while (it.hasNext()) {
            Buffer buffer = it.next();
            boolean removed = buffer.projectile == null || buffer.projectile.isRemoved();
            boolean expired = now - buffer.createdMs > MAX_AGE_MS;
            if (!removed && !expired) {
                continue;
            }
            settle(buffer);
            it.remove();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        BUFFERS.clear();
    }

    /** Take the multi-kill excess back off the killer and resync. Single-kill blasts are left exactly as DMZ paid. */
    private static void settle(Buffer buffer) {
        try {
            if (buffer.kills < 2 || buffer.sumTp <= 0.0f) {
                return;
            }
            double desired = buffer.sumTp * MULTI_KILL_BONUS / buffer.kills;
            double excess = buffer.sumTp - desired;
            if (excess <= 0.0) {
                return;
            }
            MinecraftServer server = buffer.server;
            if (server == null) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(buffer.killer);
            if (player == null) {
                return;
            }
            StatsData data = (StatsData) StatsProvider.get((Capability) StatsCapability.INSTANCE, (Entity) player)
                    .orElse(null);
            if (data == null) {
                return;
            }
            float current = data.getResources().getTrainingPoints();
            data.getResources().setTrainingPoints((float) Math.max(0.0, current - excess));
            NetworkHandler.sendToTrackingEntityAndSelf(new ResourceSyncS2C(player), (Entity) player);
        } catch (Throwable ignored) {
            // Best-effort correction; never crash the tick loop.
        }
    }

    /** Per-death carry between the HIGHEST and LOWEST handlers. */
    private static final class Pending {
        private final AbstractKiProjectile projectile;
        private final ServerPlayer killer;
        private final StatsData data;
        private final float beforeTp;

        private Pending(AbstractKiProjectile projectile, ServerPlayer killer, StatsData data, float beforeTp) {
            this.projectile = projectile;
            this.killer = killer;
            this.data = data;
            this.beforeTp = beforeTp;
        }
    }

    /** Per-projectile accumulator. */
    private static final class Buffer {
        private final UUID killer;
        private final MinecraftServer server;
        private final long createdMs = System.currentTimeMillis();
        private AbstractKiProjectile projectile;
        private float sumTp;
        private int kills;

        private Buffer(UUID killer, MinecraftServer server) {
            this.killer = killer;
            this.server = server;
        }
    }
}
