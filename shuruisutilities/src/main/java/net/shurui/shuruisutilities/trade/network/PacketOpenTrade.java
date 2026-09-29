package net.shurui.shuruisutilities.trade.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: open, refresh or close a player-to-player trade, carrying the whole authoritative view the
 * receiver's screen renders (both sides' offered stacks, both names and both confirm flags). A dedicated TYPED packet
 * (not the generic string {@code PacketEditorData}) because each offered stack is a real {@link ItemStack}, which the
 * string transport cannot move. Modelled on
 * {@link net.shurui.shuruisutilities.auction.network.PacketOpenAuction}.
 *
 * <p>Every field is authoritative: the client never derives ownership, confirm state or contents, it only renders
 * what the server sends and echoes back string actions (add/remove/confirm/cancel) via {@code PacketEditorAction},
 * which the server re-validates. {@link #active} false means the trade has ended (completed or cancelled) and the
 * client closes the screen.
 */
public class PacketOpenTrade implements ISUPacket
{
    public boolean active = true;         // false = trade ended, close the screen
    public String selfName = "";          // the receiving player's own name
    public String otherName = "";         // the trade partner's name
    public boolean selfConfirmed;
    public boolean otherConfirmed;
    public long selfZeni;                 // zeni this side has staked (shown to both)
    public long otherZeni;                // zeni the partner has staked
    public List<ItemStack> selfOffer = new ArrayList<>();
    public List<ItemStack> otherOffer = new ArrayList<>();

    public PacketOpenTrade() {}

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(active);
        buf.writeUtf(selfName);
        buf.writeUtf(otherName);
        buf.writeBoolean(selfConfirmed);
        buf.writeBoolean(otherConfirmed);
        buf.writeVarLong(selfZeni);
        buf.writeVarLong(otherZeni);
        writeStacks(buf, selfOffer);
        writeStacks(buf, otherOffer);
    }

    public static PacketOpenTrade decode(FriendlyByteBuf buf)
    {
        PacketOpenTrade p = new PacketOpenTrade();
        p.active = buf.readBoolean();
        p.selfName = buf.readUtf();
        p.otherName = buf.readUtf();
        p.selfConfirmed = buf.readBoolean();
        p.otherConfirmed = buf.readBoolean();
        p.selfZeni = buf.readVarLong();
        p.otherZeni = buf.readVarLong();
        readStacks(buf, p.selfOffer);
        readStacks(buf, p.otherOffer);
        return p;
    }

    private static void writeStacks(FriendlyByteBuf buf, List<ItemStack> stacks)
    {
        buf.writeVarInt(stacks.size());
        for (ItemStack stack : stacks)
        {
            buf.writeItem(stack == null ? ItemStack.EMPTY : stack);
        }
    }

    private static void readStacks(FriendlyByteBuf buf, List<ItemStack> out)
    {
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            out.add(buf.readItem());
        }
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.gui.editor.TradeScreen.accept(this));
    }

    public static void handler(final PacketOpenTrade message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
