package net.shurui.shuruisutilities.dragons;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.compat.dmz.DmzProjectile;

/**
 * Applies a move's effects when ITS OWN projectile lands.
 *
 * <p>DMZ's ki projectiles carry the id of the technique that fired them ({@code AbstractKiProjectile.getTechniqueId}),
 * so a landing ball can be matched straight back to the move without tagging anything. The projectile moves are
 * therefore left entirely to DMZ - it launches, steers and renders them - and only the effect on impact is ours.
 *
 * <p>Registered by hand on the Forge bus from the mod's main class.
 */
public final class DragonProjectileHit
{
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event)
    {
        Entity direct = event.getSource().getDirectEntity();
        if (direct == null)
            return;
        String techniqueId = DmzProjectile.techniqueIdOf(direct);
        DragonMove move = DragonMove.byId(techniqueId);
        if (move == null)
            return;

        LivingEntity victim = event.getEntity();
        if (victim == null || DragonMoveEffects.isImmuneShadowDragon(victim))
        {
            // Shadow dragons shrug off their own kind's attacks, here as everywhere else.
            event.setCanceled(true);
            return;
        }
        if (!(victim.level() instanceof ServerLevel level))
            return;

        Entity owner = DmzProjectile.ownerOf(direct);
        LivingEntity caster = owner instanceof LivingEntity living ? living : null;

        switch (move)
        {
            case MINUS_ENERGY_POWER_BALL -> DragonMoveOmega.onHit(caster, victim, level);
            default -> { /* the other moves are area effects and never land a projectile */ }
        }
    }
}
