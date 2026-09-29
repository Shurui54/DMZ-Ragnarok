package net.shurui.shuruisutilities.core.mixin.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;

import net.shurui.shuruisutilities.api.key.VanishHooks;

/**
 * Keeps vanished players out of every client's tab list, including clients that refresh or join late. Vanilla
 * sends {@link ClientboundPlayerInfoUpdatePacket}s (ADD_PLAYER on join, UPDATE_LATENCY on ping, etc.) that would
 * re-add a vanished player to the receiver's tab. We intercept the outgoing send and, if the packet carries a
 * vanished player's entry and the receiver is NOT that vanished player, rebuild the packet with those entries
 * dropped (cancelling the send entirely if nothing is left).
 *
 * <p>Invariant: a vanished player must always see themselves in tab, so we never strip an entry from the
 * connection belonging to that same player.
 *
 * <p>The listener's {@code player} field is reached via the {@link ServerPlayerConnection} interface, matching
 * the {@link MixinServerPlayNetHandler} idiom (shadow fields are not refmap-remapped in this dev setup).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerPlayNetHandlerVanish
{

    private static final Logger SU_VANISH_LOGGER = LoggerFactory.getLogger("shuruisutilities/vanish-tablist");

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void su$filterVanishedFromTabList(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci)
    {
        if (!(packet instanceof ClientboundPlayerInfoUpdatePacket infoPacket))
            return;

        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = infoPacket.entries();
        if (entries.isEmpty())
            return;

        // Fail open: anything that throws while filtering must NOT reset the connection. On failure we just
        // return so vanilla sends the original packet unmodified. A vanished player briefly showing in tab is
        // strictly better than dropping a login. Mirrors the defensive try/catch in MixinPlayerListVanish.
        try
        {
            ServerPlayer receiver = ((ServerPlayerConnection) (Object) this).getPlayer();
            UUID receiverId = receiver == null ? null : receiver.getUUID();

            // Quick scan: does this packet reference any vanished player this receiver may NOT see? A receiver who
            // is themselves vanished sees every vanished player, so their packets are never filtered.
            boolean needsFilter = false;
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : entries)
            {
                UUID id = entry.profileId();
                if (!VanishHooks.canSee(receiverId, id))
                {
                    needsFilter = true;
                    break;
                }
            }
            if (!needsFilter)
                return;

            List<ClientboundPlayerInfoUpdatePacket.Entry> kept = new ArrayList<>(entries.size());
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : entries)
            {
                UUID id = entry.profileId();
                // Keep the entry if this receiver may see that player: visible, self, or a vanished-sees-vanished
                // pairing. See VanishHooks#canSee.
                if (VanishHooks.canSee(receiverId, id))
                    kept.add(entry);
            }

            if (kept.isEmpty())
            {
                // Nothing left to tell this client about; drop the packet.
                ci.cancel();
                return;
            }

            // Rebuild a packet carrying the same actions but only the kept entries. We construct an empty-entry
            // packet via the public constructor, then overwrite its entry list with our filtered snapshot so the
            // original per-entry data (latency, gamemode, display name, chat session) is preserved verbatim.
            ClientboundPlayerInfoUpdatePacket rebuilt =
                    new ClientboundPlayerInfoUpdatePacket(infoPacket.actions(), java.util.List.<ServerPlayer>of());
            ((AccessorPlayerInfoUpdatePacket) (Object) rebuilt).su$setEntries(kept);

            // Cancel the original send, then push the filtered copy. Pass a null listener on the re-send so the
            // caller's completion callback is not fired twice (it will fire once for our rebuilt packet path via
            // vanilla; reusing the original listener here double-drives it).
            ci.cancel();
            ((ServerGamePacketListenerImpl) (Object) this).send(rebuilt, null);
        }
        catch (Throwable t)
        {
            // Never break the netty pipeline. Log quietly and let vanilla send the original packet.
            SU_VANISH_LOGGER.debug("Vanish tab-list filter failed; sending original packet unmodified", t);
        }
    }
}
