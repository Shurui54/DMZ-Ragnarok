package net.shurui.shuruisutilities.core.mixin.network;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import net.shurui.shuruisutilities.grave.GraveOpenPath;

/**
 * Opens a grave totem the player may open straight from the use-on-block packet ({@link GraveOpenPath}), so no
 * protection, ours or another mod's, can refuse it.
 *
 * <p>Injected right AFTER {@code PacketUtils.ensureRunningOnSameThread} (the first call re-queues the packet onto
 * the server thread by throwing, so a HEAD injection would run on the network thread). It acknowledges the client's
 * block-change sequence exactly as the rest of the method would, opens the totem and cancels the remainder. A method
 * level injection composes with other mods' redirects inside this method (Lootr redirects its mayInteract call);
 * see {@code MixinServerLevelGraveInteract} for why a call-site redirect here is not an option.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketGraveOpen
{
    @Shadow
    public ServerPlayer player;

    @Shadow
    public abstract void ackBlockChangesUpTo(int sequence);

    @Inject(method = "handleUseItemOn",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER),
            cancellable = true)
    private void su$openGraveTotemDirectly(ServerboundUseItemOnPacket packet, CallbackInfo ci)
    {
        if (GraveOpenPath.tryOpen(this.player, packet.getHitResult()))
        {
            this.ackBlockChangesUpTo(packet.getSequence());
            ci.cancel();
        }
    }
}
