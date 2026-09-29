package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The small facade the rest of the suite calls to fire teleport animations. It knows nothing about the client or
 * the packet; it resolves what is equipped and hands off to the animation server in the Ragnarok Key (through {@code CosmeticHooks}).
 *
 * <h2>Same-server versus cross-shard</h2>
 * A same-server teleport plays both halves in the same tick through {@link #teleport}. A cross-shard hop cannot:
 * the player is gone from the origin milliseconds later, so the origin plays only the DEPART half and writes a
 * {@link CosmeticArrival} marker that the destination consumes on login. That split is why the arrive effect is
 * world-anchored, not entity-anchored: there is no entity left on the origin to anchor to.
 */
public final class CosmeticAnimations
{
    private CosmeticAnimations()
    {
    }

    /**
     * Subjects whose DEPART half has ALREADY been played by the timed-teleport layer (the warmup), so the paired
     * move must not replay it. Client-render offsets do not move the server entity, so the warmup's stand-still
     * check never false-cancels on the animation itself; a warmup that IS cancelled (the player really moved, or
     * took damage) clears this via {@link #cancelTimedDepart}. Cleared on the move in {@link #teleport} too.
     */
    private static final Set<UUID> DEPART_PREPLAYED = ConcurrentHashMap.newKeySet();

    /**
     * A same-server teleport: DEPART at the origin (unless the timed-teleport layer already played it), ARRIVE at the
     * destination, each to the observers near that end. Safe to call for any teleport; it is a no-op when the player
     * has nothing equipped in the triggered slots or animations are off.
     */
    public static void teleport(ServerPlayer player, ServerLevel fromLevel, Vec3 fromPos, ServerLevel toLevel,
            Vec3 toPos)
    {
        if (player == null)
            return;
        boolean departAlreadyPlayed = DEPART_PREPLAYED.remove(player.getUUID());
        if (!departAlreadyPlayed && fromLevel != null && fromPos != null)
            net.shurui.shuruisutilities.api.key.CosmeticHooks.get().play(CosmeticSlot.TP_DEPART, player.getUUID(), fromLevel, fromPos, player.getYRot());
        if (toLevel != null && toPos != null)
            net.shurui.shuruisutilities.api.key.CosmeticHooks.get().play(CosmeticSlot.TP_ARRIVE, player.getUUID(), toLevel, toPos, player.getYRot());
    }

    /**
     * The length in game ticks of the DEPART half of the player's equipped TELEPORT animation, or 0 when nothing is
     * equipped, the definition is invalid, or animations are off. Used by {@code TeleportHelper} to hold the actual
     * move until the depart animation has played, so the teleport is TIMED to the effect rather than snapping the
     * player away before it is seen. Zero means "teleport instantly, exactly as before".
     */
    public static int departDelayTicks(ServerPlayer player)
    {
        if (player == null || !net.shurui.shuruisutilities.api.key.CosmeticHooks.get().animationsActive())
            return 0;
        try
        {
            String id = CosmeticWardrobeData.get(player.getServer()).get(player.getUUID())
                    .in(CosmeticSlot.TP_DEPART.equipSlot());
            if (id == null || id.isBlank())
                return 0;
            CosmeticDef def = CosmeticCatalog.get(id);
            if (def == null || !def.enabled || def.animation == null || !def.animation.valid())
                return 0;
            return Math.max(0, def.animation.durationFor(CosmeticSlot.TP_DEPART));
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    /**
     * Play the DEPART half NOW at the origin and mark the subject so the paired move (which runs when the warmup
     * elapses) does not replay it. Called by {@code TeleportHelper} at the START of a timed teleport, so the player
     * performs the departing animation where they stand and only then is moved.
     */
    public static void beginTimedDepart(ServerPlayer player, ServerLevel fromLevel, Vec3 fromPos)
    {
        if (player == null)
            return;
        DEPART_PREPLAYED.add(player.getUUID());
        if (fromLevel != null && fromPos != null)
            net.shurui.shuruisutilities.api.key.CosmeticHooks.get().play(CosmeticSlot.TP_DEPART, player.getUUID(), fromLevel, fromPos, player.getYRot());
    }

    /**
     * Forget a pre-played DEPART for a subject whose timed teleport was cancelled or abandoned (they moved, took
     * damage, logged out), so their NEXT teleport plays its depart normally rather than being suppressed by a stale
     * flag. Safe to call for a subject that has none pending.
     */
    public static void cancelTimedDepart(UUID subject)
    {
        if (subject != null)
            DEPART_PREPLAYED.remove(subject);
    }

    /**
     * A cross-shard hop, called from the single chokepoint {@code ShardTransfer.connect} BEFORE the vault capture
     * so the marker travels. Writes a {@link CosmeticArrival} marker (which suppresses a spurious JOIN on the
     * destination and, when a TP_ARRIVE animation is equipped, carries its id) and plays the DEPART half here.
     *
     * <p>All hops are treated as player-initiated in this version: threading a "was this a balance move" flag
     * through every {@code connect} call site was judged higher risk than the minor oddity of a balance move
     * playing a depart flourish. The marker still self-heals: a stranded reclaim or a timeout lets it expire.
     */
    public static void onHop(ServerPlayer player)
    {
        if (player == null)
            return;
        // The marker is written even when animations are OFF here, because it is also how the destination knows a
        // login is a hop and not a fresh JOIN; it is cheap and it self-expires.
        ServerLevel level = player.serverLevel();
        String arriveId = "";
        try
        {
            // The TELEPORT paired slot is what carries both directions now; TP_ARRIVE.equipSlot() resolves to it.
            arriveId = CosmeticWardrobeData.get(level.getServer()).get(player.getUUID())
                    .in(CosmeticSlot.TP_ARRIVE.equipSlot());
        }
        catch (Throwable ignored)
        {
        }
        CosmeticArrival.mark(player, arriveId);
        // DEPART plays to the observers on THIS shard, at the position they vanish from. Gated inside play().
        net.shurui.shuruisutilities.api.key.CosmeticHooks.get().play(CosmeticSlot.TP_DEPART, player.getUUID(), level, player.position(),
                player.getYRot());
    }
}
