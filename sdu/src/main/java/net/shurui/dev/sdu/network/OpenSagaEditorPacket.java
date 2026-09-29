package net.shurui.dev.sdu.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.saga.SagaData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server -> client. Opens the saga editor with the current sagas (each as a bundle JSON string). */
public class OpenSagaEditorPacket {

    private static final Gson GSON = new Gson();
    private final List<String> bundles;

    public OpenSagaEditorPacket(List<String> bundles) {
        this.bundles = bundles;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(bundles.size());
        for (String s : bundles) {
            buf.writeUtf(s, 1_000_000);
        }
    }

    public static OpenSagaEditorPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(buf.readUtf(1_000_000));
        }
        return new OpenSagaEditorPacket(list);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            List<SagaData> sagas = new ArrayList<>();
            for (String s : bundles) {
                try {
                    sagas.add(SagaData.fromBundle(GSON.fromJson(s, JsonObject.class)));
                } catch (Exception ignored) {
                }
            }
            net.shurui.dev.sdu.client.gui.saga.SagaListScreen.open(sagas);
        }));
        ctx.get().setPacketHandled(true);
    }
}
