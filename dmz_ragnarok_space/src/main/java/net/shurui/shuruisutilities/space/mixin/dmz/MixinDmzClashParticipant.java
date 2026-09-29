package net.shurui.shuruisutilities.space.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.entity.LivingEntity;

import net.shurui.shuruisutilities.space.PlanetBusterModule;

import com.dragonminez.common.init.entities.ki.AbstractKiProjectile;

/**
 * Makes a PLANET clash weigh the attacker's BATTLE POWER, not just their ki damage, so a melee / STR build can bust a
 * world instead of always losing to the ki-damage-only clash weight. Nothing else is touched: an ordinary player-vs-player
 * or player-vs-NPC clash keeps DragonMineZ's exact behaviour.
 *
 * <h2>What DMZ does, and why melee loses</h2>
 * {@code ClashParticipant}'s constructor assigns its one and only strength number ONCE:
 * {@code this.statPower = Math.max(1.0, (double) beam.getKiDamage());}. {@code AbstractKiProjectile.getKiDamage()} is
 * PWR-only, so a build that invested in STR / SKP / RES contributes nothing to the struggle. The field is {@code final}
 * with no setter, and {@code npcAccuracy} is derived from it on the very next line, so intercepting this one INVOKE is the
 * whole fix and the accuracy term follows for free.
 *
 * <h2>The interception</h2>
 * A {@link Redirect} on the {@code getKiDamage()} INVOKE inside the constructor. {@code @Inject} at TAIL cannot work (the
 * field is final and already assigned), {@code @ModifyVariable} cannot work (it is a field, not a local). The redirect
 * captures the constructor's two arguments (the beam and the owner) after its own instance parameter, so the firing
 * entity is in hand without any further lookup.
 *
 * <h2>Scoped to planet clashes only</h2>
 * {@code ClashParticipant} knows only its own beam and owner at construction, never its opponent, so it cannot ask "is
 * this a planet clash". Instead the redirect asks SU's own tracker: {@link PlanetBusterModule#isPlanetAttack} is true only
 * for a projectile the buster is steering at a planet (a giant ball or a firing beam). So:
 * <ul>
 *   <li>the tracked planet attack -> return the BP-blended power (see {@link PlanetBusterModule#planetClashPower});</li>
 *   <li>the planet DEFENDER's answering wave -> not tracked, so it returns the raw {@code getKiDamage()}, which is the
 *   planet's toughness, keeping both sides of a planet clash on the SAME ki-damage scale;</li>
 *   <li>every other clash (all normal PvP / PvE) -> not tracked, so it returns the raw {@code getKiDamage()} unchanged,
 *   byte-for-byte the value DragonMineZ would have used.</li>
 * </ul>
 *
 * <p>{@code remap = false}: the {@code @Mixin} target resolves against DragonMineZ's own names, matching SU's other DMZ
 * mixins. The redirected {@code getKiDamage()F} is a DMZ method, so it is remap = false too. {@code require = 0} so a DMZ
 * reshape of the constructor degrades to a no-op rather than crashing the client. Every DMZ-internal access inside the
 * redirect and inside {@link PlanetBusterModule#planetClashPower} is wrapped in {@code try/catch (Throwable)} and FAILS
 * OPEN by returning the raw ki damage, so an API drift can never make planets unbustable or trivially bustable.</p>
 *
 * <p>This mixin is server-side only in practice: DragonMineZ's {@code BeamClashManager} constructs {@code ClashParticipant}
 * only when the tick's level is a {@code ServerLevel}, so there is no client-side clash weight to keep in sync.</p>
 */
@Mixin(targets = "com.dragonminez.common.combat.clash.ClashParticipant", remap = false)
public abstract class MixinDmzClashParticipant
{
    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/common/init/entities/ki/AbstractKiProjectile;getKiDamage()F"),
            require = 0)
    private float su$planetClashPower(AbstractKiProjectile beam, AbstractKiProjectile ctorBeam, LivingEntity owner)
    {
        // the raw ki damage is exactly the value the constructor would have used; every non-planet clash returns it
        // unchanged so DragonMineZ's own balance is untouched.
        float raw = beam.getKiDamage();
        try
        {
            if (owner != null && PlanetBusterModule.isPlanetAttack(beam))
            {
                return (float) PlanetBusterModule.planetClashPower(owner, raw);
            }
        }
        catch (Throwable ignored)
        {
            // fail open: any drift or statless owner keeps the raw ki damage, so a planet is never made unbustable
            // or trivially bustable by a broken read.
        }
        return raw;
    }
}
