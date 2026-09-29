package net.shurui.shuruisutilities.compat.curios;

import java.util.Optional;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;

/**
 * Classload-safe read/write access to a player's equipped Curios slots, for features that stash and restore a
 * player's gear (guild raids, and anything else that needs the worn accessories). Reached only through
 * reflection so this class never hard-links the Curios API: it works with or without Curios present, returning
 * null when the mod is absent so callers simply skip the Curios part of a stash.
 *
 * <p>Same reflection shape the tournaments addon uses for the same job, kept here in SU's own compat package so
 * the core guild-raid vault depends on nothing outside SU + DMZ. Curios is a required dependency on the published
 * jar, but the ModList guard keeps a dev or stripped-down run from crashing when it is missing.
 */
public final class CuriosAccess
{
    private CuriosAccess()
    {
    }

    public static boolean isPresent()
    {
        return ModList.get() != null && ModList.get().isLoaded("curios");
    }

    /**
     * The combined equipped-Curios handler for this entity (read, clear and restore directly), or null when
     * Curios is absent or the entity has no curios inventory. Any reflection failure degrades to null rather than
     * throwing, so a Curios API shift never crashes a stash or a restore.
     */
    public static IItemHandlerModifiable getEquipped(LivingEntity entity)
    {
        if (!isPresent())
        {
            return null;
        }
        try
        {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object res = api.getMethod("getCuriosInventory", LivingEntity.class).invoke(null, entity);
            Optional<?> opt;
            if (res instanceof LazyOptional<?> lo)
            {
                opt = lo.resolve();
            }
            else if (res instanceof Optional<?> o)
            {
                opt = o;
            }
            else
            {
                return null;
            }
            if (opt.isEmpty())
            {
                return null;
            }
            Object handler = opt.get();
            Object equipped = handler.getClass().getMethod("getEquippedCurios").invoke(handler);
            return (IItemHandlerModifiable) equipped;
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
