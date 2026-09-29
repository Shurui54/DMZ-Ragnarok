package net.shurui.shuruisutilities.core.mixin.client;

import java.util.SortedMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.BufferBuilder;

import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;

import net.shurui.shuruisutilities.runes.client.RuneGlintTypes;

/**
 * Gives the runes' coloured sheens their own buffers, alongside the ones vanilla keeps for its own glint.
 *
 * <p>This is not an optimisation, it is the difference between the sheen showing and not showing. A glint draws
 * with an equal-depth test and no depth write, so it is only visible where the item has ALREADY written its depth,
 * which means it has to be drawn after the item. {@code MultiBufferSource.BufferSource} flushes every render type
 * it has no fixed builder for as soon as a different one is asked for, and it flushes the fixed ones last. Vanilla's
 * glint is fixed and the item sheet is not, which is exactly what puts the glint after the item. A sheen without a
 * fixed builder would be flushed FIRST, fail the depth test against an item that had not been drawn yet, and
 * silently render nothing.
 *
 * <p>The map is the same instance the buffer source was constructed around, so adding to it after the constructor
 * returns is enough; it is insertion ordered, and appending at the end keeps our sheens after every vanilla type.
 */
@Mixin(RenderBuffers.class)
public class MixinRenderBuffersRuneGlint
{
    @Shadow
    @Final
    private SortedMap<RenderType, BufferBuilder> fixedBuffers;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void su$addRuneGlintBuffers(CallbackInfo ci)
    {
        for (RenderType type : RuneGlintTypes.all())
            fixedBuffers.put(type, new BufferBuilder(type.bufferSize()));
    }
}
