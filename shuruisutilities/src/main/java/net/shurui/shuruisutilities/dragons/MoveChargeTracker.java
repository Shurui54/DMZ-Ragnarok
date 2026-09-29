package net.shurui.shuruisutilities.dragons;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.compat.dmz.ChargeState;
import net.shurui.shuruisutilities.compat.dmz.MoveAnimation;
import net.shurui.shuruisutilities.energy.EnergyKind;
import net.shurui.shuruisutilities.energy.EnergyManager;
import net.shurui.shuruisutilities.god.RoleMove;

/**
 * Plays the charge animation for our moves the moment charging begins.
 *
 * <p>The mixin on {@code startTechniqueCharge} knows WHICH technique started but not WHO started it, because
 * {@code Techniques} is per-character state with no entity reference. This ticker closes that gap: it notices which
 * online player is charging one of our technique ids and sends them the held {@code _cast} pose once, then clears the
 * pose when they stop charging.
 *
 * <p>Registered by hand on the Forge bus from the mod's main class.
 */
public final class MoveChargeTracker
{
    /** Set by the mixin when any technique starts charging; consumed by the next tick. */
    private static volatile String lastStarted;

    /** Players currently shown a charge pose, so it is sent once and cleared once. */
    private static final Map<UUID, String> posing = new HashMap<>();

    public static void noteChargeStarted(String techniqueId)
    {
        lastStarted = techniqueId;
    }

    /** The animation prefix for one of our technique ids, or null when it is not ours. */
    private static String prefixFor(String techniqueId)
    {
        DragonMove dragon = DragonMove.byId(techniqueId);
        if (dragon != null)
            return dragon.animation;
        RoleMove role = RoleMove.byId(techniqueId);
        return role == null ? null : role.animation;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        MinecraftServer server = event.getServer();
        if (server == null)
            return;

        shapeChargingOrbs(server);

        String started = lastStarted;
        if (started == null)
            return;
        lastStarted = null;

        // The mini clone charges with DMZ's BLOCK pose, a single raw clip rather than a ki cast/fire prefix, so it is
        // handled alongside the dragon/role moves but played through the raw-clip path.
        boolean isMiniClone = net.shurui.shuruisutilities.clone.MiniClone.TECHNIQUE_ID.equals(started);
        String prefix = prefixFor(started);
        if (prefix == null && !isMiniClone)
            return;
        // The charge belongs to whoever is currently charging that id. Checked against the live charging id rather
        // than assumed, so two players charging different moves cannot be mixed up.
        Cost cost = costOf(started);
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (!started.equals(ChargeState.chargingIdOf(player)))
                continue;
            // CANNOT PAY, SO DO NOT START. The bar is only spent when the move actually fires, which meant a caster
            // who was empty charged the whole thing, released, and got nothing: no move, no message, and no way to
            // tell a refusal from a bug. Refused at the start instead, in the same tick the charge begins, so the
            // answer arrives when the key goes down.
            //
            // This does NOT replace the check at cast, and must not: a charge can be held for as long as the caster
            // likes, and the bar can be spent elsewhere or drained by something else while they hold it. This one
            // answers "could you have started this", the one at cast answers "can you pay right now", and both are
            // real questions.
            if (cost != null && EnergyManager.get(player, cost.kind) < cost.amount)
            {
                su$abandonCharge(player);
                player.displayClientMessage(Component.literal("Not enough " + cost.kind.id() + " energy."), true);
                continue;
            }
            if (started.equals(posing.get(player.getUUID())))
                continue;
            posing.put(player.getUUID(), started);
            if (isMiniClone)
                MoveAnimation.chargeRaw(player, net.shurui.shuruisutilities.clone.MiniClone.CHARGE_ANIMATION);
            else
                MoveAnimation.charge(player, prefix);
        }
    }

    /**
     * Take a charge back off a player completely: the server's charge state, the animation, and the ball.
     *
     * <p>All three, because the refusal above used to do only the first and a half of them and that is why an
     * unaffordable move left a caster stuck mid-charge.
     *
     * <p>The animation is stopped UNCONDITIONALLY rather than through {@link #clearPose}. clearPose only stops a
     * pose THIS class sent, and on the refusal path it never sent one: the animation already playing is DMZ's own,
     * started on the client the instant the key went down, before the server had any say. So the pose map was
     * empty, clearPose did nothing, no stop ever reached the client, and it held the charge pose for ever.
     *
     * <p>The charging ball has to go for the same reason: DMZ spawns it at charge start and only ever consumes it
     * on a real release, so clearing the charge underneath it leaves it orbiting a caster who is no longer casting.
     *
     * <p>The map entry is dropped first so a later clearPose cannot send a second, pointless stop.
     */
    private static void su$abandonCharge(ServerPlayer player)
    {
        posing.remove(player.getUUID());
        ChargeState.clearCharge(player);
        MoveAnimation.stop(player);
        net.shurui.shuruisutilities.compat.dmz.ChargingProjectile.discardChargingFor(player);
    }

    /** What one cast of a technique takes, and from which bar. */
    private record Cost(EnergyKind kind, float amount) {}

    /**
     * The energy one cast of this technique costs, or null when it is not one of ours and so costs no role energy.
     *
     * <p>Kept in step with what the cast paths actually spend: every dragon move takes the flat standard share of
     * malice ({@code DragonMoveEffects}), a role move takes the same share of its own bar, and hakai alone takes the
     * whole bar ({@code GodHakai}), so it is the one move a half-full caster must not be allowed to begin.
     */
    private static Cost costOf(String techniqueId)
    {
        if (DragonMove.byId(techniqueId) != null)
            return new Cost(EnergyKind.MALICE, EnergyManager.COST_STANDARD);
        RoleMove role = RoleMove.byId(techniqueId);
        if (role == null)
            return null;
        return new Cost(role.kind, role == RoleMove.HAKAI ? EnergyManager.MAX : EnergyManager.COST_STANDARD);
    }

    /**
     * Keep every area move's charging orb wrapped around its caster and growing with the charge.
     *
     * <p>Runs every tick over the online players rather than once at spawn, because the orb has to keep pace with a
     * charge that is still climbing, and because DMZ re-derives the ball's position from its cast offsets on every
     * tick of the charge - setting them once at spawn would be overwritten by nothing, but the SIZE would freeze at
     * whatever the charge was worth on the first tick.
     *
     * <p>Omega's ball is skipped on purpose: it is a projectile held overhead and then thrown, not an area, so DMZ's
     * own offsets are the right ones for it.
     */
    private void shapeChargingOrbs(MinecraftServer server)
    {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            String charging = net.shurui.shuruisutilities.compat.dmz.ChargeState.chargingIdOf(player);

            // The sphere gathers loose orbs around its caster instead of a single ball, so it is driven here too -
            // and dropped the moment they stop charging, or an abandoned cast leaves ki orbiting them for good.
            // The sphere is the Ragnarok Key's (feature roles); keyless both calls are no-ops.
            if (RoleMove.KI_PRISON.id.equals(charging))
                net.shurui.shuruisutilities.api.key.RoleHooks.get().tickKiPrisonCharge(player,
                        net.shurui.shuruisutilities.compat.dmz.ChargeState.chargePercentOf(player));
            else
                net.shurui.shuruisutilities.api.key.RoleHooks.get().clearKiPrisonCharge(player);

            if (charging == null || charging.isEmpty())
                continue;
            DragonMove move = DragonMove.byId(charging);
            // Omega's is a thrown ball held overhead, and the hurricane charges with no ball at all, so neither is
            // an area orb to wrap around its caster.
            if (move == null || move == DragonMove.MINUS_ENERGY_POWER_BALL || move == DragonMove.HURRICANE_FURY)
                continue;
            net.shurui.shuruisutilities.compat.dmz.ChargingProjectile.engulfCaster(player,
                    (float) (move.effectRadius() * 2.0),
                    net.shurui.shuruisutilities.compat.dmz.ChargeState.chargePercentOf(player));
        }
    }

    /** Called when a move fires or is abandoned, so the held pose does not linger. */
    public static void clearPose(ServerPlayer player)
    {
        if (player != null && posing.remove(player.getUUID()) != null)
            MoveAnimation.stop(player);
    }
}
