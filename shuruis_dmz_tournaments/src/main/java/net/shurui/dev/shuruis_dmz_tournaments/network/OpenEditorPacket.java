package net.shurui.dev.shuruis_dmz_tournaments.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** S2C. Opens the tournament editor; defs travel as NBT so the client screens need no server-only state. */
public class OpenEditorPacket {
    private final List<CompoundTag> defs;

    public OpenEditorPacket(List<CompoundTag> defs) {
        this.defs = defs;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(defs.size());
        for (CompoundTag t : defs) buf.writeNbt(t);
    }

    public static OpenEditorPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<CompoundTag> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readNbt());
        return new OpenEditorPacket(list);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> net.shurui.dev.shuruis_dmz_tournaments.client.ClientPacketHandler.openEditor(defs)));
        ctx.get().setPacketHandled(true);
    }
}
