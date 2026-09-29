package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C: the saved Custom NPC clone list ({@code "tab$name"} tokens), stored client-side in
 * {@link net.shurui.dev.sdu.client.ClientCloneList} for the editor's "Saved NPC" picker.
 */
public class SyncClonesPacket {

    private final List<String> tokens;

    public SyncClonesPacket(List<String> tokens) {
        this.tokens = tokens;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(tokens.size());
        for (String t : tokens) {
            buf.writeUtf(t);
        }
    }

    public static SyncClonesPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(buf.readUtf());
        }
        return new SyncClonesPacket(list);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> {
                    net.shurui.dev.sdu.client.ClientCloneList.replace(tokens);
                    net.shurui.dev.sdu.client.gui.saga.ObjectiveEditScreen.onClonesSynced();
                    net.shurui.dev.sdu.client.gui.transform.TransformChainEditScreen.onClonesSynced();
                }));
        ctx.get().setPacketHandled(true);
    }
}
