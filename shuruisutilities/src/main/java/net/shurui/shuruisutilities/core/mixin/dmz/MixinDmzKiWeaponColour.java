package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.Locale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.common.combat.logic.weapon.KiWeaponHelper;

import net.shurui.shuruisutilities.god.RagnarokKiWeapon;

/**
 * Stops our summoned weapons being multiplied by a ki colour.
 *
 * <p>The other half of {@code MixinDmzKiWeaponRender}. DMZ resolves a colour per ki weapon type and hands it to
 * {@code renderRecursively} as the red, green and blue it draws with. For a blank white energy blade that colour IS
 * the weapon. For a painted staff it is a filter over the artwork, and a strong one, so the texture reads as one
 * flat hue whatever is actually drawn on it.
 *
 * <p>White leaves the texture exactly as painted, since the tint is a multiply. Alpha is untouched: it is not part
 * of what this returns.
 *
 * <p>Only ours. DMZ's own three keep their colours, which is the entire point of having them.
 */
@Mixin(value = KiWeaponHelper.class, remap = false)
public class MixinDmzKiWeaponColour
{
    private static final float[] SU_UNTINTED = { 1.0F, 1.0F, 1.0F };

    @Inject(method = "resolveColorForType", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void su$noTintForRagnarokWeapons(String type, float[] fallback,
            CallbackInfoReturnable<float[]> cir)
    {
        try
        {
            if (type == null || type.isEmpty())
                return;
            String lower = type.toLowerCase(Locale.ROOT);
            for (RagnarokKiWeapon weapon : RagnarokKiWeapon.values())
            {
                if (weapon.type.toLowerCase(Locale.ROOT).equals(lower))
                {
                    // A fresh array each time: the caller is free to keep or mutate what it is given, and handing
                    // out one shared instance would let it write into every future answer.
                    cir.setReturnValue(SU_UNTINTED.clone());
                    return;
                }
            }
        }
        catch (Throwable ignored)
        {
            // DMZ's own answer stands.
        }
    }
}
