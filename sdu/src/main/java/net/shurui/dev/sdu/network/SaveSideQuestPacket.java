package net.shurui.dev.sdu.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.saga.SideQuestData;
import net.shurui.dev.sdu.saga.SideQuestFileManager;

import java.util.function.Supplier;

/** Client -> server. Writes a side quest (bundle JSON) to the world save. Op-gated. */
public class SaveSideQuestPacket {

    private static final Gson GSON = new Gson();
    private final String bundle;

    public SaveSideQuestPacket(String bundle) {
        this.bundle = bundle;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(bundle, 1_000_000);
    }

    public static SaveSideQuestPacket decode(FriendlyByteBuf buf) {
        return new SaveSideQuestPacket(buf.readUtf(1_000_000));
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

    /** Apply a side quest bundle JSON on the server. Caller must have verified the sender and edit permission. */
    public static void apply(ServerPlayer player, String bundleJson) {
        try {
            SideQuestData sq = SideQuestData.fromBundle(GSON.fromJson(bundleJson, JsonObject.class));
            // fileName (when editing an existing quest) is a player-supplied relative path: reject
            // malformed/traversal values at the boundary before file I/O (the manager re-checks too).
            if (sq.fileName != null && !sq.fileName.isBlank()
                    && !net.shurui.dev.sdu.saga.SafeFileNames.isSafeRelJson(sq.fileName)) {
                player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.sidequest.invalid_file", sq.fileName), false);
                return;
            }
            String err = SideQuestFileManager.save(player.getServer(), sq);
            if (err == null) {
                net.shurui.dev.sdu.saga.SagaSpawnBindings.loadFrom(player.getServer());
                net.shurui.dev.sdu.saga.DeferredSpawnRegistry.loadFrom(player.getServer());
                net.shurui.dev.sdu.saga.TalkNpcRegistry.loadFrom(player.getServer());
                // same gap as the saga save path: without this DMZ keeps serving the side quest it read at world
                // load, so edited objective stats never reach a spawn until a restart. See DmzQuestReload.
                net.shurui.dev.sdu.saga.DmzQuestReload.reloadAndSync(player.getServer());
                net.shurui.dev.sdu.network.DmzNet.syncPreviewClonesToAll(player.getServer());
            }
            player.displayClientMessage(err == null
                    ? Component.translatable("message.dmz_ragnarok.npc.sidequest.saved", sq.id)
                    : Component.translatable("message.dmz_ragnarok.npc.sidequest.save_failed", err), false);
        } catch (Exception e) {
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.sidequest.bad_data", e.getMessage()), false);
        }
    }
}
