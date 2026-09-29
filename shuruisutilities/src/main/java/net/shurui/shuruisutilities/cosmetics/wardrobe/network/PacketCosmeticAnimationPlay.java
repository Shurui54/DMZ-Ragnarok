package net.shurui.shuruisutilities.cosmetics.wardrobe.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server to client: play one triggered cosmetic ANIMATION at a world position.
 *
 * <p>Carries an id, not a definition: the client already holds the catalogue from {@link PacketCosmeticCatalogSync}
 * and resolves the {@code catalogId} against it, so a join storm sends about eighty bytes each, not a whole
 * record. Nothing is created server side: there is no effect entity to leave behind, which is the whole point of
 * the design. A stale or missing catalogue entry client-side is silence, not a crash.
 *
 * <p>{@code durationTicks} is on the wire deliberately: if it came only from the catalogue, a client one edit
 * behind could hold an FX forever. On the wire it cannot.
 *
 * <p>Packet id 105, registered in {@code ShuruisUtilities}. Sent by {@code CosmeticAnimationServer} to every
 * player within the animation's radius.
 */
public class PacketCosmeticAnimationPlay implements ISUPacket
{
    public String catalogId = "";

    public UUID subject;

    /** The trigger slot key (join / leave / tp_depart / tp_arrive), so the client picks the in vs out sound. */
    public String triggerKey = "";

    public ResourceLocation dimension;

    public double x;

    public double y;

    public double z;

    public float yaw;

    public int durationTicks;

    public PacketCosmeticAnimationPlay()
    {
    }

    public PacketCosmeticAnimationPlay(String catalogId, UUID subject, String triggerKey, ResourceLocation dimension,
            double x, double y, double z, float yaw, int durationTicks)
    {
        this.catalogId = catalogId == null ? "" : catalogId;
        this.subject = subject;
        this.triggerKey = triggerKey == null ? "" : triggerKey;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.durationTicks = durationTicks;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(catalogId == null ? "" : catalogId);
        buf.writeBoolean(subject != null);
        if (subject != null)
            buf.writeUUID(subject);
        buf.writeUtf(triggerKey == null ? "" : triggerKey);
        buf.writeResourceLocation(dimension == null ? new ResourceLocation("minecraft", "overworld") : dimension);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(yaw);
        buf.writeVarInt(durationTicks);
    }

    public static PacketCosmeticAnimationPlay decode(FriendlyByteBuf buf)
    {
        PacketCosmeticAnimationPlay p = new PacketCosmeticAnimationPlay();
        p.catalogId = buf.readUtf();
        if (buf.readBoolean())
            p.subject = buf.readUUID();
        p.triggerKey = buf.readUtf();
        p.dimension = buf.readResourceLocation();
        p.x = buf.readDouble();
        p.y = buf.readDouble();
        p.z = buf.readDouble();
        p.yaw = buf.readFloat();
        p.durationTicks = buf.readVarInt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticAnimationClientStore.accept(this));
    }

    public static void handler(final PacketCosmeticAnimationPlay message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
