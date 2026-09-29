package net.shurui.shuruisutilities.ocarina;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.api.key.OcarinaHooks;
import net.shurui.shuruisutilities.commons.network.ISUPacket;

/**
 * client -> server: everything the player can ask their ocarina to do.
 *
 * <p>One packet with an action rather than three, because all three are the same conversation and every condition on
 * them is checked in the same place. The client is trusted with none of it: which songs are known, whether the
 * instrument is off cooldown and whether the racial exists at all are all decided by the Ragnarok Key (feature
 * {@code ocarina}, see {@link OcarinaHooks}).
 */
public class PacketOcarina implements ISUPacket
{
    /** Wire values. Do not renumber. */
    public static final int OPEN = 0;
    public static final int PLAY = 1;
    public static final int SCORE = 2;
    public static final int CLOSE = 3;

    public int action;
    public int song;
    public int score;

    public PacketOcarina() {}

    public PacketOcarina(int action, int song, int score)
    {
        this.action = action;
        this.song = song;
        this.score = score;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(action);
        buf.writeVarInt(song);
        buf.writeVarInt(score);
    }

    public static PacketOcarina decode(FriendlyByteBuf buf)
    {
        return new PacketOcarina(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
            return;
        // The ocarina is the Ragnarok Key's (feature ocarina); keyless the request is ignored.
        OcarinaHooks.get().onPacket(player, action, song, score);
    }

    public static void handler(final PacketOcarina message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
