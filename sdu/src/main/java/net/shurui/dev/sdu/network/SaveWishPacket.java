package net.shurui.dev.sdu.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.wish.WishFileManager;
import net.shurui.dev.sdu.wish.WishSetData;

import java.util.function.Supplier;

/** Client -> server. Writes a dragon's wish set (bundle JSON) to the world save and reloads DMZ wishes. Op-gated. */
public class SaveWishPacket {

    private static final Gson GSON = new Gson();
    private final String bundle;

    public SaveWishPacket(String bundle) {
        this.bundle = bundle;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(bundle, 1_000_000);
    }

    public static SaveWishPacket decode(FriendlyByteBuf buf) {
        return new SaveWishPacket(buf.readUtf(1_000_000));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null || !net.shurui.dev.sdu.util.SduPerms.canEdit(player)) {
                return;
            }
            apply(player, bundle);
        });
        context.setPacketHandled(true);
    }

    /** Apply a wish set bundle JSON on the server. Caller must have verified the sender and edit permission. */
    public static void apply(ServerPlayer player, String bundleJson) {
        try {
            WishSetData set = WishSetData.fromBundle(GSON.fromJson(bundleJson, JsonObject.class));
            String err = WishFileManager.save(player.getServer(), set);
            player.displayClientMessage(err == null
                    ? Component.translatable("message.dmz_ragnarok.npc.wish.saved", set.wishes.size(), set.dragon)
                    : Component.translatable("message.dmz_ragnarok.npc.wish.save_failed", err), false);
        } catch (Exception e) {
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.wish.bad_data", e.getMessage()), false);
        }
    }
}
