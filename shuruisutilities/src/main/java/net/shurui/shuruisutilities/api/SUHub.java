package net.shurui.shuruisutilities.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Extension point for the {@code /rginfo edit} hub menu (mirrors sdu's {@code SduHub}): other mods add
 * their own editor entries so every editor shares one command and screen.
 *
 * <p>Register on the CLIENT (e.g. FMLClientSetupEvent), guarded by
 * {@code ModList.isLoaded("shuruisutilities")} so it stays a soft dep. The entry's {@code open} runnable
 * runs client-side on click, usually sending that mod's own "open editor" packet.
 */
public final class SUHub {

    // id = stable unique id (dedupes + orders); labelKey = lang key; open = client action on select
    public record Entry(String id, String labelKey, Runnable open) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private SUHub() {
    }

    // add or replace (by id) a hub entry; call once during client setup
    public static synchronized void register(String id, String labelKey, Runnable open) {
        if (id == null || labelKey == null || open == null) {
            return;
        }
        ENTRIES.removeIf(e -> e.id().equals(id));
        ENTRIES.add(new Entry(id, labelKey, open));
    }

    // registered entries, in registration order
    public static synchronized List<Entry> entries() {
        return new ArrayList<>(ENTRIES);
    }

    // back to the SU admin hub; call from an editor's Menu/back button. client-side; server rebuilds the
    // permission-filtered hub.
    public static void openMenu() {
        net.shurui.shuruisutilities.commons.network.NetworkUtils.INSTANCE.sendToServer(
                new net.shurui.shuruisutilities.hub.PacketOpenEditor("menu:admin"));
    }
}
