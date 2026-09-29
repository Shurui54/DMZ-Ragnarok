package net.shurui.dev.shuruis_raid_bosses.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_raid_bosses.entity.RaidNpcs;

import java.util.function.Supplier;

/** Client -&gt; server. The browser asks the server to open a chosen raid's sign-up screen (fresh state). */
public class RequestSignupPacket {
    private final String raidId;

    public RequestSignupPacket(String raidId) {
        this.raidId = raidId;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(raidId);
    }

    public static RequestSignupPacket decode(FriendlyByteBuf buf) {
        return new RequestSignupPacket(buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp != null) RaidNpcs.openSignup(sp, raidId);
        });
        ctx.get().setPacketHandled(true);
    }
}
