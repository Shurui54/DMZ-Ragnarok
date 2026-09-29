package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Reads the {@code textureState} of a composite render type's state. Second of the three hops the ghost buffer
 * wrapper uses to recover a render type's texture; see {@link AccessorCompositeRenderType} for the why.
 *
 * <p>The declared field type is the empty (no texture) shard; a render type that actually carries a texture
 * holds a {@code TextureStateShard}, which is checked for and read by {@link AccessorTextureStateShard}.
 */
@Mixin(RenderType.CompositeState.class)
public interface AccessorCompositeState
{
    @Accessor("textureState")
    RenderStateShard.EmptyTextureStateShard su$textureState();
}
