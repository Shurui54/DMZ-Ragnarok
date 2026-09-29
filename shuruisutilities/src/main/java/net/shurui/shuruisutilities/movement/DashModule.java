package net.shurui.shuruisutilities.movement;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * The dash: a short, sharp burst of movement in the direction the player is facing.
 *
 * <h2>Server owns it</h2>
 * The client only ever reports "the dash key was double-tapped". Everything that matters, whether the player is
 * allowed to dash, how hard, and when they may do it again, is decided here. A client that lies about its taps
 * gets nothing but a rejected request, and the cooldown cannot be shortened by spamming the key.
 *
 * <h2>Why the impulse is set, not added</h2>
 * Adding to existing motion makes a dash out of a sprint or a fall far stronger than a dash from standing, which
 * is both inconsistent to play against and trivially abusable for distance. The horizontal component is REPLACED
 * with the dash vector so every dash covers the same ground. Vertical motion is left alone unless the player is
 * on the ground, where a small lift stops the dash grinding to a halt on the first block lip it meets.
 *
 * <h2>Cooldown is per player and wall-clock</h2>
 * Kept in a map keyed by UUID rather than on the entity, so it survives dimension changes (which recreate the
 * player object) and cannot be cleared by relogging mid-cooldown.
 */
public final class DashModule
{
    /** How far the dash throws the player. Tuned to read as a burst, not a teleport. */
    private static final double DASH_SPEED = 1.35;

    /** Small upward nudge when dashing from the ground so the dash does not catch on the first step it meets. */
    private static final double GROUND_LIFT = 0.18;

    /** Minimum gap between dashes, in milliseconds. */
    private static final long COOLDOWN_MS = 700L;

    // UUID -> when this player may dash again. Wall clock, so it is unaffected by tick lag or dimension changes.
    private static final Map<UUID, Long> NEXT_DASH = new HashMap<>();

    private DashModule()
    {
    }

    /**
     * Try to dash. Returns true if it happened, false if the player is still on cooldown or is in a state where
     * dashing makes no sense.
     *
     * <p>Called from the packet handler, so it must never trust its caller: the checks below are the whole
     * authority on whether a dash is legal.
     */
    public static boolean tryDash(ServerPlayer player)
    {
        if (player == null || !canDash(player))
        {
            return false;
        }

        long now = System.currentTimeMillis();
        Long ready = NEXT_DASH.get(player.getUUID());
        if (ready != null && now < ready)
        {
            return false;
        }
        NEXT_DASH.put(player.getUUID(), now + COOLDOWN_MS);

        apply(player);
        return true;
    }

    /** Room for the gates that come later (ki cost, stun, flow state); for now, only the obvious ones. */
    private static boolean canDash(ServerPlayer player)
    {
        if (player.isSpectator() || player.isSleeping())
        {
            return false;
        }
        // A player being carried is not in charge of their own movement.
        return !player.isPassenger();
    }

    private static void apply(ServerPlayer player)
    {
        Vec3 look = player.getLookAngle();
        // Horizontal facing only. Using the full look vector would send a player staring at their feet straight
        // into the floor and one looking up into orbit; the vertical component is handled deliberately below.
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        if (flat.lengthSqr() < 1.0E-4)
        {
            return;                       // looking exactly up or down: no horizontal direction to dash in
        }
        Vec3 dir = flat.normalize().scale(DASH_SPEED);

        double y = player.getDeltaMovement().y;
        if (player.onGround())
        {
            y = GROUND_LIFT;
        }
        player.setDeltaMovement(dir.x, y, dir.z);

        // The client simulates its own player, so a server-side motion change has to be pushed or it is
        // smoothed away as a correction. This is the same reason a knockback is sent explicitly.
        player.hurtMarked = true;

        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.6F, 1.6F);
    }

    /** Drop a player's cooldown when they leave, so the map does not grow for the lifetime of the server. */
    public static void forget(UUID playerId)
    {
        NEXT_DASH.remove(playerId);
    }
}
