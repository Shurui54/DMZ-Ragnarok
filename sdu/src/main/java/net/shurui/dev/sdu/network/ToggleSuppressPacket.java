package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.compat.DmzCompat;
import net.shurui.dev.sdu.network.DmzNet;

import java.util.function.Supplier;

/**
 * Client -> server. Suppress or restore a DMZ <em>default</em> race or class, server-wide and persistently.
 * Op-gated; the client UI is advisory only. The matching {@link DmzCompat} API updates the config, reloads
 * DMZ, re-strips the id and resyncs DMZ's configs so a suppressed race/class drops from the loaded list;
 * then {@link DmzNet#syncSuppressedDefaultsToAll} keeps every editor and the client cache in step (suppressed
 * defaults stay listable for restoration).
 */
public class ToggleSuppressPacket {

    /** What the id refers to on the wire. */
    public enum Kind {
        RACE,
        CLASS
    }

    private final Kind kind;
    private final String id;
    private final boolean suppress;

    public ToggleSuppressPacket(Kind kind, String id, boolean suppress) {
        this.kind = kind;
        this.id = id;
        this.suppress = suppress;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(kind);
        buf.writeUtf(id);
        buf.writeBoolean(suppress);
    }

    public static ToggleSuppressPacket decode(FriendlyByteBuf buf) {
        return new ToggleSuppressPacket(buf.readEnum(Kind.class), buf.readUtf(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null
                    || !net.shurui.dev.sdu.util.SduPerms.canEdit(player)) {
                return;
            }
            MinecraftServer server = player.getServer();
            switch (kind) {
                case RACE -> {
                    if (suppress) {
                        DmzCompat.suppressRace(server, id);
                    } else {
                        DmzCompat.unsuppressRace(server, id);
                    }
                }
                case CLASS -> {
                    if (suppress) {
                        DmzCompat.suppressClass(server, id);
                    } else {
                        DmzCompat.unsuppressClass(server, id);
                    }
                }
            }
            DmzNet.syncSuppressedDefaultsToAll(server);
            Component kindLabel = Component.translatable("message.dmz_ragnarok.npc.suppress.kind." + kind.name().toLowerCase(java.util.Locale.ROOT));
            player.displayClientMessage(Component.translatable(
                    suppress ? "message.dmz_ragnarok.npc.suppress.suppressed" : "message.dmz_ragnarok.npc.suppress.restored",
                    kindLabel, id), false);
        });
        context.setPacketHandled(true);
    }
}
