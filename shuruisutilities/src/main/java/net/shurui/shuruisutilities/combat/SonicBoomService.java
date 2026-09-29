package net.shurui.shuruisutilities.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.dmz.DmzFlightState;
import net.shurui.shuruisutilities.compat.dmz.DmzResourceDrain;

/**
 * Breaking the sound barrier: what it takes, and what it costs.
 *
 * <p>The crash itself is {@link SonicCrash}; this is only the gate in front of it, kept separate because the same
 * crash is also something that happens TO people (a badly lost melee clash) with none of these conditions attached.
 *
 * <h2>The conditions, and why each one</h2>
 * <ul>
 *   <li>FLYING. It is a flight manoeuvre; on the ground the gesture is just a sprint.</li>
 *   <li>FLIGHT MAXED. This is the reward for having taken the skill all the way, so it is gated on exactly that
 *       rather than on a level of ours that would have to be kept in step with DMZ's.</li>
 *   <li>KI AND STAMINA, both, taken together or not at all. Half-charging someone who could not afford it leaves
 *       them worse off than not pressing the key.</li>
 *   <li>A COOLDOWN. The gesture is two taps of a key players already hold constantly, so without one it would fire
 *       on any hurried sprint and empty their bars for them.</li>
 * </ul>
 */
public final class SonicBoomService
{
    private SonicBoomService() {}

    /**
     * What it costs to BREAK the barrier, as a SHARE of maximum DMZ ki and stamina.
     *
     * <p>Only the ignition. Staying supersonic is charged per tick for as long as it is held ({@code SonicCrash}),
     * so this is deliberately small: the old flat two hundred was the price of the whole manoeuvre back when it
     * lasted a second and a bit, and charging that up front as well would mean a run that ends early costs more
     * than one that goes on for a minute.
     *
     * <p>A share rather than the flat 60/45 points it used to be, for the same reason the sustain is
     * ({@code SonicCrash.KI_FRACTION_PER_TICK}): DMZ's maxima come off a stat, so 45 points is a trivial ignition
     * fee for a maxed flier and more than the whole bar for anyone else, who was simply refused at the door.
     */
    private static final double KI_COST_FRACTION = 0.05D;
    private static final double STAMINA_COST_FRACTION = 0.04D;

    /** Ticks between booms. */
    private static final int COOLDOWN_TICKS = 60;

    // player -> the server tick their next boom is allowed on. Not persisted: a three second cooldown is not worth
    // surviving a restart.
    private static final Map<UUID, Long> readyAt = new HashMap<>();

    /** A client reported the gesture. Everything is decided here. */
    public static void request(ServerPlayer player)
    {
        if (player == null || player.getServer() == null)
            return;
        if (!net.shurui.shuruisutilities.core.config.PublicContent.allows(
                net.shurui.shuruisutilities.core.config.PublicContent.FEATURE_COMBAT_LAYER))
            return; // silent, same as the other refusals here
        if (SonicCrash.isCrashing(player))
            return; // already going: the second gesture is a fumbled key, not a second boom

        long now = player.getServer().getTickCount();
        Long ready = readyAt.get(player.getUUID());
        if (ready != null && now < ready)
            return; // silent: a cooldown message on a double-tapped movement key would be constant noise

        if (!DmzFlightState.isFlying(player))
            return; // on the ground this gesture is just sprinting; say nothing
        if (!DmzFlightState.isFlightMaxed(player))
        {
            SonicCrash.refuse(player, "Your flight is not mastered enough to break the sound barrier.");
            return;
        }
        if (!DmzResourceDrain.spendFraction(player, KI_COST_FRACTION, STAMINA_COST_FRACTION))
        {
            SonicCrash.refuse(player, "Not enough ki and stamina to break the sound barrier.");
            return;
        }

        if (!SonicCrash.breakSoundBarrier(player, SonicCrash.damageFor(player)))
        {
            // Refused after the cost was taken (it could not read a heading): hand it straight back.
            DmzResourceDrain.restoreFraction(player, KI_COST_FRACTION, STAMINA_COST_FRACTION);
            return;
        }
        readyAt.put(player.getUUID(), now + COOLDOWN_TICKS);
    }

    /** Drop every cooldown. Called with the rest of the combat state when the server stops. */
    public static void clear()
    {
        readyAt.clear();
    }
}
