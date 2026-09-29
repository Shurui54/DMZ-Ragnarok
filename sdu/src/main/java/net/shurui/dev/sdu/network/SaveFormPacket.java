package net.shurui.dev.sdu.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.form.FormFileManager;
import net.shurui.dev.sdu.form.FormGroupData;

import java.util.function.Supplier;

/** Client -> server. Writes a form group to DMZ's config format and reloads DMZ configs. Op-gated. */
public class SaveFormPacket {

    private static final Gson GSON = new Gson();
    private final String bundle;

    public SaveFormPacket(String bundle) {
        this.bundle = bundle;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(bundle, 2_000_000);
    }

    public static SaveFormPacket decode(FriendlyByteBuf buf) {
        return new SaveFormPacket(buf.readUtf(2_000_000));
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

    /** Apply a form group bundle JSON on the server. Caller must have verified the sender and edit permission. */
    public static void apply(ServerPlayer player, String bundleJson) {
        try {
            FormGroupData group = FormGroupData.fromBundle(GSON.fromJson(bundleJson, JsonObject.class));
            String err = FormFileManager.save(group);
            if (err == null) {
                // Derive the display-name lang keys server-side and push them out.
                net.shurui.dev.sdu.lang.GeneratedLangStore.putAll(net.shurui.dev.sdu.lang.GeneratedNames.formKeys(group));
                DmzNet.syncLangToAll(player.getServer());
                DmzNet.syncFormAurasToAll(); // push the saved extra aura layers to every client
                DmzNet.syncFormLevelGatesToAll(); // push the saved per-form minimum-level gates to every client
                DmzNet.syncFormAlignmentGatesToAll(); // push the saved per-form alignment gates to every client
                boolean resynced = net.shurui.dev.sdu.compat.DmzCompat.resyncConfigsToAll(player.getServer());
                player.displayClientMessage(Component.translatable(resynced
                        ? "message.dmz_ragnarok.npc.form.saved.synced"
                        : "message.dmz_ragnarok.npc.form.saved.rejoin", group.groupName), false);
            } else {
                player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.form.save_failed", err), false);
            }
        } catch (Exception e) {
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.form.bad_data", e.getMessage()), false);
        }
    }
}
