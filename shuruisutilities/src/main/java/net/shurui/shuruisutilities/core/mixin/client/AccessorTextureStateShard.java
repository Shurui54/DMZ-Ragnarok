package net.shurui.shuruisutilities.core.mixin.client;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;

/**
 * Reads the texture {@code ResourceLocation} of a texture state shard. Last of the three hops the ghost buffer
 * wrapper uses to recover a render type's texture; see {@link AccessorCompositeRenderType} for the why.
 *
 * <p>The field is an {@code Optional} because a texture state shard can carry no texture at all, in which case
 * the wrapper leaves that render type untouched (the geometry simply stays opaque rather than crashing).
 */
@Mixin(RenderStateShard.TextureStateShard.class)
public interface AccessorTextureStateShard
{
    @Accessor("texture")
    Optional<ResourceLocation> su$texture();
}
