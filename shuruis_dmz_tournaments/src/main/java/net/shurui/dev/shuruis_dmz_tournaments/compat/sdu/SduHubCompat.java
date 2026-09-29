package net.shurui.dev.shuruis_dmz_tournaments.compat.sdu;

import net.minecraftforge.fml.ModList;

/**
 * Optional-dependency bridge to sdu's editor hub: when sdu is loaded, registers a "Tournaments" section into
 * the shared {@code /rg npc edit} menu so this mod's editors are reachable there too.
 *
 * <p>This outer class names <b>no</b> sdu types and is safe to classload when sdu is absent: it only checks
 * {@link ModList#isLoaded(String)} and delegates to the inner {@link Sdu} holder, the only class that
 * references {@code net.shurui.dev.sdu.*}, which is never classloaded unless sdu is present. See
 * the optional-dependency pattern. Client-side only.
 */
public final class SduHubCompat {

    private SduHubCompat() {}

    /** Call from client setup. sdu is now part of this same container, so this always registers. */
    public static void register() {
        Sdu.register();
    }

    /**
     * Return to the shared sdu hub instead of this mod's own {@code HubScreen}. Client-side only.
     *
     * @return {@code true} if sdu is loaded and its hub was opened (caller should do nothing else);
     *         {@code false} if sdu is absent (caller should fall back to its own hub).
     */
    public static boolean openSduHub() {
        Sdu.openHub();
        return true;
    }

    /** The only class that names sdu types; never classloaded unless sdu is present. */
    private static final class Sdu {
        static void openHub() {
            net.shurui.dev.sdu.api.SduHub.openMenu();
        }

        static void register() {
            net.shurui.dev.sdu.api.SduHubExtensions
                    .section("tournaments", net.minecraft.network.chat.Component.translatable("compat.dmz_ragnarok.tournaments.npc.section"))
                    .entry("dmz_ragnarok:tournaments",
                            net.minecraft.network.chat.Component.translatable("compat.dmz_ragnarok.tournaments.npc.entry"),
                            net.minecraft.network.chat.Component.translatable("compat.dmz_ragnarok.tournaments.npc.entry_desc"),
                            () -> net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet.sendToServer(
                                    new net.shurui.dev.shuruis_dmz_tournaments.network.OpenEditorRequestPacket("tournament")))
                    .register();
        }
    }
}
