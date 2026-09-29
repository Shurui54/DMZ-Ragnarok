package net.shurui.dev.sdu.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.shenron.ShrineColor;
import net.shurui.dev.sdu.shenron.ShrineRequiredItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> client. Opens the custom {@code ShrineScreen} for a right-clicked Shenron shrine, carrying the
 * shrine position, its colour, the colour's required items (id + count) and whether the player currently has
 * them all (computed server-side). Fully independent of DMZ's wish GUI.
 */
public class OpenShrineGuiPacket {

    private final BlockPos pos;
    private final ShrineColor color;
    private final List<ShrineRequiredItem> required;
    private final boolean hasItems;

    public OpenShrineGuiPacket(BlockPos pos, ShrineColor color, List<ShrineRequiredItem> required, boolean hasItems) {
        this.pos = pos;
        this.color = color;
        this.required = required == null ? new ArrayList<>() : required;
        this.hasItems = hasItems;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeEnum(color);
        buf.writeVarInt(required.size());
        for (ShrineRequiredItem r : required) {
            buf.writeUtf(r.item == null ? "" : r.item);
            buf.writeVarInt(Math.max(1, r.count));
        }
        buf.writeBoolean(hasItems);
    }

    public static OpenShrineGuiPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        ShrineColor color = buf.readEnum(ShrineColor.class);
        int n = buf.readVarInt();
        List<ShrineRequiredItem> req = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            req.add(new ShrineRequiredItem(buf.readUtf(), buf.readVarInt()));
        }
        boolean has = buf.readBoolean();
        return new OpenShrineGuiPacket(pos, color, req, has);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.dev.sdu.client.gui.shenron.ShrineScreen.open(pos, color, required, hasItems)));
        context.setPacketHandled(true);
    }
}
