package net.shurui.shuruisutilities.disguise;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server to client (SU channel id 148): the disguise identity sync.
 *
 * <p>Three shapes, one packet:
 * <ul>
 *   <li>{@link #MODE_SNAPSHOT}: the full set of active disguises plus this viewer's see-through flag. Sent once on
 *       login so a joining client learns every disguise already in effect and whether it may draw the real-identity
 *       marker.</li>
 *   <li>{@link #MODE_SINGLE}: one disguise added or changed, broadcast to everyone.</li>
 *   <li>{@link #MODE_CLEAR}: one disguise dropped, broadcast to everyone.</li>
 * </ul>
 * The see-through flag rides only on the snapshot because it is a property of the VIEWER, set once per login; a
 * later single update never changes it.
 */
public final class PacketDisguiseSync implements ISUPacket
{
    public static final byte MODE_SNAPSHOT = 0;
    public static final byte MODE_SINGLE = 1;
    public static final byte MODE_CLEAR = 2;

    public byte mode;
    public boolean canSeeReal;
    public List<DisguiseView> views = new ArrayList<>();
    public UUID clearId;

    public PacketDisguiseSync() {}

    public static PacketDisguiseSync snapshot(List<DisguiseView> all, boolean canSeeReal)
    {
        PacketDisguiseSync p = new PacketDisguiseSync();
        p.mode = MODE_SNAPSHOT;
        p.canSeeReal = canSeeReal;
        p.views = all;
        return p;
    }

    public static PacketDisguiseSync single(DisguiseView view)
    {
        PacketDisguiseSync p = new PacketDisguiseSync();
        p.mode = MODE_SINGLE;
        p.views.add(view);
        return p;
    }

    public static PacketDisguiseSync clear(UUID realId)
    {
        PacketDisguiseSync p = new PacketDisguiseSync();
        p.mode = MODE_CLEAR;
        p.clearId = realId;
        return p;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeByte(mode);
        if (mode == MODE_CLEAR)
        {
            buf.writeUUID(clearId);
            return;
        }
        if (mode == MODE_SNAPSHOT)
            buf.writeBoolean(canSeeReal);
        buf.writeVarInt(views.size());
        for (DisguiseView v : views)
            v.encode(buf);
    }

    public static PacketDisguiseSync decode(FriendlyByteBuf buf)
    {
        PacketDisguiseSync p = new PacketDisguiseSync();
        p.mode = buf.readByte();
        if (p.mode == MODE_CLEAR)
        {
            p.clearId = buf.readUUID();
            return p;
        }
        if (p.mode == MODE_SNAPSHOT)
            p.canSeeReal = buf.readBoolean();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.views.add(DisguiseView.decode(buf));
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.disguise.client.DisguiseClientCache.accept(this));
    }

    public static void handler(final PacketDisguiseSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
