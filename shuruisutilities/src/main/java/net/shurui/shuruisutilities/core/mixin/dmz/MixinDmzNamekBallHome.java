package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.Set;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.compat.dmz.NamekBallHome;

/**
 * Points DMZ's Namek ball set at the dimension {@link NamekBallHome} resolves (the open Namek world by default).
 *
 * <p>A set's dimension list is the one input DMZ reads for where it scatters, where it first spawns and which levels
 * the radar gathers from. The definition's constructor copies the list ({@code Set.copyOf}) during DMZ's static
 * initialiser, long before the config or the Ragnarok Key are loaded, so the list cannot be swapped by
 * re-registering the set; the two read accessors are answered here instead, for the {@code namek} set only and
 * only while {@link NamekBallHome#relocated()} is true. Every other set, and Namek with the relocation off, reads
 * DMZ's own stored list unchanged.
 *
 * <p>{@code remap = false}: DMZ's own class, method and field names. {@code require = 0}: a DMZ rename degrades to
 * DMZ's own behaviour (Namek balls on dragonminez:namek) rather than a load crash.
 */
@Mixin(targets = "com.dragonminez.common.dragonball.DragonBallSetDefinition", remap = false)
public abstract class MixinDmzNamekBallHome
{
    @Shadow(remap = false)
    @Final
    private String id;

    @Inject(method = "getValidDimensions", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$namekHomeDimensions(CallbackInfoReturnable<Set<ResourceLocation>> cir)
    {
        try
        {
            if (NamekBallHome.SET_ID.equals(this.id) && NamekBallHome.relocated())
                cir.setReturnValue(Set.of(NamekBallHome.current()));
        }
        catch (Throwable ignored)
        {
        }
    }

    @Inject(method = "supportsDimension", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$namekHomeSupports(ResourceKey<Level> dimension, CallbackInfoReturnable<Boolean> cir)
    {
        try
        {
            if (dimension != null && NamekBallHome.SET_ID.equals(this.id) && NamekBallHome.relocated())
                cir.setReturnValue(NamekBallHome.current().equals(dimension.location()));
        }
        catch (Throwable ignored)
        {
        }
    }
}
