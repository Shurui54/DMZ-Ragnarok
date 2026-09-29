package net.shurui.shuruisutilities.compat.customnpcs;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

/**
 * Whether a Custom NPC is one that fights back.
 *
 * <p>CustomNPCs is optional, so nothing here names a {@code noppes} type: the guard answers on its own and only
 * calls through to {@link CnpcCombatImpl} once the mod is known to be present, which is what keeps the class from
 * being loaded on a server without it.
 */
public final class CnpcCombat
{
    private CnpcCombat() {}

    /**
     * {@code TRUE} when this entity is a Custom NPC that will fight back, {@code FALSE} when it is one that will
     * not, and {@code null} when it is not a Custom NPC at all (or the mod is absent), so a caller can tell "this
     * is a passive NPC" apart from "this is not an NPC and I should decide some other way".
     */
    public static Boolean retaliates(Entity entity)
    {
        if (entity == null || !ModList.get().isLoaded("customnpcs"))
            return null;
        try
        {
            return CnpcCombatImpl.retaliates(entity);
        }
        catch (Throwable t)
        {
            // A version whose internals moved answers "not an NPC", so the caller falls back to its ordinary rule
            // rather than every NPC silently becoming erasable or every one becoming immune.
            return null;
        }
    }

    /**
     * Provoke a Custom NPC into fighting {@code target} (pack aggro: a regionmate near it was attacked). Routes
     * through the NPC's own {@code onAttack}, so it respects the NPC's configured attack mode: a shopkeeper (mode 3)
     * stays passive, everything else turns and fights.
     *
     * @return {@code true} when this entity IS a Custom NPC (whether or not it chose to fight, the caller should not
     *         also call vanilla setTarget), {@code false} when it is not a Custom NPC or the mod is absent, so the
     *         caller falls back to the vanilla target field.
     */
    public static boolean provoke(Entity entity, LivingEntity target)
    {
        if (entity == null || target == null || !ModList.get().isLoaded("customnpcs"))
            return false;
        try
        {
            return CnpcCombatImpl.provoke(entity, target);
        }
        catch (Throwable t)
        {
            return false;
        }
    }
}
