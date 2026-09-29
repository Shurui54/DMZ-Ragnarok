package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.RenderType;

/**
 * Reads the private {@code state} of a composite render type.
 *
 * <h2>Why this exists</h2>
 * Cross-server ghosts are drawn barely visible by wrapping the buffer they render into (see
 * {@code GhostRender}). To show a solid texture translucently, the wrapper has to re-request the SAME texture
 * on a blending render type, which means it must recover the texture out of whatever render type the body,
 * armour and item layers ask for. A render type does not expose its texture, so this walks its composite state
 * to find it, via three accessors: this one for the state, {@link AccessorCompositeState} for the texture
 * shard, and {@link AccessorTextureStateShard} for the resource location itself.
 *
 * <p>{@code RenderType$CompositeRenderType} is package private, so it is targeted by name rather than by class
 * literal. Reflection is not an option here: it would need the runtime (SRG) field name, which only the mixin
 * refmap knows, so an accessor is both the correct tool and the one that stays valid across a remap.
 */
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface AccessorCompositeRenderType
{
    @Accessor("state")
    RenderType.CompositeState su$state();
}
