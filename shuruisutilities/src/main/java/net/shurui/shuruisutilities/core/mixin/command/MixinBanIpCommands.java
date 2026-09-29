package net.shurui.shuruisutilities.core.mixin.command;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.BanIpCommands;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import net.shurui.shuruisutilities.shard.NetKick;
import net.shurui.shuruisutilities.shard.ShardPunishments;

/**
 * Records a vanilla {@code /ban-ip} in the punishment ledger, since staff use the vanilla command too.
 *
 * <p>Injected at the RETURN of {@code banIp}, which is where the IP ban list entry has already been added. That
 * method THROWS on an invalid or already-banned IP and returns only on success (its return value is merely how many
 * online players were kicked, which can be zero), so reaching RETURN is itself the success signal and the row is
 * written unconditionally here. {@code banIpOrName} funnels the by-name form into this same {@code banIp}, so both
 * forms are captured once.
 *
 * <p>An IP ban has no player UUID, so the target UUID column takes the nil UUID and the IP goes in the target name.
 * Takes the target's parameters EXACTLY (source, ip, reason) plus the callback. require = 0 so a vanilla mapping
 * change degrades rather than crashes.
 */
@Mixin(BanIpCommands.class)
public class MixinBanIpCommands
{
    @Inject(method = "banIp", at = @At("RETURN"), require = 0)
    private static void su$recordBanIp(CommandSourceStack source, String ip, Component reason,
            CallbackInfoReturnable<Integer> cir)
    {
        String why = reason == null ? null : reason.getString();
        ShardPunishments.record(source, ShardPunishments.IPBAN, null, ip, why, 0L);
    }

    /**
     * Make an IP ban a WHOLE-NETWORK removal instead of a redirect to the other shard. {@code banIp} loops over every
     * online player on the address and disconnects each; this redirects that call so the proxy is told first (see
     * {@link NetKick}). require = 0 so a vanilla mapping change degrades to today's failover rather than crashing.
     */
    @Redirect(method = "banIp", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"),
            require = 0)
    private static void su$netKick(ServerGamePacketListenerImpl connection, Component reason)
    {
        NetKick.disconnect(connection, reason);
    }
}
