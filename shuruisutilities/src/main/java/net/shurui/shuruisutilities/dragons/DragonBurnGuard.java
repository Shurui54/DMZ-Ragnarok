package net.shurui.shuruisutilities.dragons;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Suppresses vanilla's own fire damage for anyone burning because of Nuova's aura.
 *
 * <p>The aura sets victims genuinely alight so vanilla draws the burning overlay and plays the burn sounds, which is
 * exactly the presentation asked for and costs nothing to build. The side effect is that vanilla then also ticks its
 * own fire damage, which would land on top of the move's own figure and quietly break the rule that a move deals
 * {@code (melee + strike + ki) / 2} in TOTAL. Cancelling it here keeps the flames as presentation only.
 *
 * <p>Scoped as narrowly as possible: only fire-typed damage, and only for an entity the aura is currently burning.
 * Fire from any other source, on anyone else, is untouched, so this cannot make a player fireproof in general.
 *
 * <p>Registered by hand on the Forge bus from the mod's main class.
 */
public final class DragonBurnGuard
{
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event)
    {
        LivingEntity victim = event.getEntity();
        if (victim == null || !DragonMoveNuova.isBurningFromAura(victim))
            return;
        if (event.getSource().is(DamageTypeTags.IS_FIRE))
            event.setCanceled(true);
    }
}
