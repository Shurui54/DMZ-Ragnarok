package net.shurui.shuruisutilities.core.mixin.network;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.commands.util.CommandSpamGuard;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;

/**
 * Stops the suite's OWN trusted commands from feeding vanilla's chat-spam counter.
 *
 * <p>Vanilla runs a slash command and then calls {@code detectRateSpam()}, which does
 * {@code chatSpamTickCount += 20} and disconnects a non-op past 200 (see {@code handleChatCommand}). A GUI
 * button that fires two guild commands per click adds +40 and kicks a player after only a few clicks. This
 * HEAD injection, when the just-run command was one the suite flagged as trusted (see {@link CommandSpamGuard}
 * and {@code CommandGateHandler#commandEvent}), pre-subtracts 20 (clamped at 0) so vanilla's following
 * {@code += 20} nets to zero for our command.</p>
 *
 * <p>It deliberately does NOT zero the field: genuine chat-flood accumulation must survive, we only decline to
 * count our own commands. Chat routes through {@code broadcastChatMessage -> detectRateSpam} and never sets
 * the flag, so a chat flooder is still kicked on the exact old schedule.</p>
 *
 * <p>Defensive by design: {@code @Shadow} resolves against official mappings and the player is reached via the
 * {@code ServerPlayerConnection} interface (no shadowed field). If the field or method shape ever differs the
 * inject simply fails to bind and vanilla behaviour is unchanged; login and chat are untouched.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerPlayNetHandlerSpam
{
    @Shadow
    private int chatSpamTickCount;

    @Inject(method = "detectRateSpam", at = @At("HEAD"))
    private void su$dontCountOwnCommands(CallbackInfo ci)
    {
        ServerPlayer player = ((ServerPlayerConnection) (Object) this).getPlayer();
        if (player == null)
            return;
        if (CommandSpamGuard.consumeAllowed(player.getUUID()))
        {
            // Undo this suite's contribution before vanilla adds it back, clamped so we never go negative and
            // never erase a real chat-flood buildup below what our command would have added.
            chatSpamTickCount = Math.max(0, chatSpamTickCount - 20);
        }
    }
}
