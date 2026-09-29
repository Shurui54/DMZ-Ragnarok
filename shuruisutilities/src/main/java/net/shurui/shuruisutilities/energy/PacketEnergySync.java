package net.shurui.shuruisutilities.energy;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * server -&gt; client: the receiving player's OWN active role bar, for the stat HUD track.
 *
 * <p>Carries no uuid, exactly like {@code PacketZeniSync}: the server only ever sends a player their own bar, so
 * there is nothing to key by and a client cannot learn another player's energy from this.
 *
 * <p>{@code kindOrdinal} is -1 for "this player currently has no role", which the HUD draws as no track at all
 * rather than as an empty one, so a player with no role sees the layout they had before this feature existed.
 */
public class PacketEnergySync implements ISUPacket
{
    /** {@link EnergyKind} ordinal, or -1 when the player has no active role. */
    public int kindOrdinal;
    /** Current value on the flat 0..{@link EnergyManager#MAX} scale. Meaningless when {@code kindOrdinal} is -1. */
    public float value;

    public PacketEnergySync() {}

    public PacketEnergySync(EnergyKind kind, float value)
    {
        this.kindOrdinal = kind == null ? -1 : kind.ordinal();
        this.value = value;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(kindOrdinal + 1); // shifted so the -1 "no role" case still encodes as a varint
        buf.writeFloat(value);
    }

    public static PacketEnergySync decode(FriendlyByteBuf buf)
    {
        PacketEnergySync p = new PacketEnergySync();
        p.kindOrdinal = buf.readVarInt() - 1;
        p.value = buf.readFloat();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.hud.EnergyClientCache.set(
                        EnergyKind.byOrdinal(kindOrdinal), value));
    }

    public static void handler(final PacketEnergySync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
