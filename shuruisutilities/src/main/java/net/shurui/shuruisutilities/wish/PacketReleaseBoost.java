package net.shurui.shuruisutilities.wish;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: the player's current stored ki release bonus ({@link ReleaseBoostStore}). The client caches it
 * in {@link ReleaseBoostClient} so the radial release node draws the correct ceiling. Sent on login
 * ({@link ReleaseBoostEvents}) and again each time a wish raises the total ({@link ReleaseBoostCommand}).
 */
public class PacketReleaseBoost implements ISUPacket
{
    public int bonus = 0;

    public PacketReleaseBoost() {}

    public PacketReleaseBoost(int bonus)
    {
        this.bonus = bonus;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeInt(bonus);
    }

    public static PacketReleaseBoost decode(FriendlyByteBuf buf)
    {
        return new PacketReleaseBoost(buf.readInt());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.wish.ReleaseBoostClient.set(bonus));
    }

    public static void handler(final PacketReleaseBoost message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
