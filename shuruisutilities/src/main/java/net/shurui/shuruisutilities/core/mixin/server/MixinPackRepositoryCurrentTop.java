package net.shurui.shuruisutilities.core.mixin.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

/**
 * Keeps the built-in "DMZ Ragnarok dimension types" pack (WorldgenCurrentPack) at the very top of the active pack list.
 *
 * <p>Vanilla keeps a world's saved pack order, and a pack that is already listed is never moved, so a world whose own
 * worldgen shim datapack was saved above it kept loading the shim's older dimension types on the first start after an
 * update (the space column change of 1.6.1 applied only after a second restart). Moving the pack to the top after every
 * rebuild means this build's dimension types always win, from the first start.
 */
@Mixin(PackRepository.class)
public abstract class MixinPackRepositoryCurrentTop
{
    private static final String CURRENT_PACK_ID = "dmz_ragnarok_dimension_types";

    @Inject(method = "rebuildSelected", at = @At("RETURN"), cancellable = true, require = 0)
    private void dmzr$currentDimensionTypesOnTop(Collection<String> ids, CallbackInfoReturnable<List<Pack>> cir)
    {
        List<Pack> selected = cir.getReturnValue();
        if (selected == null || selected.isEmpty())
        {
            return;
        }
        Pack current = null;
        for (Pack pack : selected)
        {
            if (CURRENT_PACK_ID.equals(pack.getId()))
            {
                current = pack;
                break;
            }
        }
        if (current == null || selected.get(selected.size() - 1) == current)
        {
            return;
        }
        List<Pack> reordered = new ArrayList<>(selected.size());
        for (Pack pack : selected)
        {
            if (pack != current)
            {
                reordered.add(pack);
            }
        }
        reordered.add(current);   // the last pack is the highest priority
        cir.setReturnValue(reordered);
    }
}
