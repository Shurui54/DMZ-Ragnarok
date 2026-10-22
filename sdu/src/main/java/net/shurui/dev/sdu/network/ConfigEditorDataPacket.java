package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.config.editor.OptionView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server to client: opens (or refreshes) the options editor with ONE tab of server options, the list of tabs that
 * have anything for this player, the editor links for the Editors tab, and the result of the last save. One tab per
 * packet keeps it small whatever the suite grows to. Only ever sent to a player who may edit.
 */
public class ConfigEditorDataPacket {

    public record TabCount(String id, int count) {
    }

    public record LinkView(String id, String labelKey, String tipKey) {
    }

    public final List<TabCount> tabs;
    public final String tab;
    public final String query;
    public final List<OptionView> options;
    public final List<LinkView> links;
    public final Map<String, String> errors;
    public final String statusKey;
    public final List<String> statusArgs;
    public final boolean keyPresent;

    public ConfigEditorDataPacket(List<TabCount> tabs, String tab, String query, List<OptionView> options,
                                  List<LinkView> links, Map<String, String> errors, String statusKey,
                                  List<String> statusArgs, boolean keyPresent) {
        this.tabs = tabs;
        this.tab = tab;
        this.query = query;
        this.options = options;
        this.links = links;
        this.errors = errors;
        this.statusKey = statusKey;
        this.statusArgs = statusArgs;
        this.keyPresent = keyPresent;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(tabs.size());
        for (TabCount t : tabs) {
            buf.writeUtf(t.id());
            buf.writeVarInt(t.count());
        }
        buf.writeUtf(tab);
        buf.writeUtf(query);
        buf.writeVarInt(options.size());
        for (OptionView o : options) {
            o.encode(buf);
        }
        buf.writeVarInt(links.size());
        for (LinkView l : links) {
            buf.writeUtf(l.id());
            buf.writeUtf(l.labelKey());
            buf.writeUtf(l.tipKey());
        }
        buf.writeVarInt(errors.size());
        for (Map.Entry<String, String> e : errors.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue());
        }
        buf.writeUtf(statusKey);
        buf.writeVarInt(statusArgs.size());
        for (String a : statusArgs) {
            buf.writeUtf(a);
        }
        buf.writeBoolean(keyPresent);
    }

    public static ConfigEditorDataPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<TabCount> tabs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tabs.add(new TabCount(buf.readUtf(), buf.readVarInt()));
        }
        String tab = buf.readUtf();
        String query = buf.readUtf();
        int on = buf.readVarInt();
        List<OptionView> options = new ArrayList<>(on);
        for (int i = 0; i < on; i++) {
            options.add(OptionView.decode(buf));
        }
        int ln = buf.readVarInt();
        List<LinkView> links = new ArrayList<>(ln);
        for (int i = 0; i < ln; i++) {
            links.add(new LinkView(buf.readUtf(), buf.readUtf(), buf.readUtf()));
        }
        int en = buf.readVarInt();
        Map<String, String> errors = new LinkedHashMap<>();
        for (int i = 0; i < en; i++) {
            errors.put(buf.readUtf(), buf.readUtf());
        }
        String statusKey = buf.readUtf();
        int an = buf.readVarInt();
        List<String> args = new ArrayList<>(an);
        for (int i = 0; i < an; i++) {
            args.add(buf.readUtf());
        }
        boolean key = buf.readBoolean();
        return new ConfigEditorDataPacket(tabs, tab, query, options, links, errors, statusKey, args, key);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.dev.sdu.client.gui.OptionsOverviewScreen.receive(this)));
        context.setPacketHandled(true);
    }
}
