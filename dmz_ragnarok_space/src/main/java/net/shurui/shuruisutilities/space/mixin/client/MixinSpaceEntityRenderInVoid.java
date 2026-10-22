package net.shurui.shuruisutilities.space.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;

import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * In the space dimension, draws entities whether or not the chunk section they sit in has been compiled yet.
 *
 * <p>Vanilla's entity pass in {@code LevelRenderer.renderLevel} skips any entity whose section is not compiled, so
 * mobs never float in front of terrain that has not appeared. A pod moves 2 to 12+ blocks a tick and keeps entering
 * chunks that have only just arrived (or, at speed, not arrived at all), so the pod and its pilot blinked out for a few
 * frames at a time. DMZ also restarts a 3D aura's grow-in whenever its owner goes undrawn for more than two ticks, so
 * the pod's aura kept collapsing to a flat ring. Space is open void with no terrain to wait for, so the gate is
 * dropped there only. Every other dimension keeps vanilla's check.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinSpaceEntityRenderInVoid
{
    @Shadow
    private ClientLevel level;

    @Redirect(method = "renderLevel", require = 0, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;isChunkCompiled(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean dmzr$drawEntitiesInVoid(LevelRenderer self, BlockPos pos)
    {
        return self.isChunkCompiled(pos) || SpaceKeys.isSpace(this.level);
    }
}
