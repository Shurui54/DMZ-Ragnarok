package net.shurui.shuruisutilities.compat.customnpcs;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import noppes.npcs.entity.EntityNPCInterface;

/**
 * The only class here that names a CustomNPCs type. Never touched unless the mod is loaded (see {@link CnpcCombat}).
 */
final class CnpcCombatImpl
{
    private CnpcCombatImpl() {}

    /**
     * CustomNPCs' own "what do I do when I am attacked" setting, which is the honest answer to whether an NPC can
     * fight back.
     *
     * <p>{@code EntityNPCInterface.onAttack} sets its attacker as its target for every value of {@code ais.onAttack}
     * EXCEPT 3, which is the option meaning do nothing. So 3 is a shopkeeper, a quest giver, a wandering villager
     * NPC: something that will stand there and be hit. Everything else will turn and fight.
     *
     * <p>Read from the NPC's own configuration rather than guessed from its stats, so an NPC an operator has
     * deliberately made passive is treated as passive whatever damage numbers it happens to carry.
     */
    static Boolean retaliates(Entity entity)
    {
        if (!(entity instanceof EntityNPCInterface npc))
            return null;
        return npc.ais != null && npc.ais.onAttack != 3;
    }

    /**
     * Turn a Custom NPC onto {@code target} the same way being hit would. {@code onAttack} sets the target for every
     * {@code ais.onAttack} mode except 3 (do nothing), so a shopkeeper stays put while a fighter engages, and it
     * no-ops when the NPC is already attacking. Returns whether the entity was a Custom NPC at all.
     */
    static boolean provoke(Entity entity, LivingEntity target)
    {
        if (!(entity instanceof EntityNPCInterface npc))
            return false;
        npc.onAttack(target);
        return true;
    }
}
