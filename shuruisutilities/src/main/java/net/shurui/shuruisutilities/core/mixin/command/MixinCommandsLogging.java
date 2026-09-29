package net.shurui.shuruisutilities.core.mixin.command;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.brigadier.ParseResults;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import net.shurui.shuruisutilities.shard.ShardCommandLog;

/**
 * Log every command run on the server for the moderation audit trail.
 *
 * <p><b>Why here and not on {@code performPrefixedCommand}.</b> In 1.20.1 only the console, RCON and the retired
 * {@code /reload}-style callers reach {@code Commands.performPrefixedCommand(CommandSourceStack, String)}: a PLAYER
 * typing a chat command reaches {@code ServerGamePacketListenerImpl.performChatCommand}, which calls
 * {@code Commands.performCommand(ParseResults, String)} directly, NEVER the prefixed variant. Hooking the prefixed
 * method (the original bug) therefore logged the console but nothing a player ran, which is why {@code su_command_log}
 * sat empty on the live shards. {@code performCommand(ParseResults, String)} is the real choke point: the prefixed
 * method delegates straight to it, so both the player path and the console/RCON path pass through here exactly once,
 * and there is nothing left to double log.
 *
 * <p><b>Functions and command blocks.</b> A datapack function runs its lines through {@code getDispatcher().execute}
 * directly, NOT through {@code performCommand}, so a function's inner commands never reach this hook; only the
 * {@code /function} invocation itself does, which is the single line an operator actually typed. A command block's
 * source has no player entity and a name this class already declines to log. Both are handled in {@link ShardCommandLog}.
 *
 * <p>The RETURN captures the final result of the whole line (its sub-commands run inside the dispatcher, not back
 * through here), so {@link ShardCommandLog#onCommand} records a real outcome rather than only "attempted". Whether a
 * given line is kept, and how its secrets are redacted, is decided in {@link ShardCommandLog}; this only forwards, and
 * {@code onCommand} is itself fully defensive so a fault in the audit path can never take a command down with it.
 *
 * <p>{@code require = 1}: this is a live vanilla target and a silent miss would put the audit trail back to empty, so a
 * failure to bind must be loud. Vanilla mixins are remapped, so the refmap carries {@code performCommand}.
 */
@Mixin(Commands.class)
public class MixinCommandsLogging
{
    @Inject(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)I",
            at = @At("RETURN"), require = 1)
    private void su$logCommand(ParseResults<CommandSourceStack> parseResults, String command,
            CallbackInfoReturnable<Integer> cir)
    {
        ShardCommandLog.onCommand(parseResults.getContext().getSource(), command, cir.getReturnValueI());
    }
}
