package net.shurui.shuruisutilities.character;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** Server -&gt; client: opens/refreshes the character-slot picker with the player's slot list. */
public class PacketCharacterGui implements ISUPacket
{
    public List<String> names = new ArrayList<>();
    public int active;
    public int max;
    // tournament character row state: whether the feature is on, whether one exists, and whether it is in use now
    public boolean tourFeature;
    public boolean tourPresent;
    public boolean tourActive;

    public PacketCharacterGui() {}

    public PacketCharacterGui(List<String> names, int active, int max,
                              boolean tourFeature, boolean tourPresent, boolean tourActive)
    {
        this.names = names;
        this.active = active;
        this.max = max;
        this.tourFeature = tourFeature;
        this.tourPresent = tourPresent;
        this.tourActive = tourActive;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(names.size());
        for (String n : names)
            buf.writeUtf(n);
        buf.writeVarInt(active);
        buf.writeVarInt(max);
        buf.writeBoolean(tourFeature);
        buf.writeBoolean(tourPresent);
        buf.writeBoolean(tourActive);
    }

    public static PacketCharacterGui decode(FriendlyByteBuf buf)
    {
        PacketCharacterGui p = new PacketCharacterGui();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.names.add(buf.readUtf());
        p.active = buf.readVarInt();
        p.max = buf.readVarInt();
        p.tourFeature = buf.readBoolean();
        p.tourPresent = buf.readBoolean();
        p.tourActive = buf.readBoolean();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            // A character switch replaces the DMZ character server-side and re-syncs it; drop DMZ's client-side
            // stat cache so the stats screen re-reads the freshly-synced values instead of the old character's.
            com.dragonminez.common.stats.StatsCapability.clearClientCache();
            net.shurui.shuruisutilities.character.client.CharacterSelectScreen.openOrUpdate(
                    names, active, max, tourFeature, tourPresent, tourActive);
        });
    }

    public static void handler(final PacketCharacterGui message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
