package net.shurui.shuruisutilities;

/**
 * Thin gate kept for SU's callers and for the parity {@code ModuleDump}, which reads {@link #present()} and
 * {@link #unlocked()} reflectively. Every answer comes from the {@code RagnarokKey} facade.
 *
 * <p>The Ragnarok Key mod ({@code dmz_ragnarok_key}) IS the key (SF), and presence means the key mod installed its
 * hooks, never that a jar with some mod id is on the list.</p>
 *
 * <p><b>A running dedicated server does NOT imply a key.</b> {@code ShuruisUtilities#resolveServerKeyTier} logs the
 * tier and lets the server boot, and {@code PublicContent.enforce()} checks it is running the public set. Every gate
 * therefore has to be asked, every time.</p>
 */
public final class KeyGate {

    private KeyGate() {}

    // The Ragnarok Key is present. Routed through the RagnarokKey facade, the one place the key's identity lives.
    public static boolean present() {
        return net.shurui.dev.sdu.api.RagnarokKey.present();
    }

    // key-gated features unlocked here? Simply key presence (no singleplayer/LAN exemption).
    public static boolean unlocked() {
        return net.shurui.dev.sdu.api.RagnarokKey.unlocked();
    }

    // Delegates to the facade so a boot prints one shared status line, not one per tree.
    public static void logStatusOnce() {
        net.shurui.dev.sdu.api.RagnarokKey.logStatusOnce();
    }
}
