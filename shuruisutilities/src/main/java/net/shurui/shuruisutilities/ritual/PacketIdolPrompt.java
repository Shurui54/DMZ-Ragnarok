package net.shurui.shuruisutilities.ritual;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

/**
 * server -> client: open the idol's confirmation, with the terms already decided.
 *
 * <p>The numbers travel with the packet rather than being worked out on the client, because the client cannot see the
 * player's DMZ level or which ball set is currently spent, and because a prompt that computed its own answer could
 * disagree with the one the server is about to enforce.
 */
public class PacketIdolPrompt implements ISUPacket
{
    public String set = "";
    public int level;
    public int requiredLevel;
    public int levelCost;
    public int ballCount;
    public int refusal;

    public PacketIdolPrompt() {}

    public PacketIdolPrompt(DragonBallRecreation.Prompt prompt)
    {
        this.set = prompt.set();
        this.level = prompt.level();
        this.requiredLevel = prompt.requiredLevel();
        this.levelCost = prompt.levelCost();
        this.ballCount = prompt.ballCount();
        this.refusal = prompt.refusal().ordinal();
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(set);
        buf.writeVarInt(level);
        buf.writeVarInt(requiredLevel);
        buf.writeVarInt(levelCost);
        buf.writeVarInt(ballCount);
        buf.writeVarInt(refusal);
    }

    public static PacketIdolPrompt decode(FriendlyByteBuf buf)
    {
        PacketIdolPrompt p = new PacketIdolPrompt();
        p.set = buf.readUtf();
        p.level = buf.readVarInt();
        p.requiredLevel = buf.readVarInt();
        p.levelCost = buf.readVarInt();
        p.ballCount = buf.readVarInt();
        p.refusal = buf.readVarInt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.gui.ShenronIdolScreen.open(this));
    }

    public static void handler(final PacketIdolPrompt message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
