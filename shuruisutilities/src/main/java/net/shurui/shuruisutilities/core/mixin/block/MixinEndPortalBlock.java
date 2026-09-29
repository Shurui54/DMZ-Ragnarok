package net.shurui.shuruisutilities.core.mixin.block;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.util.events.entity.EntityPortalEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraftforge.common.MinecraftForge;

@Mixin(EndPortalBlock.class)
public class MixinEndPortalBlock
{

    // entity dimension-travel perms via EntityPortalEvent
    @Inject(method = "entityInside", at = @At(value = "HEAD"), cancellable = true)
    public void runSUEntityPortalEVENT(BlockState state, Level worldIn, BlockPos pos, Entity entityIn, CallbackInfo ci)
    {
        if (worldIn instanceof ServerLevel && !entityIn.isPassenger() && !entityIn.isVehicle() && entityIn.canChangeDimensions()
                && Shapes.joinIsNotEmpty(
                        Shapes.create(entityIn.getBoundingBox().move((double) (-pos.getX()), (double) (-pos.getY()), (double) (-pos.getZ()))),
                        state.getShape(worldIn, pos), BooleanOp.AND))
        {
            ResourceKey<Level> registrykey = worldIn.dimension() == Level.END ? Level.OVERWORLD : Level.END;
            ServerLevel serverworld = ((ServerLevel) worldIn).getServer().getLevel(registrykey);
            if (serverworld == null)
            {
                ci.cancel();
                return;
            }
            // TODO: get target coordinates somehow
            if (MinecraftForge.EVENT_BUS.post(new EntityPortalEvent(entityIn, worldIn, pos, serverworld, new BlockPos(0, 0, 0), true)))
            {
                ci.cancel();
            }
        }
        else
        {
            ci.cancel();
        }
    }

}
