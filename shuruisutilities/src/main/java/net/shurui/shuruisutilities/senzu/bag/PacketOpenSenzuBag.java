package net.shurui.shuruisutilities.senzu.bag;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.compat.curios.SenzuBagCurios;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;

/**
 * Client -> server, empty payload: the "open my bean bag" keybind. The server is the authority: it looks up the
 * player's OWN equipped bag and opens the {@link SenzuBagMenu} bound to that live stack. The client never says what
 * is in the bag or even which stack it is; it only asks to open "my bag". Mirrors {@code PacketOpenDragonBallBag}.
 */
public class PacketOpenSenzuBag implements ISUPacket
{
    // translation key shown when the keybind is pressed with no bag equipped (defined in en_us / es_es).
    private static final String NO_BAG_KEY = "message.dmz_ragnarok.core.senzu_bag_none";

    public PacketOpenSenzuBag()
    {
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        // no payload: the packet is the "open my bag" signal
    }

    public static PacketOpenSenzuBag decode(FriendlyByteBuf buf)
    {
        return new PacketOpenSenzuBag();
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
        {
            return;
        }

        ItemStack bag = SenzuBagCurios.findEquipped(player);
        if (bag.isEmpty())
        {
            player.sendSystemMessage(Component.translatable(NO_BAG_KEY));
            return;
        }

        NetworkHooks.openScreen(player, new SimpleMenuProvider(
                (id, inv, p) -> new SenzuBagMenu(id, inv, player, bag),
                Component.translatable("item.dmz_ragnarok.senzubag")));
    }

    public static void handler(final PacketOpenSenzuBag message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
