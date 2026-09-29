package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.shurui.shuruisutilities.god.RagnarokKiWeapon;

/**
 * Puts our ki weapon types on the list DragonMineZ is willing to summon.
 *
 * <h2>Why nothing appeared when you picked one</h2>
 * Our entries in the weapon wheel select through DMZ's own {@code SelectKiWeaponC2S}, which was supposed to make them
 * behave exactly like blade, scythe and clawlance. It very nearly does, except that the server handler validates the
 * requested type against {@code CombatConfig.getKiWeaponTypes()} before it does anything:
 *
 * <pre>if (!type.isEmpty() &amp;&amp; !types.contains(type.toLowerCase())) return;</pre>
 *
 * <p>Our types are not in that config, so the packet was accepted, validated, and dropped on the floor. No weapon, no
 * error, no log line: picking the staff simply did nothing, which is exactly what it looked like.
 *
 * <p>Appending here rather than at the call site is deliberate. It is one place, it fixes the summon and the deselect
 * and the "which weapon am I holding" comparison at once, and it leaves the rest of DMZ's validation intact: the
 * {@code kimanipulation} skill check in front of it still applies, so this widens the allowed SET without opening the
 * gate to anyone who has not earned it.
 *
 * <p>A copy is returned rather than mutating DMZ's list, because that list comes from a config object that is reloaded
 * and reused; adding to it in place would stack duplicates on every reload and would write our names into whatever
 * DMZ does with its own config elsewhere.
 *
 * <p>{@code require = 0}: if DMZ renames this the weapons stop summoning again, which is visible, rather than the
 * client or server failing to start.
 */
@Mixin(targets = "com.dragonminez.common.config.CombatConfig", remap = false)
public class MixinDmzKiWeaponTypes
{
    @Inject(method = "getKiWeaponTypes", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void su$allowRagnarokKiWeapons(CallbackInfoReturnable<List<String>> cir)
    {
        try
        {
            List<String> original = cir.getReturnValue();
            List<String> widened = new ArrayList<>(original == null ? List.of() : original);
            for (RagnarokKiWeapon weapon : RagnarokKiWeapon.values())
            {
                // Lowercase because that is what the handler compares against: it lowercases the REQUESTED type and
                // then asks contains(), so an entry cased any other way would never match.
                String type = weapon.type.toLowerCase(java.util.Locale.ROOT);
                if (!widened.contains(type))
                    widened.add(type);
            }
            cir.setReturnValue(widened);
        }
        catch (Throwable ignored)
        {
            // Never let this break DMZ's own weapons; their list stands as it was.
        }
    }
}
