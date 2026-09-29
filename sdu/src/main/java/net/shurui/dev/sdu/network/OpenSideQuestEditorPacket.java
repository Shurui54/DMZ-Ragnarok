package net.shurui.dev.sdu.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.saga.SideQuestData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server -> client. Opens the side-quest editor with the current side quests (each a bundle JSON string). */
public class OpenSideQuestEditorPacket {

    private static final Gson GSON = new Gson();
    private final List<String> bundles;

    public OpenSideQuestEditorPacket(List<String> bundles) {
        this.bundles = bundles;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(bundles.size());
        for (String s : bundles) {
            buf.writeUtf(s, 1_000_000);
        }
    }

    public static OpenSideQuestEditorPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(buf.readUtf(1_000_000));
        }
        return new OpenSideQuestEditorPacket(list);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            List<SideQuestData> quests = new ArrayList<>();
            for (String s : bundles) {
                try {
                    quests.add(SideQuestData.fromBundle(GSON.fromJson(s, JsonObject.class)));
                } catch (Exception ignored) {
                }
            }
            net.shurui.dev.sdu.client.gui.saga.SideQuestListScreen.open(quests);
        }));
        ctx.get().setPacketHandled(true);
    }
}
