package net.shurui.dev.sdu.api;

import java.util.ArrayList;
import java.util.List;

// public extension point for the /rg npc edit hub. sibling mods add flat editor entries to the bottom of the
// menu so every editor shares one command + screen. register() on the CLIENT (FMLClientSetupEvent), guarded
// by isModLoaded("sdu") so it stays a soft dep; the open runnable runs client-side, usually sending that mod's
// own "open editor" packet. mods adding a hub entry should also drop their own "edit" command when sdu is
// present so there's one way in.
public final class SduHub {

    // id stable+unique (de-dupes re-registration). labelKey a translation key the owning mod ships. open runs
    // client-side when chosen.
    public record Entry(String id, String labelKey, Runnable open) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private SduHub() {
    }

    // add or replace (by id). call once during client setup.
    public static synchronized void register(String id, String labelKey, Runnable open) {
        if (id == null || labelKey == null || open == null) {
            return;
        }
        ENTRIES.removeIf(e -> e.id().equals(id));
        ENTRIES.add(new Entry(id, labelKey, open));
    }

    // in registration order.
    public static synchronized List<Entry> entries() {
        return new ArrayList<>(ENTRIES);
    }

    // open the hub on the client, e.g. from a sibling editor's back button. client-side only.
    public static void openMenu() {
        net.shurui.dev.sdu.client.gui.SduHubScreen.open();
    }
}
