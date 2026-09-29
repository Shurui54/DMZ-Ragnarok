package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.dragons.DragonMove;
import net.shurui.shuruisutilities.dragons.DragonMoveEffects;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.techniques.KiAttackData;
import com.dragonminez.common.stats.techniques.TechniqueDispatcher;

/**
 * The cast seam for the shadow dragon moves: catches a cast of one of OUR registered techniques before DMZ acts on
 * it, charges malice for it, and runs our effect.
 *
 * <h2>Why here</h2>
 * {@code TechniqueDispatcher.executeKiAttack} is the single funnel every ki technique cast goes through, and it
 * receives the {@link KiAttackData} itself, so the technique's id is in hand with no lookup. The alternative seams
 * are worse: the ki projectile entity carries no technique id (only an owner and colours), so a move could not be
 * identified once it is in flight, and five of the seven moves are caster-centred area effects that never spawn a
 * projectile to identify in the first place.
 *
 * <h2>What is cancelled and what is not</h2>
 * An AREA move is entirely ours, so the call is cancelled with a success return: DMZ must not also launch something.
 * The two genuine projectile moves (Naturon's beam, Omega's ball) fall through to DMZ so it spawns and steers the
 * projectile normally, and their extra effects are applied when the projectile lands.
 *
 * <h2>Paying for it</h2>
 * Malice is charged HERE and only here, and a refused charge cancels the cast with a failure return, so a move on an
 * empty bar does nothing rather than going off free. Because the charge happens before any effect runs, a cast can
 * never take the bar and then fizzle.
 *
 * <p>{@code require = 0}: a DMZ-targeting mixin must never harden the build against a DMZ version that moved this
 * method, and a green build does not prove this bound. Launch-test it.
 */
@Mixin(value = TechniqueDispatcher.class, remap = false)
public abstract class MixinDmzTechniqueDispatcher
{
    /**
     * The charge value DMZ passes on its UNCHARGED path, exactly.
     *
     * <p>{@code TickHandler} calls {@code executeKiAttack} from two places: the charged release, which passes the
     * accumulated charge, and a tap-fire guarded by "no charging entity and charge percent is zero", which passes a
     * hardcoded {@code 0.01f}. Acting on both is what made the moves go off the instant the key was pressed.
     *
     * <p>An earlier version of this gate DID break the moves entirely - but only because they were registered as
     * AREA at the time, and an AREA technique never spawns a charging entity, so it could ONLY ever arrive by the
     * tap path and the gate rejected everything. Now that every move is a charging ball type, the charged path is
     * reachable and the gate does what it was meant to.
     */
    private static final float UNCHARGED_MARKER = 0.01f;

    /** Anything at or below this came from the tap path, not a charged release. */
    private static final float MIN_CHARGE = UNCHARGED_MARKER * 2.0f;

    /**
     * A full charge, on the same scale DMZ hands us: {@code chargePercent / 100}.
     *
     * <p>DMZ itself fires anything released past 50%, which let every one of these go off half built. They are
     * meant to be held: below a full bar the cast is refused outright, which costs the charge and the ki spent
     * building it but takes no malice and sets no cooldown, so it reads as a fumble rather than as a weak hit.
     * A hair under one, because the charge climbs in fractional steps and can land at 99.997.
     */
    private static final float FULL_CHARGE = 0.999f;

    /**
     * Whether this player may use this dragon's move at all.
     *
     * <p>DMZ's own race filter ({@code setAllowedRaces}) already keeps each dragon to its own move: only the owning
     * sub-race and the base race are on any move's list, so a three-star can cast Absolute Zero and nothing else.
     * What that filter CANNOT express is the rule for the base dragon. The base race is on every move's list, which
     * is what made transforming into Omega hand over the entire kit at once, and a static registry field has no way
     * to ask a per-player question.
     *
     * <p>So the rule is enforced here instead: Omega may use a dragon's move only once that dragon's SUB-RACE has
     * been unlocked. Each of the six is earned separately, so the kit is assembled a dragon at a time rather than
     * arriving whole with the transformation.
     *
     * <p>Two things are deliberately allowed through. Omega's own Minus Energy Power Ball belongs to the base race
     * and has no sub-race to unlock, so it is never gated. And a player who IS the owning sub-race keeps their move
     * whatever the entitlement ledger says: selecting that sub-race required the unlock in the first place, so a
     * mismatch there means the bookkeeping has drifted (legacy saves, an admin race command), and the answer to
     * that is not to disarm somebody of the one move their race exists for.
     *
     * <p>{@link RaceUnlocks#hasRaceAccess} fails closed, so anything it cannot establish reads as locked.
     */
    private static boolean su$mayUse(ServerPlayer player, DragonMove move)
    {
        try
        {
            if (net.shurui.shuruisutilities.dragons.DragonRaces.OMEGA_RACE.equals(move.race))
                return true;
            String race = net.shurui.shuruisutilities.compat.dmz.ShadowDragonFormCompat.currentRace(player);
            if (race != null && race.equalsIgnoreCase(move.race))
                return true;
            if (net.shurui.shuruisutilities.corrupted.RaceUnlocks.hasRaceAccess(player, move.race))
                return true;
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    move.displayName + " needs its dragon unlocked."), true);
            return false;
        }
        catch (Throwable t)
        {
            // A failed check must not disarm a player mid-fight; fall through to DMZ's own race filter.
            return true;
        }
    }

    /** Whether the release was at full charge; says so on the action bar when it was not. */
    private static boolean su$fullyCharged(ServerPlayer caster, float charge)
    {
        if (charge >= FULL_CHARGE)
            return true;
        caster.displayClientMessage(
                net.minecraft.network.chat.Component.literal("Not charged: hold it to full."), true);
        return false;
    }

    /**
     * Turn a cast down: drop the held pose, take the charging ball away, and hand DMZ a failure.
     *
     * <p>ALL of that, every time, which is the point of it being one method. Suppressing DMZ's cast also stops DMZ
     * consuming the charging ball, so every refusal has to remove it or the ball sits on the caster for ever. That
     * was already known on the paths that SUCCEED (both of them discard it before returning true) but only one of
     * the four refusals did it, so releasing a shadow dragon move early, or firing one on an empty malice bar, left
     * an orbiting ball behind with nothing that would ever clear it. Releasing early is the common case, so this
     * was reachable several times a fight.
     *
     * <p>Every refusal goes through here now. A new one that forgets to clean up is the same bug again, so there is
     * deliberately nowhere else that writes {@code setReturnValue(false)}.
     */
    private static void su$refuse(ServerPlayer caster, CallbackInfoReturnable<Boolean> cir)
    {
        net.shurui.shuruisutilities.dragons.MoveChargeTracker.clearPose(caster);
        net.shurui.shuruisutilities.compat.dmz.ChargingProjectile.discardChargingFor(caster);
        cir.setReturnValue(false);
    }

    /**
     * The mini clone cast. Below a full charge it is left entirely to DMZ (no cancel), matching the "do nothing below
     * 1.0" rule. At or above a full charge it is a PUBLIC feature (2026-09-22) and is refused only when the tier's
     * allow-list withholds it ({@code !PublicContent.allows(FEATURE_MINI_CLONE)}) or the operator switch is off, and
     * when the caster is in a tournament character; otherwise it plays DMZ's BLOCK pose once and summons the clones,
     * suppressing DMZ's own (invisible) projectile.
     *
     * <p>The gate was {@code PublicContent.fullKeyOnlyDenied()}, which withholds the move from a keyless server. It
     * is now the allow-list gate, so a keyless server runs it
     * (the name is in {@code PUBLIC_FEATURES}); {@link net.shurui.shuruisutilities.core.config.Features#enabled}
     * still carries the operator switch.
     */
    private static void su$miniClone(ServerPlayer caster, float charge, CallbackInfoReturnable<Boolean> cir)
    {
        if (charge < 1.0f)
            return; // below full: let DMZ handle it normally, do not cancel

        if (!net.shurui.shuruisutilities.core.config.PublicContent.allows(
                        net.shurui.shuruisutilities.core.config.PublicContent.FEATURE_MINI_CLONE)
                || !net.shurui.shuruisutilities.core.config.Features.enabled(
                        net.shurui.shuruisutilities.core.config.Features.MINI_CLONE))
        {
            caster.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.dmz_ragnarok.core.miniclone.locked"), true);
            su$refuse(caster, cir);
            return;
        }

        if (net.shurui.shuruisutilities.character.TournamentCharBridge.active(caster))
        {
            caster.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.dmz_ragnarok.core.miniclone.tournament"), true);
            su$refuse(caster, cir);
            return;
        }

        // Drop the held BLOCK pose, then play it once as the cast. Suppressing DMZ's cast also means its charging
        // ball is never consumed, so it is discarded here like the dragon moves do.
        net.shurui.shuruisutilities.dragons.MoveChargeTracker.clearPose(caster);
        net.shurui.shuruisutilities.compat.dmz.MoveAnimation.fireRaw(caster,
                net.shurui.shuruisutilities.clone.MiniClone.CHARGE_ANIMATION);
        net.shurui.shuruisutilities.compat.dmz.ChargingProjectile.discardChargingFor(caster);

        if (net.shurui.shuruisutilities.clone.MiniClone.spawn(caster, charge))
            caster.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.dmz_ragnarok.core.miniclone.summoned"), true);
        cir.setReturnValue(true);
    }

    @Inject(method = "executeKiAttack", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$shadowDragonMove(LivingEntity caster, Level level, KiAttackData data, StatsData stats,
                                            float charge, CallbackInfoReturnable<Boolean> cir)
    {
        if (data == null || level == null || level.isClientSide())
            return;
        String id = data.getId();

        // Role techniques (God of Destruction) come first: they are gated on a TITLE, not a race, and they charge
        // their own cost, so they never go through the malice path below.
        // Not a charged release: let it pass through untouched so nothing of ours fires on a tap.
        if (charge <= MIN_CHARGE)
            return;

        // The mini clone technique: a full charge summons one clone, an overcharge to 175% (raw charge >= 1.74) a
        // second. Handled before the dragon/role paths because it has its own gating and pays no malice.
        if (net.shurui.shuruisutilities.clone.MiniClone.TECHNIQUE_ID.equals(id))
        {
            if (caster instanceof ServerPlayer cloneCaster)
                su$miniClone(cloneCaster, charge, cir);
            return;
        }

        net.shurui.shuruisutilities.god.RoleMove roleMove = net.shurui.shuruisutilities.god.RoleMove.byId(id);
        if (roleMove != null)
        {
            if (!(caster instanceof ServerPlayer roleCaster))
                return;
            // The god roles are the Ragnarok Key's (feature roles). Without it the cast is refused BEFORE anything of
            // ours runs (no pose, no animation, no charge discard), exactly like an unaffordable one.
            if (!net.shurui.shuruisutilities.api.key.RoleHooks.available())
            {
                su$refuse(roleCaster, cir);
                return;
            }
            if (!su$fullyCharged(roleCaster, charge))
            {
                su$refuse(roleCaster, cir);
                return;
            }
            // We animate these ourselves (MoveAnimation), so the projectile is no longer needed to carry the clip
            // and can be suppressed. Hakai must NOT lob a ball - it erases its target - and the sphere's orb is our
            // own trap, not a thrown one.
            // Drop the held charge pose FIRST, then play the release. Doing it the other way round sends a STOP
            // immediately after the fire clip and cancels the very animation it just started, which is why charge
            // animations played and firing animations never did.
            net.shurui.shuruisutilities.dragons.MoveChargeTracker.clearPose(roleCaster);
            net.shurui.shuruisutilities.compat.dmz.MoveAnimation.fire(roleCaster, roleMove.animation);
            // Suppressing DMZ's cast also stops it consuming the charging ball, so remove it or it sits on the
            // caster forever.
            net.shurui.shuruisutilities.compat.dmz.ChargingProjectile.discardChargingFor(roleCaster);
            net.shurui.shuruisutilities.api.key.RoleHooks.get().handleRoleMove(roleCaster, roleMove);
            cir.setReturnValue(true);
            return;
        }

        DragonMove move = DragonMove.byId(id);
        if (move == null)
            return; // not one of ours: leave DMZ's own technique handling completely alone
        if (!(caster instanceof ServerPlayer player))
            return;

        if (!su$mayUse(player, move))
        {
            su$refuse(player, cir);
            return;
        }

        if (!su$fullyCharged(player, charge))
        {
            su$refuse(player, cir);
            return;
        }

        if (!DragonMoveEffects.payFor(player))
        {
            su$refuse(player, cir);
            return;
        }

        // Stop the held charge pose BEFORE the release clip: the other order cancels the fire animation outright.
        net.shurui.shuruisutilities.dragons.MoveChargeTracker.clearPose(player);
        net.shurui.shuruisutilities.compat.dmz.MoveAnimation.fire(player, move.animation);

        boolean wantsDmzProjectile = DragonMoveEffects.cast(player, move);
        // Only Omega's ball is a real thrown projectile. Every other move suppresses DMZ's - and must also discard
        // the charging ball, because a suppressed cast never consumes it and it would sit on the caster's feet.
        if (!wantsDmzProjectile)
        {
            net.shurui.shuruisutilities.compat.dmz.ChargingProjectile.discardChargingFor(player);
            cir.setReturnValue(true);
        }
    }
}
