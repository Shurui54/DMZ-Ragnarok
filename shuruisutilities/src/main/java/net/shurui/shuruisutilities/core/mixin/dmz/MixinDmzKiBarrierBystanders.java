package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.combat.CombatBystanders;

/**
 * Stops DragonMineZ's ki shield from shoving the NPCs that exist to stand still.
 *
 * <h2>The bug this fixes</h2>
 * {@code KiBarrierEntity.pushEntitiesAway()} runs once a tick while the shield is up. For every living entity inside
 * the shield's inflated box (bar the anchor and the owner) it calls {@code setDeltaMovement} outward and then may hurt
 * it. It uses {@code setDeltaMovement} rather than {@code knockback}, so {@code KNOCKBACK_RESISTANCE} never gets a say:
 * a DragonMineZ master, a suite raid or tournament host, a prestige NPC, a region NPC or a planet garrison defender
 * standing next to a raised shield is pushed off its spot every tick, and non-combat NPCs have no way to walk back, so
 * a player can pin one against a shield and slide it off a platform or out of a shop for good.
 *
 * <h2>The interception</h2>
 * A {@link Redirect} on the single {@code Entity.setDeltaMovement(Vec3)} INVOKE inside {@code pushEntitiesAway}. When
 * the target is a protected bystander (see {@link CombatBystanders#isProtected}) the call is dropped, so the entity
 * keeps its own motion and is never displaced. Anything else, a player, a real fighter, a hostile mob, is pushed
 * exactly as before. Only the movement is suppressed: the shield's damage branch runs later on the same entity and is
 * left untouched, so a shield still hurts what it always hurt.
 *
 * <p>{@code remap = false} on the {@code @Mixin} because the target resolves against DragonMineZ's own class name,
 * matching SU's other DMZ mixins; {@code remap = true} on the redirect because {@code setDeltaMovement} is a vanilla
 * method whose reference must map to SRG in production, while the DMZ method selector {@code pushEntitiesAway} has no
 * mapping and stays literal. {@code require = 0} per the suite rule that a mixin into a DMZ class degrades rather than
 * crashes if the target ever moves.
 */
@Mixin(targets = "com.dragonminez.common.init.entities.ki.KiBarrierEntity", remap = false)
public abstract class MixinDmzKiBarrierBystanders
{
    @Redirect(
            method = "pushEntitiesAway",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"),
            require = 0,
            remap = true)
    private void su$skipBystanderShove(Entity entity, Vec3 movement)
    {
        if (entity instanceof LivingEntity le && CombatBystanders.isProtected(le))
            // A protected bystander keeps its own motion, so the shield never moves it.
            return;
        entity.setDeltaMovement(movement);
    }
}
