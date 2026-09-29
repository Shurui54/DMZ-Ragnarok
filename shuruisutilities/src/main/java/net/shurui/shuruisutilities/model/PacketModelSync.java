package net.shurui.shuruisutilities.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server to client (SU channel id 149): the look-only model override sync.
 *
 * <p>{@link #MODE_SNAPSHOT} carries the whole map on login; {@link #MODE_SINGLE} one add / change; {@link #MODE_CLEAR}
 * one removal. The client loads it into {@code ModelClientCache}, which the DragonMineZ player-model render redirect
 * consults (routing every geo through {@code RgNpcFallback} so a not-yet-streamed model never crashes the render
 * thread).
 */
public final class PacketModelSync implements ISUPacket
{
    public static final byte MODE_SNAPSHOT = 0;
    public static final byte MODE_SINGLE = 1;
    public static final byte MODE_CLEAR = 2;

    public byte mode;
    public Map<UUID, String> overrides = new HashMap<>();
    public UUID single;
    public String singleModel = "";

    public PacketModelSync() {}

    public static PacketModelSync snapshot(Map<UUID, String> all)
    {
        PacketModelSync p = new PacketModelSync();
        p.mode = MODE_SNAPSHOT;
        p.overrides = all;
        return p;
    }

    public static PacketModelSync single(UUID id, String modelId)
    {
        PacketModelSync p = new PacketModelSync();
        p.mode = MODE_SINGLE;
        p.single = id;
        p.singleModel = modelId;
        return p;
    }

    public static PacketModelSync clear(UUID id)
    {
        PacketModelSync p = new PacketModelSync();
        p.mode = MODE_CLEAR;
        p.single = id;
        return p;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeByte(mode);
        if (mode == MODE_SNAPSHOT)
        {
            buf.writeVarInt(overrides.size());
            for (Map.Entry<UUID, String> e : overrides.entrySet())
            {
                buf.writeUUID(e.getKey());
                buf.writeUtf(e.getValue());
            }
            return;
        }
        buf.writeUUID(single);
        if (mode == MODE_SINGLE)
            buf.writeUtf(singleModel);
    }

    public static PacketModelSync decode(FriendlyByteBuf buf)
    {
        PacketModelSync p = new PacketModelSync();
        p.mode = buf.readByte();
        if (p.mode == MODE_SNAPSHOT)
        {
            int n = buf.readVarInt();
            for (int i = 0; i < n; i++)
            {
                UUID id = buf.readUUID();
                p.overrides.put(id, buf.readUtf());
            }
            return p;
        }
        p.single = buf.readUUID();
        if (p.mode == MODE_SINGLE)
            p.singleModel = buf.readUtf();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.model.client.ModelClientCache.accept(this));
    }

    public static void handler(final PacketModelSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
