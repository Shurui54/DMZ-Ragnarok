package net.shurui.shuruisutilities.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.players.PlayerList;

/**
 * Silences vanilla's own join and leave lines while the server is part of a network.
 *
 * <h2>Why</h2>
 * On a network these are said twice and one of them is wrong. The shard system announces an arrival once, to
 * everybody, and deliberately says nothing when a player merely hops between servers. Vanilla knows nothing
 * about any of that: it announces on the local server every time somebody connects or disconnects, so walking
 * through a portal that moves you to the SMP would read as leaving and joining the game, and a genuine login
 * would be announced twice to the people standing next to you.
 *
 * <p>Caught at {@code broadcastSystemMessage} rather than at either call site, because they are in two
 * different classes: the join line is built in {@code PlayerList.placeNewPlayer} and the leave line in
 * {@code ServerGamePacketListenerImpl.onDisconnect}. One hook on the thing they both go through covers both,
 * and covers any other caller of the same message.
 *
 * <p>Matched on the TRANSLATION KEY, so it is exactly the three vanilla messages and nothing else. Matching on
 * rendered text would break in every language but English and would be at risk of catching a player's chat.
 *
 * <p>Does nothing at all unless the shard system is live, so a single server keeps vanilla behaviour.
 */
@Mixin(PlayerList.class)
public abstract class MixinPlayerListJoinMessage
{
    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void su$dropVanillaJoinLeave(Component message, boolean overlay, CallbackInfo ci)
    {
        if (overlay || message == null)
            return;
        if (!net.shurui.shuruisutilities.shard.ShardSync.active())
            return;
        if (message.getContents() instanceof TranslatableContents contents)
        {
            String key = contents.getKey();
            if ("multiplayer.player.joined".equals(key)
                    || "multiplayer.player.joined.renamed".equals(key)
                    || "multiplayer.player.left".equals(key))
            {
                ci.cancel();
            }
        }
    }
}
