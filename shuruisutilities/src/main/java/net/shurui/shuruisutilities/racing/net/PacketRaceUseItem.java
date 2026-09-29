package net.shurui.shuruisutilities.racing.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.api.key.RaceHooks;
import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Client -&gt; server (fixed id 114): use the held powerup. {@code back} = drop behind (sneak, or S + right click),
 * kept as an alias for dropping a mine behind. {@code aimX} / {@code aimZ} are the crosshair look direction projected
 * onto the horizontal road plane (owner's aim-with-the-mouse controls): a Ki Blast fires along it (looking backward
 * fires backward), a Hellzone locks onto the racer nearest it, a Ki Mine / Fake Dragon Ball is tossed toward it.
 * These fields are APPENDED at the END of the codec so the packet stays backward compatible in wire order.
 *
 * <p>Server authoritative: it routes straight to the racing hook, which validates the sender is racing and holds an
 * item, and CLAMPS the aim to a sane unit direction (never a position) before using it. Keyless the hook default is a
 * no-op, so this does nothing.
 */
public class PacketRaceUseItem implements ISUPacket
{
    public boolean back;
    /** The crosshair aim direction on the horizontal plane (unit-ish). Server normalises / clamps; positions are never sent. */
    public float aimX;
    public float aimZ;

    public PacketRaceUseItem() {}

    public PacketRaceUseItem(boolean back)
    {
        this(back, 0.0F, 0.0F);
    }

    public PacketRaceUseItem(boolean back, float aimX, float aimZ)
    {
        this.back = back;
        this.aimX = aimX;
        this.aimZ = aimZ;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(back);
        // appended at the END of the codec
        buf.writeFloat(aimX);
        buf.writeFloat(aimZ);
    }

    public static PacketRaceUseItem decode(FriendlyByteBuf buf)
    {
        PacketRaceUseItem p = new PacketRaceUseItem(buf.readBoolean());
        p.aimX = buf.readFloat();
        p.aimZ = buf.readFloat();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
            return;
        RaceHooks.get().useItem(player, back, aimX, aimZ);
    }

    public static void handler(final PacketRaceUseItem message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
