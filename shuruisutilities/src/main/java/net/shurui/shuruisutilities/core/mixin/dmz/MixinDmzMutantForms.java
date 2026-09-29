package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Player;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Effects;


/**
 * Removes the mutant trait's access to legendary forms, so a mutant reaches transformations exactly as a non-mutant
 * does. Two seams in {@code TransformationsHelper}, both keyed on the mutant effect:
 *
 * <ul>
 *   <li>{@code hasMutantLegendaryAccess} is the gate {@code hasFormSkillAccess} consults to shave one skill level
 *       off a legendary form's unlock requirement for a mutant. Forced to false, so the real skill level is
 *       required.
 *   <li>{@code getGroupWithFirstAvailableForm} reads the mutant effect inline to PREFER a legendary form type when
 *       auto-picking the first available form. That read is redirected to false so the preference matches a
 *       non-mutant's. The redirect still honours any non-mutant {@code hasEffect} query in the same method, forcing
 *       only the {@code mutant} lookup, so nothing else in the auto-pick changes.
 * </ul>
 *
 * <p>The trait is otherwise untouched: {@code isMutant} still answers true and the lottery still runs. Only the
 * legendary shortcut is gone.
 *
 * <p>PRIVATE since batch M: both seams are in force exactly when the logical server holds the Ragnarok Key, so a
 * keyless server keeps DMZ's shortcut. The side is decided per call from the {@code StatsData}'s own player, because
 * this class is applied on both sides and the form screens ask these questions on the client (which reads the
 * server-synced {@code MutantHooks.clientNerfActive()}).
 *
 * <p>Since S20 the decision lives in the Ragnarok Key: the server asks {@code MutantHooks.nerfActive()} (keyless:
 * off) and the client reads {@code MutantHooks.clientNerfActive()}, that same server answer as synced at login.
 *
 * <p>{@code require = 0} per the standing rule: a DMZ rename degrades to the mutant shortcut staying, never a broken
 * load. The inject captures {@code hasMutantLegendaryAccess}'s parameters exactly (argument capture is all or
 * nothing), and the redirect appends the enclosing method's one parameter after its own.
 */
@Mixin(targets = "com.dragonminez.common.util.TransformationsHelper", remap = false)
public abstract class MixinDmzMutantForms
{
    @Inject(method = "hasMutantLegendaryAccess", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$noMutantLegendaryAccess(StatsData stats, String groupName,
            CallbackInfoReturnable<Boolean> cir)
    {
        if (su$nerfOn(stats))
            cir.setReturnValue(false);
    }

    @Redirect(method = "getGroupWithFirstAvailableForm",
            at = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/common/stats/character/Effects;hasEffect(Ljava/lang/String;)Z"),
            require = 0, remap = false)
    private static boolean su$noMutantLegendaryPreference(Effects effects, String name, StatsData stats)
    {
        // Force only the mutant lookup to false, and only while the nerf is on; leave any other effect query in this
        // method behaving normally, so a mutant's auto-picked first form is the one a non-mutant would get, and
        // nothing else shifts.
        if ("mutant".equals(name) && su$nerfOn(stats))
            return false;
        return effects.hasEffect(name);
    }

    /** Is the nerf in force for the side this call is on? See {@code MixinDmzMutantStats} for the reasoning. */
    private static boolean su$nerfOn(StatsData stats)
    {
        Player player = stats == null ? null : stats.getPlayer();
        boolean clientSide = player != null && player.level().isClientSide;
        return clientSide ? net.shurui.shuruisutilities.api.key.MutantHooks.clientNerfActive()
                : net.shurui.shuruisutilities.api.key.MutantHooks.nerfActive();
    }
}
