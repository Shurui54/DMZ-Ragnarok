package net.shurui.shuruisutilities.core.mixin.command;

import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.KickCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import net.shurui.shuruisutilities.shard.NetKick;
import net.shurui.shuruisutilities.shard.ShardPunishments;

/**
 * Records a vanilla {@code /kick} in the punishment ledger, since staff use the vanilla command too.
 *
 * <p>Injected at the RETURN of {@code kickPlayers}, which is the point every target has already been disconnected.
 * The handler takes the target method's parameters EXACTLY (source, targets, reason) plus the callback, because
 * Mixin allows a handler only the full argument list or none of it; a leading subset fails at apply.
 *
 * <p>require = 0: this targets a vanilla Minecraft class, so a mapping change upstream must degrade (no ledger row)
 * rather than crash, per the suite's Minecraft-mixin rule.
 */
@Mixin(KickCommand.class)
public class MixinKickCommand
{
    @Inject(method = "kickPlayers", at = @At("RETURN"), require = 0)
    private static void su$recordKick(CommandSourceStack source, Collection<ServerPlayer> targets, Component reason,
            CallbackInfoReturnable<Integer> cir)
    {
        // kickPlayers kicks every target unconditionally and returns the count; 0 means nobody matched.
        Integer kicked = cir.getReturnValue();
        if (kicked == null || kicked <= 0 || targets == null)
            return;
        String why = reason == null ? null : reason.getString();
        for (ServerPlayer p : targets)
            ShardPunishments.record(source, ShardPunishments.KICK, p.getUUID(), p.getGameProfile().getName(), why, 0L);
    }

    /**
     * Make the kick a WHOLE-NETWORK removal instead of a redirect to the other shard. Redirects the {@code disconnect}
     * call inside {@code kickPlayers} so the proxy is told first (see {@link NetKick}). Behaviour is otherwise
     * identical: the same reason still reaches the same connection. require = 0 so a vanilla mapping change degrades
     * to today's failover rather than crashing.
     */
    @Redirect(method = "kickPlayers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"),
            require = 0)
    private static void su$netKick(ServerGamePacketListenerImpl connection, Component reason)
    {
        NetKick.disconnect(connection, reason);
    }
}
