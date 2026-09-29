package net.shurui.dev.shuruis_raid_bosses.compat.sdu;

import net.minecraftforge.fml.ModList;

/**
 * Optional-dependency bridge to sdu's editor hub: registers a "Raid Bosses" section into the shared
 * {@code /rg npc edit} menu when sdu is loaded.
 *
 * <p>This outer class names NO sdu types and is safe to classload when sdu is absent: it only checks
 * {@link ModList#isLoaded(String)} and delegates to the inner {@link Sdu} holder, the only class touching
 * {@code net.shurui.dev.sdu.*}, never classloaded unless sdu is present (the optional-dependency pattern).
 * Client-side only.
 */
public final class SduHubCompat {

    private SduHubCompat() {}

    /** Call from client setup. sdu is now part of this same container, so this always registers. */
    public static void register() {
        Sdu.register();
    }

    /**
     * Return to the shared sdu hub instead of this mod's own {@code RaidHubScreen}. Client-side only.
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
                    .section("raid", net.minecraft.network.chat.Component.translatable(
                            "compat.dmz_ragnarok.raid.npc.section"))
                    .entry("dmz_ragnarok:raids",
                            net.minecraft.network.chat.Component.translatable("compat.dmz_ragnarok.raid.npc.entry"),
                            net.minecraft.network.chat.Component.translatable("compat.dmz_ragnarok.raid.npc.entry_desc"),
                            () -> net.shurui.dev.shuruis_raid_bosses.network.RaidNet.sendToServer(
                                    new net.shurui.dev.shuruis_raid_bosses.network.OpenEditorRequestPacket("raid")))
                    .entry("dmz_ragnarok:rifts",
                            net.minecraft.network.chat.Component.translatable("compat.dmz_ragnarok.rift.npc.entry"),
                            net.minecraft.network.chat.Component.translatable(
                                    "compat.dmz_ragnarok.rift.npc.entry_desc"),
                            () -> net.shurui.dev.shuruis_raid_bosses.network.RaidNet.sendToServer(
                                    new net.shurui.dev.shuruis_raid_bosses.network.OpenEditorRequestPacket("rift")))
                    .register();
        }
    }
}
