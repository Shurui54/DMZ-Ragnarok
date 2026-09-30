package net.shurui.shuruisutilities.core.mixin.network;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.server.players.PlayerList;

import net.shurui.shuruisutilities.api.key.VanishHooks;

/**
 * Suppresses the yellow "multiplayer.player.left" broadcast when the leaving player is vanished. In 1.20.1 this
 * broadcast lives in {@code ServerGamePacketListenerImpl#onDisconnect} (NOT {@code PlayerList#remove}), so the
 * redirect targets the {@code broadcastSystemMessage} call there. Reads the PERSISTED vanish state via
 * {@link VanishHooks#isVanished}. Only the leave system message is affected.
 *
 * <p>The listener's {@code player} field is reached via the {@link ServerPlayerConnection} interface, matching
 * the {@link MixinServerPlayNetHandler} idiom (shadow fields are not refmap-remapped in this dev setup).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerPlayNetHandlerLeaveMsg
{

    @Redirect(method = "onDisconnect",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void su$suppressVanishedLeaveMessage(PlayerList list, Component message, boolean overlay)
    {
        ServerPlayer player = ((ServerPlayerConnection) (Object) this).getPlayer();
        if (player != null && VanishHooks.isVanished(player.getUUID()))
        {
            // Vanished: do not tell the whole server. Other vanished staff still saw this player, so they get the
            // real leave line; ordinary players (who thought the player already left on vanish) get nothing.
            VanishHooks.announcePresenceToSeers(player, message);
            return;
        }
        // DISGUISE: on a single server (network off), a disguised player leaves under the disguise name. On a network
        // this vanilla leave is suppressed and the key announces instead, so this only bites the non-networked case;
        // keyless the store is empty, so it never fires. Vanish takes precedence above.
        if (player != null)
        {
            String shown = net.shurui.shuruisutilities.disguise.DisguiseState.visibleName(player.getUUID());
            if (shown != null)
                message = net.minecraft.network.chat.Component.translatable("multiplayer.player.left", shown)
                        .withStyle(net.minecraft.ChatFormatting.YELLOW);
        }
        list.broadcastSystemMessage(message, overlay);
    }
}
