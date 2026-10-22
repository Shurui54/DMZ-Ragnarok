package net.shurui.shuruisutilities.space.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.dragonminez.common.init.entities.SpacePodEntity;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * The pilot's half of {@code MixinSpacePodAlwaysTicking}. {@code LocalPlayer.tick} skips its whole body while the chunk
 * under the player has not arrived, and that body is what sends the pilot's rotation, input and the
 * {@code ServerboundMoveVehiclePacket} for the pod. A pod that keeps ticking through an unreceived chunk would then move
 * on the pilot's screen while the server heard nothing, and the first report after the gap would be a long jump the
 * server rejects (a snap back). So while the local player pilots a space pod in the space dimension, the chunk check
 * reads as loaded and the reports keep flowing every tick. Anywhere else, or on foot, vanilla's check is untouched.
 */
@Mixin(LocalPlayer.class)
public abstract class MixinPodPilotTickInSpace
{
    @Redirect(method = "tick", require = 0,
            at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/world/level/Level;hasChunkAt(II)Z"))
    private boolean dmzr$pilotTicksInSpace(Level level, int x, int z)
    {
        if (level.hasChunkAt(x, z))
        {
            return true;
        }
        try
        {
            Entity root = ((LocalPlayer) (Object) this).getRootVehicle();
            return root instanceof SpacePodEntity && root.isControlledByLocalInstance() && SpaceKeys.isSpace(level);
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }
}
