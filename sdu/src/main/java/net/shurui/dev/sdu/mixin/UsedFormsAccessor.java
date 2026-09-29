package net.shurui.dev.sdu.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

// Exposes UsedForms' private backing map so we can drop a whole tombstoned form group from a player's usage
// history. UsedForms has no public remove (only getFormGroup/putForm/clear), and after an NBT load its
// per-group lists are immutable, so the group entry must be removed from the map itself. Literal name,
// remap=false (DMZ class).
@Mixin(targets = "com.dragonminez.common.stats.extras.UsedForms", remap = false)
public interface UsedFormsAccessor {

    @Accessor("usedForms")
    Map<String, List<String>> sdu$usedForms();
}
