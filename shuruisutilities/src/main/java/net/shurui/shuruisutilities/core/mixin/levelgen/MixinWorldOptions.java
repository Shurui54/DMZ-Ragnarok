package net.shurui.shuruisutilities.core.mixin.levelgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.levelgen.WorldOptions;

/**
 * Forces structure generation on for every dimension, regardless of the persisted world flag.
 *
 * <p>Root cause this addresses: the live world's level.dat carries WorldGenSettings generate_features=0
 * (i.e. {@link WorldOptions#generateStructures()} returns false). When that flag is false,
 * ChunkGeneratorStructureState builds an empty structure set for every dimension, so no structures
 * generate in any newly generated chunk (overworld, Nether, End, and custom dims alike). This server
 * suite always wants structures, so we override the accessor to report true everywhere.</p>
 *
 * <p>{@code WorldOptions} is a stable vanilla class (a plain class, not a record) whose
 * {@code generateStructures()} boolean accessor is read by the structure-state builder. Returning true
 * at HEAD makes structures generate without having to edit and re-save the world's level.dat.</p>
 */
@Mixin(WorldOptions.class)
public class MixinWorldOptions
{
    /**
     * Force the generate_features flag to read as enabled.
     *
     * @author Shurui
     * @reason This server suite must generate structures in all dimensions even though the live
     *         level.dat has generate_features=0. Overriding the accessor is simpler and safer than
     *         rewriting the saved world settings.
     */
    @Inject(method = "generateStructures", at = @At(value = "HEAD"), cancellable = true)
    private void suForceStructuresOn(CallbackInfoReturnable<Boolean> cir)
    {
        cir.setReturnValue(true);
    }
}
