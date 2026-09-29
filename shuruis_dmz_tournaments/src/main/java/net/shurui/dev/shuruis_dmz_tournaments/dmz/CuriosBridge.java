package net.shurui.dev.shuruis_dmz_tournaments.dmz;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.Optional;

// reflection access to a player's equipped Curios slots so we work with or without Curios. returns the
// combined equipped handler (read/clear/restore directly) or null when Curios is absent.
public final class CuriosBridge {
    private CuriosBridge() {}

    public static boolean isPresent() {
        return ModList.get() != null && ModList.get().isLoaded("curios");
    }

    public static IItemHandlerModifiable getEquipped(LivingEntity entity) {
        if (!isPresent()) return null;
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object res = api.getMethod("getCuriosInventory", LivingEntity.class).invoke(null, entity);
            Optional<?> opt;
            if (res instanceof LazyOptional<?> lo) opt = lo.resolve();
            else if (res instanceof Optional<?> o) opt = o;
            else return null;
            if (opt.isEmpty()) return null;
            Object handler = opt.get();
            Object equipped = handler.getClass().getMethod("getEquippedCurios").invoke(handler);
            return (IItemHandlerModifiable) equipped;
        } catch (Throwable t) {
            return null;
        }
    }
}
