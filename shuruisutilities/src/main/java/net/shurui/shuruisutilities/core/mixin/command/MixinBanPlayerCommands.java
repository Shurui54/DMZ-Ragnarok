package net.shurui.shuruisutilities.core.mixin.command;

import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.GameProfile;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.BanPlayerCommands;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import net.shurui.shuruisutilities.shard.NetKick;
import net.shurui.shuruisutilities.shard.ShardPunishments;

/**
 * Records a vanilla {@code /ban} in the punishment ledger, since staff use the vanilla command too.
 *
 * <p>Injected at the RETURN of {@code banPlayers}. The method loops over the profiles and bans each one not already
 * banned, returning how many were newly banned, so the ledger row is only written when that count is positive. For
 * the ordinary single-target {@code /ban <name>} this is exactly one row; a multi-target selector that mixes new and
 * already-banned profiles may over-record the already-banned ones, which is acceptable for an audit trail.
 *
 * <p>The handler takes the target's parameters EXACTLY (source, profiles, reason) plus the callback. require = 0 so a
 * vanilla mapping change degrades rather than crashes.
 */
@Mixin(BanPlayerCommands.class)
public class MixinBanPlayerCommands
{
    @Inject(method = "banPlayers", at = @At("RETURN"), require = 0)
    private static void su$recordBan(CommandSourceStack source, Collection<GameProfile> profiles, Component reason,
            CallbackInfoReturnable<Integer> cir)
    {
        Integer banned = cir.getReturnValue();
        if (banned == null || banned <= 0 || profiles == null)
            return;
        String why = reason == null ? null : reason.getString();
        for (GameProfile profile : profiles)
            ShardPunishments.record(source, ShardPunishments.BAN, profile.getId(), profile.getName(), why, 0L);
    }

    /**
     * Make the ban a WHOLE-NETWORK removal instead of a redirect to the other shard. Redirects the {@code disconnect}
     * call {@code banPlayers} makes on a target online here so the proxy is told first (see {@link NetKick}); the
     * synced ban still keeps them out of every shard. require = 0 so a vanilla mapping change degrades to today's
     * failover rather than crashing.
     */
    @Redirect(method = "banPlayers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"),
            require = 0)
    private static void su$netKick(ServerGamePacketListenerImpl connection, Component reason)
    {
        NetKick.disconnect(connection, reason);
    }
}
