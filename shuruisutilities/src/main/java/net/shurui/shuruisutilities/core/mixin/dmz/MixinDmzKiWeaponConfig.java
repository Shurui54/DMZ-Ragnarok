package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.Locale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.common.config.CombatConfig;

import net.shurui.shuruisutilities.god.RagnarokKiWeapon;

/**
 * Gives our summoned weapons a DragonMineZ combat identity, which is where their pose, their swing animation and
 * their sounds all come from.
 *
 * <h2>Why they were silent and posed like bare fists</h2>
 * {@code CombatAnimationResolver.resolvePlayerPose} does NOT use the held-item registry when a ki weapon is up. It
 * branches:
 *
 * <pre>isKiWeaponActive(player) ? PlayerAttackHelper.getKiWeaponAttributes(player)
 *                           : WeaponRegistry.getAttributes(player.getMainHandItem())</pre>
 *
 * <p>and a summoned weapon is not an item, so the hand is empty and only the first branch ever runs. That branch
 * reads the player's ki weapon TYPE, asks {@code CombatConfig.getKiWeaponConfig(type)} for it, takes that config's
 * {@code weaponCombo} id and resolves the attributes from {@code WeaponRegistry} by ID. Our types have no entry in
 * that config, so there was no combo, no attributes, and therefore no pose and no sound.
 *
 * <p>That also means {@code DragonTechniqueBridge.registerWeaponPoses} could never have worked for these: it registers
 * into {@code WeaponRegistry} under an ITEM id, which is the branch a summoned weapon never takes.
 *
 * <h2>Standing in rather than inventing</h2>
 * Each of ours answers with the config of the DMZ ki weapon it should fight like, so the combo, the animation set, the
 * swing sounds and the timings are all DMZ's own and stay in step with them. The Angel's staff stands in as the
 * CLAWLANCE, DMZ's polearm, which is the spear-style moveset. If it wants to be a different weapon later it is one
 * word here and nothing else.
 *
 * <p>Building a {@code KiWeaponConfig} by hand instead would mean guessing at damage, ki cost and attack speed for a
 * weapon DMZ already balances, and would drift the moment they retune theirs.
 *
 * <p>The recursion terminates because the stand-in is never one of ours: asking for "scythe" falls straight through.
 */
@Mixin(value = CombatConfig.class, remap = false)
public class MixinDmzKiWeaponConfig
{
    @Inject(method = "getKiWeaponConfig", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$standInForRagnarokWeapons(String type,
            CallbackInfoReturnable<CombatConfig.KiWeaponConfig> cir)
    {
        try
        {
            String standIn = su$standInFor(type);
            if (standIn == null)
                return;
            cir.setReturnValue(((CombatConfig) (Object) this).getKiWeaponConfig(standIn));
        }
        catch (Throwable ignored)
        {
            // Never let this break DMZ's own weapons; theirs resolve as they always did.
        }
    }

    /** The DMZ ki weapon each of ours fights like, or null when the type is not one of ours. */
    private static String su$standInFor(String type)
    {
        if (type == null || type.isEmpty())
            return null;
        String lower = type.toLowerCase(Locale.ROOT);
        for (RagnarokKiWeapon weapon : RagnarokKiWeapon.values())
        {
            if (!weapon.type.toLowerCase(Locale.ROOT).equals(lower))
                continue;
            // The staff wants the polearm.
            return "clawlance";
        }
        return null;
    }
}
