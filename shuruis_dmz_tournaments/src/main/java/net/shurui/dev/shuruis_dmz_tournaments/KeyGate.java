package net.shurui.dev.shuruis_dmz_tournaments;

// Thin gate for the Tournaments module, kept for its callers: every answer comes from the RagnarokKey facade.
// The Ragnarok Key mod (dmz_ragnarok_key) IS the key (SF), and presence means the key mod installed its hooks,
// never that a jar with some mod id is on the list.
public final class KeyGate {

    private KeyGate() {}

    // Whether this server holds the Ragnarok Key.
    public static boolean present() {
        return net.shurui.dev.sdu.api.RagnarokKey.present();
    }

    // Whether key-gated features unlock here: simply key presence (no singleplayer/LAN exemption).
    public static boolean unlocked() {
        return net.shurui.dev.sdu.api.RagnarokKey.unlocked();
    }

    // Delegates to the facade so a boot prints one shared status line, not one per tree.
    public static void logStatusOnce() {
        net.shurui.dev.sdu.api.RagnarokKey.logStatusOnce();
    }
}
