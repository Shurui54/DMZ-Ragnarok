package net.shurui.shuruisutilities.compat.sdu;

import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.hub.PacketOpenEditor;

// bridge to sdu's editor hub: when sdu is loaded, adds a "Shurui's Utilities" section to the shared /rg npc edit
// menu so SU's admin editors are reachable there too. client-only.
// outer class names NO sdu types (classload-safe when sdu absent): checks isLoaded and delegates to the inner
// Sdu holder, the only class referencing net.shurui.dev.sdu.*, never classloaded unless sdu is present.
// each entry reuses SU's "open editor" flow (PacketOpenEditor -> server perm-checks + pushes the populated
// editor back), same as /rggui admin, so no empty server-data screen is built client-side.
public final class SduHubCompat {

    private SduHubCompat() {}

    // from client setup. sdu is now part of this same container, so this always registers.
    public static void register() {
        Sdu.register();
    }

    // open the shared sdu hub instead of SU's own admin hub (client-only). sdu is now part of this same
    // container, so this always opens the shared hub and returns true.
    public static boolean openSduHub() {
        Sdu.openHub();
        return true;
    }

    // send the "open editor" request; server gates by perm and opens the screen
    private static Runnable open(String which) {
        return () -> NetworkUtils.INSTANCE.sendToServer(new PacketOpenEditor(which));
    }

    // only class that names sdu types; never classloaded unless sdu is present
    private static final class Sdu {
        static void openHub() {
            net.shurui.dev.sdu.api.SduHub.openMenu();
        }

        static void register() {
            net.shurui.dev.sdu.api.SduHubExtensions
                    .section("core", Component.literal("Shurui's Utilities"))
                    .entry("dmz_ragnarok:permissions", Component.literal("Permissions"),
                            Component.literal("Open the permissions / groups editor"), open("permissions"))
                    .entry("dmz_ragnarok:guilds", Component.literal("Guilds"),
                            Component.literal("Open the guild admin editor"), open("guilds"))
                    .entry("dmz_ragnarok:economy", Component.literal("Economy"),
                            Component.literal("Open the economy / currencies editor"), open("economy"))
                    .entry("dmz_ragnarok:holograms", Component.literal("Holograms"),
                            Component.literal("Open the hologram list editor"), open("holograms"))
                    .entry("dmz_ragnarok:crates", Component.literal("Crates"),
                            Component.literal("Open the crate list editor"), open("crates"))
                    .entry("dmz_ragnarok:portals", Component.literal("Portals"),
                            Component.literal("Open the portal list editor"), open("portals"))
                    .entry("dmz_ragnarok:worldborder", Component.literal("World Border"),
                            Component.literal("Open the world border editor"), open("worldborder"))
                    .entry("dmz_ragnarok:chat", Component.literal("Chat"),
                            Component.literal("Open the chat formatting editor"), open("chat"))
                    .entry("dmz_ragnarok:protection", Component.literal("Protection"),
                            Component.literal("Open the protection / claims editor"), open("protection"))
                    .entry("dmz_ragnarok:regions", Component.literal("Regions"),
                            Component.literal("Open the region list editor"), open("regions"))
                    .entry("dmz_ragnarok:npcregions", Component.literal("NPC Regions"),
                            Component.literal("Open the NPC region list editor"), open("npcregions"))
                    .entry("dmz_ragnarok:banitem", Component.literal("Banned Items"),
                            Component.literal("Open the banned items editor"), open("banitem"))
                    .entry("dmz_ragnarok:prestige", Component.literal("Prestige"),
                            Component.literal("Open the prestige editor"), open("prestige"))
                    .entry("dmz_ragnarok:shrines", Component.literal("Shrines"),
                            Component.literal("Open the shrine list editor"), open("shrines"))
                    .entry("dmz_ragnarok:hoverbikes", Component.literal("Hoverbikes"),
                            Component.literal("Open the hoverbike speed / tuning editor"), open("hoverbikes"))
                    .entry("dmz_ragnarok:sparringsettings", Component.literal("Sparring"),
                            Component.literal("Open the sparring settings editor"), open("sparringsettings"))
                    .register();
        }
    }
}
