package net.shurui.shuruisutilities.client.combat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Flies the local player's own sonic crash, on their own machine.
 *
 * <p>The same lesson as the dash, learned again: a player's client is authoritative for their movement, so a crash
 * pushed only from the server is overwritten by the client every tick and stops dead the moment it starts - which is
 * exactly how it behaved.
 *
 * <p>For a flier driving themselves this SCALES the velocity they already have, so they keep their own controls and
 * their own turns and merely go faster. It used to overwrite that velocity with a vector along a stored heading,
 * which flew one predetermined line and could not be turned at all. Only a THROWN body (a lost melee clash) still
 * gets a fixed vector, because that one is not the player's to steer.
 *
 * <p>Only the LOCAL player is driven. Everyone else's crash arrives as ordinary position updates, which the entity
 * renderer already interpolates.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class SonicCrashLocalMotion
{
    private SonicCrashLocalMotion() {}

    private static final class Crash
    {
        final Vec3 heading;
        final double speed;
        /** Whether the body follows where its owner LOOKS, or the one heading it was thrown along. */
        final boolean steered;
        int ticksLeft;

        Crash(Vec3 heading, double speed, int ticks, boolean steered)
        {
            this.heading = heading;
            this.speed = speed;
            this.ticksLeft = ticks;
            this.steered = steered;
        }
    }

    private static final Map<Integer, Crash> RUNNING = new HashMap<>();

    public static void begin(int entityId, Vec3 heading, double speed, int ticks, boolean steered)
    {
        if (heading == null || heading.lengthSqr() < 1.0E-6D || ticks <= 0)
            return;
        RUNNING.put(entityId, new Crash(heading.normalize(), speed, ticks, steered));
    }

    public static void end(int entityId)
    {
        RUNNING.remove(entityId);
    }

    public static void clear()
    {
        RUNNING.clear();
    }

    /**
     * LOWEST priority, and that is the whole reason the boost used to die on the spot.
     *
     * <p>DMZ flies its own players from a client tick handler at the END phase, and the last thing it does is
     * {@code setDeltaMovement(flightVector)} - so a crash written at the same phase and the ordinary priority was
     * simply overwritten before the body ever moved, every tick. Running last is what makes the speed stick, and
     * with the speed the destruction, since the hole is punched along the path the body actually travels.
     */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || RUNNING.isEmpty())
            return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null)
        {
            clear();
            return;
        }
        for (Iterator<Map.Entry<Integer, Crash>> it = RUNNING.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<Integer, Crash> entry = it.next();
            Crash crash = entry.getValue();
            if (crash.ticksLeft-- <= 0)
            {
                it.remove();
                continue;
            }
            // Only our own body. Every other crasher is already moving on screen from the server's updates.
            if (entry.getKey() != player.getId())
                continue;
            // LETTING GO OF FORWARD ENDS IT, exactly as it ends ordinary fast flight.
            //
            // Without this the run outlived the flight it was a boost on. The sprint hold below re-asserts the
            // sprint flag every tick, and DMZ reads fast flight off that flag, so a flier who released the key was
            // put straight back into fast flight by us and kept the multiplier with it. The body never slowed, so
            // the server never saw it stall, so nothing ended the run: it carried on until the bars ran dry or the
            // ground arrived. Pressing forward again then read as being shoved into a second boom, because the
            // first one had never stopped.
            //
            // The hold itself is still right, it was just unconditional. Vanilla's aiStep clears sprinting for
            // three different reasons and only ONE of them means the player is done: horizontal collision (which
            // is what tunnelling through blocks is) and a low food bar are both incidental, and the run has to
            // survive those. Releasing forward is the one that is a decision, so that is the one we stop covering
            // for. A thrown body is untouched, having never been the player's to steer in the first place.
            if (crash.steered && (player.input == null || !player.input.hasForwardImpulse()))
            {
                it.remove();
                // No packet: the server ends its own copy the honest way, by watching the body actually stop.
                // Sprinting is left alone rather than cleared, so a player who is genuinely sprinting keeps it;
                // once we stop re-asserting, aiStep clears the flag on its own next tick.
                continue;
            }
            if (crash.steered)
            {
                // A MULTIPLE of what they are already doing, direction untouched. This is the whole manoeuvre for
                // a flier driving themselves: they fly normally, with normal controls, only faster. Replacing the
                // vector with one along a stored heading (which is what this did) is what made the run impossible
                // to turn, because the player's own steering was overwritten every single tick.
                //
                // Running at LOWEST priority is what makes it a multiplier rather than a fight: DMZ has already
                // written this tick's flight velocity by the time we get here, so scaling it compounds with their
                // input instead of replacing it, and it re-derives from whatever their real max speed currently is.
                Vec3 current = player.getDeltaMovement();
                if (current.lengthSqr() > 1.0E-8D)
                    player.setDeltaMovement(current.scale(net.shurui.shuruisutilities.combat.SonicCrash.SELF_SPEED_MULTIPLIER));
            }
            else
            {
                // Thrown: a fixed line the player had no say in, so it IS asserted.
                player.setDeltaMovement(crash.heading.scale(crash.speed));
            }
            player.fallDistance = 0.0F;
            player.hasImpulse = true;
            // HOLD THE SPRINT FLAG, for as long as they are still asking to move (checked above).
            //
            // It matters because DMZ decides a local player is flying fast from their sprint flag, and vanilla
            // LocalPlayer.aiStep clears that flag every tick on any of three things this manoeuvre does constantly:
            // letting go of forward, colliding horizontally (which is what tunnelling through blocks IS), and running
            // the food bar low. The gesture that starts a boom is a double TAP of the sprint key, so the key is up by
            // the time the run begins and nothing puts the flag back. DMZ then drops the flier out of fast flight, the
            // body slows below the server's stopped threshold, and the run is ended as stalled a few ticks in, which
            // is the "starts and then stops instantly" this reads as in game.
            //
            // Re-asserted at tick END, which is after aiStep has cleared it, so ours is the last word. The next tick's
            // sendIsSprintingIfNeeded carries the change to the server on its own.
            if (crash.steered && !player.isSprinting())
                player.setSprinting(true);
        }
    }
}
