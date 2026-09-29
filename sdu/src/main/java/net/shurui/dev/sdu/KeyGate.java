package net.shurui.dev.sdu;

// Thin gate kept for its callers: every answer comes from the RagnarokKey facade. The Ragnarok Key mod
// (dmz_ragnarok_key) IS the key (SF), and presence means the key mod installed its hooks (KeyFeatures
// "ragnarok_key"), never that a jar with some mod id is on the list. There is no singleplayer/LAN exemption: without
// the key the private feature set is off everywhere.
public final class KeyGate {

    private KeyGate() {}

    // server/common: whether this server holds the Ragnarok Key. This is the boolean the server syncs to clients.
    public static boolean present() {
        return net.shurui.dev.sdu.api.RagnarokKey.present();
    }

    // key-gated features unlocked here? Simply key presence, routed through the RagnarokKey facade.
    public static boolean unlocked() {
        return net.shurui.dev.sdu.api.RagnarokKey.unlocked();
    }

    // Our PRIVATE imported worlds (kaiow, namekow): the key is required OUTRIGHT. Config.allowPrivateWorldsWithoutKey
    // is kept as a field so existing sdu-common.toml files stay valid, but it no longer opens the worlds.
    public static boolean privateWorldsUnlocked() {
        return present();
    }

    // client-side setter, kept for callers: delegates to the ClientGate, the one client-side key flag.
    public static void setServerHasKey(boolean value) {
        net.shurui.dev.sdu.api.ClientGate.set(value);
    }

    public static boolean serverHasKey() {
        return net.shurui.dev.sdu.api.ClientGate.key();
    }

    // Delegates to the facade so a boot prints one shared status line, not one per tree.
    public static void logStatusOnce() {
        net.shurui.dev.sdu.api.RagnarokKey.logStatusOnce();
    }
}
