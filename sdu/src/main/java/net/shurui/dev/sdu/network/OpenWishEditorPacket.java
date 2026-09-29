package net.shurui.dev.sdu.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.wish.WishSetData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server -> client. Opens the wishes editor with the current dragons' wish sets (each a bundle JSON). */
public class OpenWishEditorPacket {

    private static final Gson GSON = new Gson();
    private final List<String> bundles;

    public OpenWishEditorPacket(List<String> bundles) {
        this.bundles = bundles;
    }

    public void encode(FriendlyByteBuf buf) {
        writeList(buf, bundles);
    }

    public static OpenWishEditorPacket decode(FriendlyByteBuf buf) {
        return new OpenWishEditorPacket(readList(buf));
    }

    private static void writeList(FriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) {
            buf.writeUtf(s, 1_000_000);
        }
    }

    private static List<String> readList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(buf.readUtf(1_000_000));
        }
        return list;
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            List<WishSetData> sets = new ArrayList<>();
            for (String s : bundles) {
                try {
                    sets.add(WishSetData.fromBundle(GSON.fromJson(s, JsonObject.class)));
                } catch (Exception ignored) {
                }
            }
            net.shurui.dev.sdu.client.gui.wish.WishListScreen.open(sets);
        }));
        ctx.get().setPacketHandled(true);
    }
}
