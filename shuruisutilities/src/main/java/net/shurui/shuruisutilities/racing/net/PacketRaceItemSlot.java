package net.shurui.shuruisutilities.racing.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.racing.physics.PowerupKind;

/**
 * Server -&gt; client (fixed id 111): the racer's item slot. When a box is picked up the server sends this with the
 * result already DECIDED and a spin length, so the client plays the roulette animation settling on it; the client
 * never picks the item. {@code charges} is the count for multi-use kinds (Kaioken x3), {@code result} {@link
 * PowerupKind#NONE} clears the slot after use.
 */
public class PacketRaceItemSlot implements ISUPacket
{
    public byte result = PowerupKind.NONE.id();
    public int rouletteTicks;
    public byte charges;

    public PacketRaceItemSlot() {}

    public PacketRaceItemSlot(PowerupKind result, int rouletteTicks, int charges)
    {
        this.result = (result == null ? PowerupKind.NONE : result).id();
        this.rouletteTicks = rouletteTicks;
        this.charges = (byte) charges;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeByte(result);
        buf.writeVarInt(rouletteTicks);
        buf.writeByte(charges);
    }

    public static PacketRaceItemSlot decode(FriendlyByteBuf buf)
    {
        PacketRaceItemSlot p = new PacketRaceItemSlot();
        p.result = buf.readByte();
        p.rouletteTicks = buf.readVarInt();
        p.charges = buf.readByte();
        return p;
    }

    public PowerupKind result()
    {
        return PowerupKind.byId(result);
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.racing.client.RaceClientState.onItemSlot(this));
    }

    public static void handler(final PacketRaceItemSlot message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
