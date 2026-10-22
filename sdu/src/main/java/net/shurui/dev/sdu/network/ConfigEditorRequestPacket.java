package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.config.editor.ConfigEditorRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Client to server, the only request the options editor sends: show a tab (or search), save edits, or open one of
 * the linked editors. Every mode is permission checked on the server; a save is checked option by option against
 * its spec before anything is stored.
 */
public class ConfigEditorRequestPacket {

    public static final int OPEN = 0;
    public static final int SAVE = 1;
    public static final int LINK = 2;

    /** Most edits one save may carry (a whole tab of the largest file is under 100). */
    private static final int MAX_EDITS = 1024;

    private final int mode;
    private final String tab;
    private final String query;
    private final Map<String, String> edits;

    public ConfigEditorRequestPacket(int mode, String tab, String query, Map<String, String> edits) {
        this.mode = mode;
        this.tab = tab == null ? "" : tab;
        this.query = query == null ? "" : query;
        this.edits = edits == null ? Map.of() : edits;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(mode);
        buf.writeUtf(tab, 256);
        buf.writeUtf(query, 256);
        buf.writeVarInt(edits.size());
        for (Map.Entry<String, String> e : edits.entrySet()) {
            buf.writeUtf(e.getKey(), 512);
            buf.writeUtf(e.getValue());
        }
    }

    public static ConfigEditorRequestPacket decode(FriendlyByteBuf buf) {
        int mode = buf.readVarInt();
        String tab = buf.readUtf(256);
        String query = buf.readUtf(256);
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_EDITS) {
            throw new IllegalArgumentException("too many config edits in one packet: " + n);
        }
        Map<String, String> edits = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            edits.put(buf.readUtf(512), buf.readUtf());
        }
        return new ConfigEditorRequestPacket(mode, tab, query, edits);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            switch (mode) {
                case SAVE -> ConfigEditorRegistry.save(player, tab, query, edits);
                case LINK -> ConfigEditorRegistry.openLink(player, tab);
                default -> ConfigEditorRegistry.open(player, tab, query);
            }
        });
        context.setPacketHandled(true);
    }
}
