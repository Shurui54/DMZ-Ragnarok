package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.shenron.ShrineColor;
import net.shurui.dev.sdu.shenron.ShrineWish;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> client (summoner only). After a successful summon, opens the custom {@code WishSelectScreen} with
 * the colour's available wishes (id / name / description). Commands are never sent to the client - only the
 * displayable fields.
 */
public class OpenWishSelectPacket {

    private final int entityId;
    private final ShrineColor color;
    private final List<ShrineWish> wishes;

    public OpenWishSelectPacket(int entityId, ShrineColor color, List<ShrineWish> wishes) {
        this.entityId = entityId;
        this.color = color;
        this.wishes = wishes == null ? new ArrayList<>() : wishes;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeEnum(color);
        buf.writeVarInt(wishes.size());
        for (ShrineWish w : wishes) {
            buf.writeUtf(w.id == null ? "" : w.id);
            buf.writeUtf(w.name == null ? "" : w.name);
            buf.writeUtf(w.description == null ? "" : w.description);
        }
    }

    public static OpenWishSelectPacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        ShrineColor color = buf.readEnum(ShrineColor.class);
        int n = buf.readVarInt();
        List<ShrineWish> wishes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ShrineWish w = new ShrineWish();
            w.id = buf.readUtf();
            w.name = buf.readUtf();
            w.description = buf.readUtf();
            wishes.add(w);
        }
        return new OpenWishSelectPacket(entityId, color, wishes);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.dev.sdu.client.gui.shenron.WishSelectScreen.open(entityId, color, wishes)));
        context.setPacketHandled(true);
    }
}
