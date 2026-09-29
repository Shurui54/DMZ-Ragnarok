package net.shurui.shuruisutilities.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/**
 * The supporter crown in vanilla-delivered player chat, for a server where the private Chat module is not running. All the deciding is in {@code PatreonChatCrown}; this only swaps the chat type binding of
 * a player's chat line for the decorated one.
 *
 * <p>Hooked on {@code PlayerList} rather than {@code ServerGamePacketListenerImpl} because a Bukkit hybrid rewrites
 * the latter and {@code SUMixinConfig} skips our mixins into it there. The {@code ServerPlayer} overload is the one
 * vanilla's chat handler calls; {@code /say} and {@code /me} use the command-source overload and are not touched.
 * With the Chat module up this line is never reached (it cancels the chat event and delivers itself), and the
 * decorator refuses anyway, so keyed chat is unchanged.
 */
@Mixin(PlayerList.class)
public abstract class MixinPlayerListChatCrown
{
    @ModifyVariable(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"), argsOnly = true)
    private ChatType.Bound su$patreonChatCrown(ChatType.Bound bound, PlayerChatMessage message, ServerPlayer sender)
    {
        return net.shurui.shuruisutilities.patreon.PatreonChatCrown.decorate(sender, bound);
    }
}
