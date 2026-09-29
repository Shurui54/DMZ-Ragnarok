package net.shurui.shuruisutilities.energy;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * client -&gt; server: the player pressed the Ragnarok ability activate key.
 *
 * <p>Carries nothing at all. WHICH ability fires is decided entirely on the server from the role the player actually
 * holds, so a client cannot ask for an ability it has no right to: a forged packet from someone with no role simply
 * does nothing. Putting an ability id in here would make the client the authority on that, which is exactly the
 * thing worth avoiding for abilities that instantly kill or make a player unhittable.
 */
public class PacketAbilityActivate implements ISUPacket
{
    public PacketAbilityActivate() {}

    @Override
    public void encode(FriendlyByteBuf buf)
    {
    }

    public static PacketAbilityActivate decode(FriendlyByteBuf buf)
    {
        return new PacketAbilityActivate();
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player != null)
            net.shurui.shuruisutilities.api.key.RoleHooks.get().activateAbility(player); // keyless: nothing
    }

    public static void handler(final PacketAbilityActivate message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
