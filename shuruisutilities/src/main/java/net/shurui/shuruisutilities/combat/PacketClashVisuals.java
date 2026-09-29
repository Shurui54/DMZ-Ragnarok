package net.shurui.shuruisutilities.combat;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

/**
 * server to client: these two entities are locked in a melee clash, or have stopped being.
 *
 * <p>Separate from {@link PacketClashStart} because it goes to a different audience. The start packet is for the two
 * fighters and opens their minigame; this one goes to EVERYONE nearby, because the punching is something onlookers
 * should see. Sending the start packet to bystanders instead would put the rhythm overlay on screens belonging to
 * people who are not in the fight.
 *
 * <p>Entity ids rather than UUIDs: the client is looking these up in its own level, which is exactly what ids are for,
 * and it keeps the packet to a few bytes at the rate a fight produces them.
 */
public class PacketClashVisuals implements ISUPacket
{
    public int entityA;
    public int entityB;
    public boolean active;

    public PacketClashVisuals() {}

    public PacketClashVisuals(int entityA, int entityB, boolean active)
    {
        this.entityA = entityA;
        this.entityB = entityB;
        this.active = active;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(entityA);
        buf.writeVarInt(entityB);
        buf.writeBoolean(active);
    }

    public static PacketClashVisuals decode(FriendlyByteBuf buf)
    {
        PacketClashVisuals p = new PacketClashVisuals();
        p.entityA = buf.readVarInt();
        p.entityB = buf.readVarInt();
        p.active = buf.readBoolean();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.combat.ClashVisuals.set(entityA, entityB, active));
    }

    public static void handler(final PacketClashVisuals message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
