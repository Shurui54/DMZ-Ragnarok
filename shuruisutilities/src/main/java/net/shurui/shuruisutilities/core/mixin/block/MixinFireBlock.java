package net.shurui.shuruisutilities.core.mixin.block;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.util.events.world.FireEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;

@Mixin(FireBlock.class)
public class MixinFireBlock
{
    // post FireEvent.Destroy when fire removes a block while ticking (FireBlock#tick calls removeBlock)
    @Inject(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"),
            cancellable = true)
    public void handleBlockDestroyOnTick(BlockState state, ServerLevel world, BlockPos pos, RandomSource random, CallbackInfo ci)
    {
        if (MinecraftForge.EVENT_BUS.post(new FireEvent.Destroy(world, pos)))
        {
            ci.cancel();
        }
    }
}
