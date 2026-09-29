package net.shurui.shuruisutilities.core.mixin.command;

import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.GameProfile;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.PardonCommand;

import net.shurui.shuruisutilities.shard.ShardPunishments;

/**
 * Records a vanilla {@code /pardon} in the punishment ledger, since staff use the vanilla command too.
 *
 * <p>Injected at the RETURN of {@code pardonPlayers}, which pardons each currently banned profile and returns the
 * count actually lifted, so a row is written only when that count is positive. Takes the target's parameters EXACTLY
 * (source, profiles) plus the callback. require = 0 so a vanilla mapping change degrades rather than crashes.
 */
@Mixin(PardonCommand.class)
public class MixinPardonCommand
{
    @Inject(method = "pardonPlayers", at = @At("RETURN"), require = 0)
    private static void su$recordPardon(CommandSourceStack source, Collection<GameProfile> profiles,
            CallbackInfoReturnable<Integer> cir)
    {
        Integer pardoned = cir.getReturnValue();
        if (pardoned == null || pardoned <= 0 || profiles == null)
            return;
        for (GameProfile profile : profiles)
            ShardPunishments.record(source, ShardPunishments.UNBAN, profile.getId(), profile.getName(), null, 0L);
    }
}
