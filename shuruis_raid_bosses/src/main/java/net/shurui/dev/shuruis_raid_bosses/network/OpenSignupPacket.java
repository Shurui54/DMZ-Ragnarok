package net.shurui.dev.shuruis_raid_bosses.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C on right-clicking a sign-up NPC: raid id/name and a state snapshot so the client opens the sign-up
 * screen with correct button states.
 */
public class OpenSignupPacket {
    private final String defId;
    private final String name;
    private final boolean signupOpen;
    private final boolean signedUp;
    private final int count;
    private final int stateOrdinal;

    public OpenSignupPacket(String defId, String name, boolean signupOpen, boolean signedUp, int count, int stateOrdinal) {
        this.defId = defId;
        this.name = name;
        this.signupOpen = signupOpen;
        this.signedUp = signedUp;
        this.count = count;
        this.stateOrdinal = stateOrdinal;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(defId);
        buf.writeUtf(name);
        buf.writeBoolean(signupOpen);
        buf.writeBoolean(signedUp);
        buf.writeVarInt(count);
        buf.writeVarInt(stateOrdinal);
    }

    public static OpenSignupPacket decode(FriendlyByteBuf buf) {
        return new OpenSignupPacket(buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readBoolean(),
                buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> net.shurui.dev.shuruis_raid_bosses.client.ClientPacketHandler
                                .openSignup(defId, name, signupOpen, signedUp, count, stateOrdinal)));
        ctx.get().setPacketHandled(true);
    }
}
