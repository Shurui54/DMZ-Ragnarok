package net.shurui.shuruisutilities.space.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.shurui.shuruisutilities.space.SpaceChunkTicking;

/**
 * The cached "this chunk is all air" answer SpaceChunkTicking reads (0 unknown, 1 all air, 2 has something). Any block
 * change or block entity resets it to unknown, so the next chunk tick re-checks the chunk once. A new chunk object starts
 * unknown. Costs one byte write per block change, in every dimension (the flag is only ever READ for space). A write that
 * bypasses LevelChunk (straight into a section) is caught by SpaceChunkTicking's periodic recheck of all-air chunks.
 */
@Mixin(LevelChunk.class)
public abstract class MixinSpaceChunkEmptyFlag implements SpaceChunkTicking.Flag
{
    @Unique
    private byte dmzr$empty;

    @Override
    public byte dmzr$emptyState()
    {
        return this.dmzr$empty;
    }

    @Override
    public void dmzr$setEmptyState(byte state)
    {
        this.dmzr$empty = state;
    }

    // by NAME, so every setBlockState overload is covered (a Bukkit hybrid adds a 4-argument one the 3-argument one
    // delegates to). No argument capture, so the handler fits any of them.
    @Inject(method = "setBlockState", at = @At("HEAD"), require = 0)
    private void dmzr$blockChanged(CallbackInfoReturnable<BlockState> cir)
    {
        this.dmzr$empty = 0;
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), require = 0)
    private void dmzr$blockEntityChanged(CallbackInfo ci)
    {
        this.dmzr$empty = 0;
    }
}
