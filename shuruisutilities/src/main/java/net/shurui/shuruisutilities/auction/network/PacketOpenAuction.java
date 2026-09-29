package net.shurui.shuruisutilities.auction.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: open/refresh the auction house, carrying the whole authoritative view the screen renders.
 * A dedicated TYPED packet (not the generic string {@code PacketEditorData}) because a listing and a claim each
 * carry a real {@link ItemStack}, which the string transport cannot move. Modelled on
 * {@link net.shurui.shuruisutilities.corrupted.network.PacketOpenShadowDragonEditor}.
 *
 * <p>The client never derives prices, ownership or time-left; it only renders what the server sends and echoes back
 * string actions (bid/buy/cancel/collect) via {@code PacketEditorAction}, which the server re-validates.
 */
public class PacketOpenAuction implements ISUPacket
{
    // one open listing as the client sees it.
    public static final class ListingView
    {
        public UUID id;
        public String sellerName;
        public ItemStack stack;
        public long startingBid;
        public long buyNowPrice;   // 0 = none
        public long currentBid;    // 0 = no bid yet
        public String bidderName;  // "" = none
        public long remainingMillis;
        public boolean own;        // the viewer is the seller
        public boolean topBidder;  // the viewer is the current top bidder
    }

    // one pending payout as the client sees it.
    public static final class ClaimView
    {
        public boolean isItem;
        public ItemStack stack; // empty for a zeni payout
        public long zeni;
        public String reasonKey;
    }

    public String currency = "Zeni";
    public boolean canList;
    public int maxListings;
    public int activeCount;
    public long listingFee;
    public long minIncrement;
    public double saleTaxPercent;
    public List<ListingView> listings = new ArrayList<>();
    public List<ClaimView> claims = new ArrayList<>();

    public PacketOpenAuction() {}

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(currency);
        buf.writeBoolean(canList);
        buf.writeVarInt(maxListings);
        buf.writeVarInt(activeCount);
        buf.writeLong(listingFee);
        buf.writeLong(minIncrement);
        buf.writeDouble(saleTaxPercent);
        buf.writeVarInt(listings.size());
        for (ListingView v : listings)
        {
            buf.writeUUID(v.id);
            buf.writeUtf(v.sellerName == null ? "" : v.sellerName);
            buf.writeItem(v.stack);
            buf.writeLong(v.startingBid);
            buf.writeLong(v.buyNowPrice);
            buf.writeLong(v.currentBid);
            buf.writeUtf(v.bidderName == null ? "" : v.bidderName);
            buf.writeLong(v.remainingMillis);
            buf.writeBoolean(v.own);
            buf.writeBoolean(v.topBidder);
        }
        buf.writeVarInt(claims.size());
        for (ClaimView c : claims)
        {
            buf.writeBoolean(c.isItem);
            buf.writeItem(c.stack == null ? ItemStack.EMPTY : c.stack);
            buf.writeLong(c.zeni);
            buf.writeUtf(c.reasonKey == null ? "" : c.reasonKey);
        }
    }

    public static PacketOpenAuction decode(FriendlyByteBuf buf)
    {
        PacketOpenAuction p = new PacketOpenAuction();
        p.currency = buf.readUtf();
        p.canList = buf.readBoolean();
        p.maxListings = buf.readVarInt();
        p.activeCount = buf.readVarInt();
        p.listingFee = buf.readLong();
        p.minIncrement = buf.readLong();
        p.saleTaxPercent = buf.readDouble();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            ListingView v = new ListingView();
            v.id = buf.readUUID();
            v.sellerName = buf.readUtf();
            v.stack = buf.readItem();
            v.startingBid = buf.readLong();
            v.buyNowPrice = buf.readLong();
            v.currentBid = buf.readLong();
            v.bidderName = buf.readUtf();
            v.remainingMillis = buf.readLong();
            v.own = buf.readBoolean();
            v.topBidder = buf.readBoolean();
            p.listings.add(v);
        }
        int m = buf.readVarInt();
        for (int i = 0; i < m; i++)
        {
            ClaimView c = new ClaimView();
            c.isItem = buf.readBoolean();
            c.stack = buf.readItem();
            c.zeni = buf.readLong();
            c.reasonKey = buf.readUtf();
            p.claims.add(c);
        }
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.gui.editor.AuctionScreen.open(this));
    }

    public static void handler(final PacketOpenAuction message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
