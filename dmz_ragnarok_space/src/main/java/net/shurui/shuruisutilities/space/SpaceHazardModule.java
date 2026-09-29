package net.shurui.shuruisutilities.space;

import java.util.List;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import com.dragonminez.common.init.entities.SpacePodEntity;

/**
 * The DANGER half of the two space hazards: the star burn and the black hole pull/kill. The SCENERY half (the entities
 * and their rendering) is spawned by {@link PlanetSpawnModule}; nothing here touches an entity or a chunk. Both hazards
 * re-derive their bodies straight from the position hash ({@link StarPositions}, {@link BlackHolePositions}) exactly as
 * generated-planet landing does, so a player is burned or pulled whether or not the body entity near them is loaded.
 *
 * <p>Runs as a slow server tick on the Forge bus (auto-registered by the SU module launcher). Only players in the space
 * dimension are considered, and SPECTATORS and CREATIVE players are skipped entirely, matching the rest of the space
 * code ({@link SpaceTravelModule} bails on spectators; the corrupted-event smite skips spectators and creative). Every
 * damaged or killed player gets a clear, translated cause via the custom damage sources in {@link SpaceHazardDamage}, so
 * there are no mystery deaths.
 *
 * <p>All strengths and toggles are read from {@link PlanetSpawnModule}'s baked SpacePlanets config, so an operator tunes
 * or disables the hazards through the one SpacePlanets config file rather than a second home.
 */
@SUModule(name = "SpaceHazards", parentMod = ShuruisUtilities.class, version = ShuruisUtilities.CURRENT_MODULE_VERSION)
public class SpaceHazardModule
{
    // The pull runs EVERY tick: it must, or fast ki flight would out-run a slow tug between applications. The whole
    // check runs every tick, which stays cheap because the star/black hole sectors are large (so the per-player cell
    // walk holds very few cells) and only players actually in space are visited. The burn is throttled to twice a second
    // (every BURN_INTERVAL ticks) so its "per half-second" damage figure is exact.
    private static final int BURN_INTERVAL = 10;

    // Extra reach, in blocks, added past a star's visual surface where the burn field begins. The field spans from this
    // outer edge (outer damage) down to the surface (inner damage). Widened 5x (2.5 -> 12.5, defect 5): the field now
    // reaches 12.5x the star's TRUE radius, a huge danger zone so a player is cooked long before nearing a star. This is a
    // plain constant, NOT config-backed (only the damage magnitudes are read from the SpacePlanets config), so this is a
    // default change in code. It also comfortably outreaches the enlarged 3x star DRAW size, so the burn starts well
    // outside anything the player can see, not inside the visible surface.
    private static final double STAR_BURN_FIELD_FACTOR = 12.5;

    // The inner pull is set from the player's OWN maximum outward speed so the inner zone is inescapable for everybody,
    // not just an average flyer. A player's on-foot max flight speed is PodSpeedBoost.computeMaxFlightSpeed; a ridden
    // space pod cruises at TWICE that (PodSpeedBoost boosts the pod to 2x the pilot's max), so a pod rider's real max
    // outward speed is 2x the on-foot figure. We floor the derived value at HARD_MAX_ESCAPE_PER_TICK so the guarantee
    // holds even when a shape-shifted DMZ read returns a stock-ish fallback: a heavily invested flyer reaches about 6
    // blocks/tick on foot and about 12 in a boosted pod (see PodSpeedBoost), so 12 is the documented ceiling to beat.
    private static final double HARD_MAX_ESCAPE_PER_TICK = 12.0;

    // The inner pull is the player's max outward speed times this margin, so the inward step strictly EXCEEDS anything
    // they can fly, guaranteeing a net inward move every tick past the point of no return. 1.5 leaves comfortable slack.
    private static final double INNER_ESCAPE_SAFETY = 1.5;

    // The point of no return sits at this multiple of the event-horizon radius: the inner band where the derived pull is
    // clamped to its inescapable value. Kept a small multiple of the horizon so the inescapable core is a compact region
    // near the centre, leaving the vast majority of the (now far larger) field as escapable, strengthening pull.
    private static final double NO_RETURN_HORIZON_FACTOR = 3.0;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
        {
            return;
        }
        boolean stars = PlanetSpawnModule.starsEnabled();
        boolean holes = PlanetSpawnModule.blackHolesEnabled();
        if (!stars && !holes)
        {
            return;
        }
        ServerLevel space = SpaceDimension.level(server);
        if (space == null)
        {
            return;
        }
        List<ServerPlayer> players = space.players();
        if (players.isEmpty())
        {
            return;
        }

        boolean burnTick = server.getTickCount() % BURN_INTERVAL == 0;
        for (ServerPlayer player : players)
        {
            // spectators and creative players are never damaged or pulled: they are meant to fly through space freely,
            // and this matches how the rest of the space/corrupted code treats them.
            if (player.isSpectator() || player.isCreative())
            {
                continue;
            }
            // ACTIVE AUTOPILOT immunity: a pod that is AUTO-TRAVELLING to a planet takes no star burn and no black hole
            // pull/kill, so a scripted cross-space cruise cannot be knocked off course or killed by a hazard the player
            // has no controls to avoid. The test is SpaceAutopilot.isActive (the su_space_autopilot persistent tag is
            // non-empty), which is set ONLY while a course-driven drive is running, so it is genuinely "auto travelling
            // only": a player sitting in a PARKED pod or HAND-FLYING one has an empty tag and stays fully vulnerable.
            // Both hazards act on the PLAYER (the burn hurts the player; the pull is decided per-player and either
            // teleports the player or drags the pod they ride), and the autopilot tag lives on the player, so this is
            // the correct place and subject for the check. The tag is read fresh every tick and every autopilot exit
            // clears it (dismount, pod destroyed, manual leave, landing, course-target loss all route through
            // stopAutopilot; death drops the un-copied tag), so immunity ends the instant auto-travel does and can
            // never get stuck on. Only the pilot who launched holds the tag, so only that pilot is covered.
            if (SpaceAutopilot.isActive(player))
            {
                continue;
            }
            if (holes)
            {
                applyBlackHoles(server, space, player);
            }
            if (stars && burnTick)
            {
                applyStarBurn(server, space, player);
            }
        }
    }

    // burn the player for the CLOSEST star whose field they are inside. Damage scales with proximity from starDamageOuter
    // at the field edge to starDamageInner flush against the surface, both as a fraction of MAX HEALTH so the hit stays
    // meaningful no matter how large the DMZ health pool is. Only the closest star is applied so overlapping fields (they
    // cannot overlap, but a player between two could be in both) never stack into a surprise multi-hit.
    private void applyStarBurn(MinecraftServer server, ServerLevel space, ServerPlayer player)
    {
        Vec3 p = player.position();
        List<StarPositions.Star> near = StarPositions.starsNear(server, p, PlanetSpawnModule.starSpawnRange());
        StarPositions.Star hit = null;
        double bestT = -1.0;
        for (StarPositions.Star star : near)
        {
            double dist = Math.sqrt(star.position.distanceToSqr(p));
            double outer = star.radius * STAR_BURN_FIELD_FACTOR;
            if (dist >= outer)
            {
                continue;
            }
            // t = 0 at the outer field edge, 1 at (or inside) the surface. Clamped so brushing the surface is full heat.
            double t = (outer - dist) / (outer - star.radius);
            t = Math.max(0.0, Math.min(1.0, t));
            if (t > bestT)
            {
                bestT = t;
                hit = star;
            }
        }
        if (hit == null)
        {
            return;
        }

        double frac = lerp(PlanetSpawnModule.starDamageOuter(), PlanetSpawnModule.starDamageInner(), bestT);
        float damage = (float) (frac * player.getMaxHealth());
        if (damage <= 0.0F)
        {
            return;
        }
        // clear the arbitrary-source hurt cooldown so back-to-back burn ticks always land, then apply the custom
        // star-burn source. It bypasses armour/effects/enchantments (see the datapack tags) so DMZ gear cannot soak it,
        // and carries the translated "burned up ... near a star" death message.
        player.invulnerableTime = 0;
        player.hurt(SpaceHazardDamage.star(space), damage);
    }

    // pull the player toward the CLOSEST black hole whose (now much larger) influence field they are inside, and KILL
    // them the moment they cross an event horizon. Only the closest is applied so a player who is somehow inside two
    // fields is not yanked in two directions at once.
    //
    // WHY THE PULL IS A POSITION DISPLACEMENT, NOT A VELOCITY NUDGE. DMZ ki flight recomputes the player's velocity from
    // their input every tick, so a force added to deltaMovement here would simply be overwritten next tick and do
    // nothing, which is the exact "a gentle nudge does nothing" failure the pull must avoid. Instead we move the
    // POSITION toward the centre each tick by a proximity-scaled amount (blocks per tick). For an on-foot player that is
    // a straight teleport that composes with their own flight (authoritative regardless of velocity). For a POD RIDER
    // the pod is client-authoritative, so a bare teleport is overwritten by the client's next ServerboundMoveVehicle
    // report; there we move the POD and send a ClientboundMoveVehiclePacket to force-snap the client, the same technique
    // SpaceTravelModule.nudgePod uses for the autopilot.
    //
    // THE PULL CURVE (feel over physics). Three zones over the influence field [horizon .. R = radius * influenceFactor]:
    //   OUTER  (near R): a gentle tug (blackHolePullOuter, well below a tick of flight), easily flown out of.
    //   MIDDLE          : the pull accelerates as an INVERSE-SQUARE of distance. Inverse-square is chosen over
    //                     inverse-distance because it is real gravity's own falloff and it stays almost flat across most
    //                     of the huge outer field, then ramps hard only as you close in, which reads as "it grabs you"
    //                     rather than a linear creep. The curve is anchored to pass through (R, pullOuter) and
    //                     (PNR, innerPull), so both endpoints are exact and the middle is the natural 1/d^2 between them.
    //   INNER  (d <= point of no return): the pull is at least innerPull, which is derived from THIS player's own max
    //                     outward speed (2x on foot when in a pod) times a safety margin, so the inward step strictly
    //                     exceeds anything they can fly. Escape is impossible for everyone, not just an average flyer.
    private void applyBlackHoles(MinecraftServer server, ServerLevel space, ServerPlayer player)
    {
        Vec3 p = player.position();
        double influenceFactor = PlanetSpawnModule.blackHoleInfluenceFactor();
        List<BlackHolePositions.BlackHole> near =
                BlackHolePositions.blackHolesNear(server, p, PlanetSpawnModule.blackHoleSpawnRange());
        BlackHolePositions.BlackHole closest = null;
        double bestDist = Double.MAX_VALUE;
        for (BlackHolePositions.BlackHole hole : near)
        {
            double dist = Math.sqrt(hole.position.distanceToSqr(p));
            // crossed the event horizon: kill outright with the custom black-hole source, which is in
            // bypasses_invulnerability and bypasses_resistance so nothing (armour, DMZ resistance, the hurt cooldown)
            // can save them, and carries the translated "torn apart by a black hole" death message.
            if (dist <= hole.horizonRadius())
            {
                killInBlackHole(player, space);
                return;
            }
            double influence = hole.radius * influenceFactor;
            if (dist < bestDist && dist < influence)
            {
                bestDist = dist;
                closest = hole;
            }
        }
        if (closest == null || bestDist <= 1.0E-4)
        {
            return;
        }

        double horizon = closest.horizonRadius();
        double influence = closest.radius * influenceFactor;

        // The player's max outward speed this tick: 2x their on-foot max when riding a pod (the pod cruises at 2x the
        // pilot's max, per PodSpeedBoost), else the on-foot max. Floored at the documented boosted-pod ceiling so the
        // inner zone beats even a maxed pod when the per-player DMZ read degrades to a fallback.
        boolean inPod = player.getVehicle() instanceof SpacePodEntity;
        double maxEscape = PodSpeedBoost.computeMaxFlightSpeed(player) * (inPod ? 2.0 : 1.0);
        maxEscape = Math.max(maxEscape, HARD_MAX_ESCAPE_PER_TICK);
        double innerPull = Math.max(PlanetSpawnModule.blackHolePullInner(), maxEscape * INNER_ESCAPE_SAFETY);

        double outerPull = PlanetSpawnModule.blackHolePullOuter();
        // point of no return: a small multiple of the horizon, clamped safely inside the influence edge.
        double pnr = Math.min(horizon * NO_RETURN_HORIZON_FACTOR, influence * 0.5);
        pnr = Math.max(pnr, horizon + 1.0E-3);

        double step = pullStep(bestDist, outerPull, innerPull, pnr, influence);
        // never overshoot past the centre in one tick.
        step = Math.min(step, bestDist);

        Vec3 dir = closest.position.subtract(p).scale(1.0 / bestDist);

        if (inPod)
        {
            // pod path: move the pod and force-snap the client (see nudgePod rationale) so its next report does not undo
            // the drag. The rider follows the pod. If the DMZ pod class shape-shifts, fall back to moving the player.
            if (dragPod(player, (SpacePodEntity) player.getVehicle(), dir, step))
            {
                warnBlackHole(player, bestDist, pnr);
                return;
            }
        }

        // on-foot path (also the pod fallback): teleport the player's POSITION inward. Do NOT zero their velocity, so
        // this inward move COMPOSES with their own ki flight rather than replacing it. hurtMarked forces the resync so
        // the drag is felt, not rubber-banded. This is the established, shipped on-foot approach and is authoritative
        // for a ki-flying player because it moves position directly rather than fighting the per-tick velocity.
        Vec3 target = p.add(dir.scale(step));
        player.teleportTo(space, target.x, target.y, target.z, player.getYRot(), player.getXRot());
        player.hurtMarked = true;
        warnBlackHole(player, bestDist, pnr);
    }

    // The inward step in blocks per tick at distance d, as an inverse-square curve anchored at (influence, outerPull) and
    // (pnr, innerPull). Inside pnr the same 1/d^2 form keeps growing, so the pull only strengthens toward the centre and
    // never drops below innerPull. Falls back to a linear interpolation if the anchors are degenerate.
    private static double pullStep(double d, double outerPull, double innerPull, double pnr, double influence)
    {
        double invPnr = 1.0 / (pnr * pnr);
        double invR = 1.0 / (influence * influence);
        double denom = invPnr - invR;
        if (denom <= 1.0E-12)
        {
            double t = (influence - d) / Math.max(influence - pnr, 1.0E-6);
            return lerp(outerPull, innerPull, Math.max(0.0, Math.min(1.0, t)));
        }
        // pull(d) = A / d^2 + B, solved so pull(influence) = outerPull and pull(pnr) = innerPull.
        double a = (innerPull - outerPull) / denom;
        double b = outerPull - a * invR;
        double pull = a / (d * d) + b;
        // clamp: never below the gentle outer tug, and hold at innerPull once inside the point of no return.
        pull = Math.max(pull, outerPull);
        if (d <= pnr)
        {
            pull = Math.max(pull, innerPull);
        }
        return pull;
    }

    // Move the pod inward by `step` along `dir` and force-snap the controlling client, mirroring
    // SpaceTravelModule.nudgePod: a bare pod.teleportTo is overwritten by the client's next ServerboundMoveVehicle
    // report, so the ClientboundMoveVehiclePacket is what makes the drag stick for a pod rider. Returns false (so the
    // caller falls back to moving the player) if the pod/vehicle shape-shifts, rather than crashing the tick.
    private boolean dragPod(ServerPlayer player, SpacePodEntity pod, Vec3 dir, double step)
    {
        try
        {
            Vec3 pos = pod.position();
            Vec3 next = pos.add(dir.scale(step));
            // set delta to the step so the motion reads as real momentum to other clients and to the anti-cheat
            // tolerance, exactly as nudgePod does, then teleport and force-snap the pilot's client.
            pod.setDeltaMovement(next.subtract(pos));
            pod.teleportTo(next.x, next.y, next.z);
            player.connection.send(new ClientboundMoveVehiclePacket(pod));
            player.hurtMarked = true;
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    // Escape feedback on the action bar: a gentle warning inside the influence field, and a stronger "no escape" warning
    // once past the point of no return, so drifting is never a mystery. translatableWithFallback so it reads even if a
    // lang entry is missing. Sent every tick while inside the field; the action bar is built for continuous display, so
    // this refreshes the same line rather than spamming chat.
    private void warnBlackHole(ServerPlayer player, double dist, double pnr)
    {
        if (dist <= pnr)
        {
            player.displayClientMessage(Component.translatableWithFallback(
                    "message.dmz_ragnarok.core.space_black_hole_no_return",
                    "The black hole has you. There is no escaping now."), true);
        }
        else
        {
            player.displayClientMessage(Component.translatableWithFallback(
                    "message.dmz_ragnarok.core.space_black_hole_pull",
                    "A black hole's gravity has you. Break away before you are pulled in."), true);
        }
    }

    private void killInBlackHole(ServerPlayer player, ServerLevel space)
    {
        // set health to 0 first so a downstream handler that somehow cancelled the hurt still leaves them dead, then
        // deal the lethal custom-source hit for the correct death message. The source bypasses invulnerability and
        // resistance, so this is a true kill, not "a lot of damage".
        player.invulnerableTime = 0;
        player.hurt(SpaceHazardDamage.blackHole(space), Float.MAX_VALUE);
        if (player.isAlive())
        {
            // belt and braces: if some other mod cancelled even the bypassing hit, force the kill so crossing the
            // horizon is never survivable, and still surface the cause on the action bar.
            player.setHealth(0.0F);
            player.die(SpaceHazardDamage.blackHole(space));
        }
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.space_black_hole_pulled"), true);
    }

    private static double lerp(double a, double b, double t)
    {
        return a + (b - a) * t;
    }
}
