package net.shurui.shuruisutilities.character;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: clear the four DMZ custom-hair objects on the receiving player's own entity capability.
 *
 * <p>The server side of a character switch already blanks the hair before it loads the incoming character (see
 * {@link CharacterSlots#clearHair}). That fixes the SERVER copy, but the switch then re-syncs the character to the
 * client with DragonMineZ's own {@code StatsSyncS2C}, whose client handler runs {@code StatsData.load} ->
 * {@code Character.load}. That load is ADDITIVE for hair: {@code CustomHair.load} only overwrites the strands the
 * incoming tag names and skips every face the tag does not mention, and {@code CustomHair.save} omits base and
 * invisible strands entirely. So loading character B's hair onto the client's still-present character A leaves every
 * strand and face B did not explicitly restyle, which is the hair that "combines" across a slot switch. DMZ never
 * hits this because a normal player only ever loads a character's hair back onto itself.
 *
 * <p>This packet clears the client's hair objects to base so the {@code StatsSyncS2C} that follows writes B's hair
 * onto a blank head, matching the server. It MUST be sent immediately BEFORE the DMZ stats sync so the client
 * processes the two in order: both packets ride the same connection and each handler enqueues to the client main
 * thread in arrival order, so send order is apply order. No payload: it always acts on the receiver's own player.
 */
public class PacketClearClientHair implements ISUPacket
{
    public PacketClearClientHair() {}

    public static PacketClearClientHair decode(FriendlyByteBuf buf)
    {
        return new PacketClearClientHair();
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Double-lambda so the client-only body is not linked on a dedicated server.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
            if (p == null)
                return;
            p.getCapability(com.dragonminez.common.stats.StatsCapability.INSTANCE).ifPresent(sd -> {
                try
                {
                    var character = sd.getCharacter();
                    clear(() -> character.getHairBase().clear());
                    clear(() -> character.getHairSSJ().clear());
                    clear(() -> character.getHairSSJ2().clear());
                    clear(() -> character.getHairSSJ3().clear());
                }
                catch (Throwable ignored)
                {
                    // A DMZ shape change should cost the hair reset, not the swap. Same contract as the server side.
                }
            });
        });
    }

    private static void clear(Runnable step)
    {
        try
        {
            step.run();
        }
        catch (Throwable ignored)
        {
        }
    }

    public static void handler(final PacketClearClientHair message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
