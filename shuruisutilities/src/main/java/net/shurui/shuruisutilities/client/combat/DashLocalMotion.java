package net.shurui.shuruisutilities.client.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.combat.DashMode;
import net.shurui.shuruisutilities.combat.DashPath;

/**
 * Flies the local player's own dash, on their own machine.
 *
 * <p>This is the fix for a dash that felt jumpy and delayed. The server used to move the dasher by writing their
 * velocity every tick and forcing it down the wire, which meant the player's smooth local motion was overwritten
 * twenty times a second by a value that had been computed a round trip earlier. Even on a local server that reads as a
 * stutter, and on a real one it is worse: the dash visibly lags the key press and then catches up in jerks.
 *
 * <p>The player's own client is authoritative for their movement in Minecraft, so the right place to run the dash is
 * here. The server sends the planned route once, this drives the player along it every client tick, and the server
 * watches its own copy of the same curve and only pulls if the two genuinely disagree. The curve itself lives in
 * {@link DashPath} precisely so both sides compute it identically.
 *
 * <p>Only ever the LOCAL player. Everyone else's dash arrives as ordinary position updates, which the entity renderer
 * already interpolates smoothly; driving them from here would be predicting motion the server has already told us
 * about.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class DashLocalMotion
{
    private DashLocalMotion() {}

    // Never move further in one tick than this. The route is normally well inside it; this catches the case where the
    // target teleports and the curve's end jumps, which would otherwise be applied as one enormous step.
    private static final double MAX_STEP = 5.5D;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null)
            return;
        DashAuraState.Route route = DashAuraState.route(player.getId());
        if (route == null)
            return;
        try
        {
            drive(mc, player, route);
        }
        catch (Throwable ignored)
        {
            // Movement is the server's to fall back on. A failure here costs smoothness, not the dash.
        }
    }

    private static void drive(Minecraft mc, LocalPlayer player, DashAuraState.Route route)
    {
        route.clientTicks++;
        // Falling during the windup would undo the point of it. Held to a dead stop rather than merely slowed, which is
        // what the server does on its side too.
        if (route.clientTicks <= route.windup)
        {
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            return;
        }

        if (!route.anchored)
        {
            // First travelling tick. The route was planned from where the player stood when the server got the
            // request; they have moved since, and starting the curve back there is what threw them backwards before it
            // pulled them forward. The shape is anchored to the TARGET, not to this point, so moving the start does not
            // change the arc.
            route.anchored = true;
            route.origin = player.getEyePosition();
        }

        Vec3 step;
        Entity target = route.targetId > 0 ? mc.level.getEntity(route.targetId) : null;
        if (target != null && target.isAlive())
        {
            DashMode mode = DashMode.byId(route.modeId);
            // Recomputed from where the target is NOW, and facing where they are facing NOW, exactly as the server
            // does, so the curve follows someone who is moving or turning while keeping the shape it was planned with.
            // The lateral flanks are the exception and use the side that came with the route: see DashPath.destination
            // for why those two cases have to differ.
            Vec3 destination = DashPath.destination(player.getEyePosition(), target.getEyePosition(),
                    DashPath.bodyFacing(target), target.getBbWidth() * 0.5D, target.getBbHeight() * 0.5D,
                    mode, route.side);
            double t = (double) (route.clientTicks - route.windup) / (double) Math.max(1, route.travelTicks);
            Vec3 want = DashPath.sample(route.origin, route.control, destination, t);
            step = want.subtract(0.0D, player.getEyeHeight(), 0.0D).subtract(player.position());
        }
        else
        {
            // A plain travel burst, or a dash whose target has gone. Straight along the heading for what is left of it.
            Vec3 heading = DashAuraState.heading(player.getId());
            if (heading == null)
                return;
            double remaining = route.maxTravel - route.travelled;
            if (remaining <= 0.0D)
                return;
            step = heading.scale(Math.min(net.shurui.shuruisutilities.combat.DashService.SPEED, remaining));
            route.travelled += step.length();
        }

        double length = step.length();
        if (length > MAX_STEP)
            step = step.scale(MAX_STEP / length);
        if (length > 1.0E-6D)
            DashAuraState.setHeading(player.getId(), step.normalize());
        player.setDeltaMovement(step);
        player.fallDistance = 0.0F;
    }
}
