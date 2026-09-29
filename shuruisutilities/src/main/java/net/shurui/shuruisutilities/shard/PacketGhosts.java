package net.shurui.shuruisutilities.shard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

/**
 * server -&gt; client: everybody on a sister server close enough to see, and where they are.
 *
 * <p>The whole visible set every time, not deltas: a handful of entries, the client treats anything missing from
 * the list as gone without a despawn message, and a dropped packet corrects itself a tenth of a second later.
 *
 * <p>Positions are floats, not doubles: a ghost is a translucent stand-in nobody can interact with, so centimetre
 * accuracy is wasted bandwidth.
 */
public class PacketGhosts implements ISUPacket
{
    /**
     * A ghost's identity, where it is, and just enough DragonMineZ character state to draw it as the race the
     * player actually is rather than a default human. {@code race}, {@code gender} and {@code form} are the
     * three strings {@code DMZPlayerRenderer} keys its model choice on, plus {@code bodyType} for the body
     * variant. Any of the strings may be empty, meaning "unknown", in which case the client leaves the ghost's
     * default (human) character alone. Only the race side is carried, not the full character: it is all the
     * renderer reads to pick a model, and carrying the rest (colours, hair, unlocked forms) would be a lot of
     * bandwidth for no visible gain on a translucent stand-in.
     */
    public record Ghost(UUID id, String name, float x, float y, float z, float yaw, float pitch,
                        String race, String gender, int bodyType, String form, String formGroup,
                        String appearance, String armor) {}

    public List<Ghost> ghosts = new ArrayList<>();

    public PacketGhosts() {}

    public PacketGhosts(List<Ghost> ghosts)
    {
        this.ghosts = ghosts;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(ghosts.size());
        for (Ghost g : ghosts)
        {
            buf.writeUUID(g.id());
            buf.writeUtf(g.name(), 32);
            buf.writeFloat(g.x());
            buf.writeFloat(g.y());
            buf.writeFloat(g.z());
            buf.writeFloat(g.yaw());
            buf.writeFloat(g.pitch());
            buf.writeUtf(g.race() == null ? "" : g.race(), 32);
            buf.writeUtf(g.gender() == null ? "" : g.gender(), 16);
            buf.writeVarInt(g.bodyType());
            buf.writeUtf(g.form() == null ? "" : g.form(), 64);
            buf.writeUtf(g.formGroup() == null ? "" : g.formGroup(), 64);
            // Hair, colours and face types, then the four worn pieces as item ids. See GhostAppearance for
            // the encoding and for what is deliberately left out of it.
            buf.writeUtf(g.appearance() == null ? "" : g.appearance(), 256);
            buf.writeUtf(g.armor() == null ? "" : g.armor(), 256);
        }
    }

    public static PacketGhosts decode(FriendlyByteBuf buf)
    {
        PacketGhosts packet = new PacketGhosts();
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++)
        {
            packet.ghosts.add(new Ghost(buf.readUUID(), buf.readUtf(32),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readUtf(32), buf.readUtf(16), buf.readVarInt(), buf.readUtf(64), buf.readUtf(64),
                    buf.readUtf(256), buf.readUtf(256)));
        }
        return packet;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::applyClient);
    }

    private void applyClient()
    {
        net.shurui.shuruisutilities.client.shard.GhostManager.accept(ghosts);
    }

    public static void handler(final PacketGhosts message, java.util.function.Supplier<NetworkEvent.Context> ctx)
    {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> message.handle(context));
        context.setPacketHandled(true);
    }
}
