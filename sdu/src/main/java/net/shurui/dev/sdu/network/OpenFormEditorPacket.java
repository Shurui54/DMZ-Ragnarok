package net.shurui.dev.sdu.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.form.FormGroupData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server -> client. Opens the form editor with the current DMZ form groups (each a bundle JSON). */
public class OpenFormEditorPacket {

    private static final Gson GSON = new Gson();
    private final List<String> bundles;

    public OpenFormEditorPacket(List<String> bundles) {
        this.bundles = bundles;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(bundles.size());
        for (String s : bundles) {
            buf.writeUtf(s, 2_000_000);
        }
    }

    public static OpenFormEditorPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(buf.readUtf(2_000_000));
        }
        return new OpenFormEditorPacket(list);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            List<FormGroupData> groups = new ArrayList<>();
            for (String s : bundles) {
                try {
                    groups.add(FormGroupData.fromBundle(GSON.fromJson(s, JsonObject.class)));
                } catch (Exception ignored) {
                }
            }
            net.shurui.dev.sdu.client.gui.form.FormRaceListScreen.open(groups);
        }));
        ctx.get().setPacketHandled(true);
    }
}
