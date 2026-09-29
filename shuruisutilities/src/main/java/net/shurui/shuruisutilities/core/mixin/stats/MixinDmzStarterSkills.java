package net.shurui.shuruisutilities.core.mixin.stats;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.stats.StatsData;

import net.shurui.shuruisutilities.compat.dmz.StarterSkills;

/**
 * Hands a new Spiritualist or Cleric their Ki Control skill at the moment the character is committed.
 *
 * <p>WHY here. {@code initializeWithRaceAndClass} is where DMZ actually writes the chosen race and class onto the
 * player and seeds their base stats; {@code CreateCharacterC2S.handle} only defers to it inside an
 * {@code enqueueWork} lambda, so a TAIL injection on the packet handler would run BEFORE the character existed.
 * By the tail of this method the class is set and the skill map is live, which is exactly what
 * {@link StarterSkills} needs.
 *
 * <p>No target parameters are captured. The method takes nineteen, none of which we need: the committed class is
 * read back off the object, which is both shorter and far less brittle than mirroring a nineteen-argument signature
 * that DMZ may extend. Per the standing rule, argument capture is all or nothing, and this takes none.
 *
 * <p>remap=false: the target and method are DMZ's own names. require=0 per the standing rule, so if DMZ renames or
 * reshapes this method the injector degrades to "no starting skill", which is DMZ's own behaviour, rather than
 * crashing mod load. {@link StarterSkills#applyTo} performs the key-gate and class checks and is safe to call for
 * any class.
 */
@Mixin(targets = "com.dragonminez.common.stats.StatsData", remap = false)
public abstract class MixinDmzStarterSkills
{
    @Inject(method = "initializeWithRaceAndClass", at = @At("TAIL"), require = 0, remap = false)
    private void su$grantStarterSkills(CallbackInfo ci)
    {
        try
        {
            StarterSkills.applyTo((StatsData) (Object) this);
        }
        catch (Throwable ignored)
        {
            // character creation must never fail over a starting perk: worst case the player buys Ki Control
        }
    }
}
