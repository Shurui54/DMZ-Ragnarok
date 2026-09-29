package net.shurui.shuruisutilities.core.mixin.command;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.PardonIpCommand;

import net.shurui.shuruisutilities.shard.ShardPunishments;

/**
 * Records a vanilla {@code /pardon-ip} in the punishment ledger, since staff use the vanilla command too.
 *
 * <p>Injected at the RETURN of {@code unban}, which THROWS when the IP is not banned and returns only after the IP
 * ban entry has been removed, so reaching RETURN means the pardon succeeded and the row is written here. An IP pardon
 * has no player UUID, so the target UUID column takes the nil UUID and the IP goes in the target name. Takes the
 * target's parameters EXACTLY (source, ip) plus the callback. require = 0 so a vanilla mapping change degrades
 * rather than crashes.
 */
@Mixin(PardonIpCommand.class)
public class MixinPardonIpCommand
{
    @Inject(method = "unban", at = @At("RETURN"), require = 0)
    private static void su$recordPardonIp(CommandSourceStack source, String ip, CallbackInfoReturnable<Integer> cir)
    {
        ShardPunishments.record(source, ShardPunishments.UNBANIP, null, ip, null, 0L);
    }
}
