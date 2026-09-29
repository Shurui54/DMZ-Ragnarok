package net.shurui.dev.shuruis_dmz_dungeons.compat.sdu;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;

// guarded bridge that surfaces the dungeon config GUI inside the shared "/rg npc edit" hub. That hub and its extension
// API (net.shurui.dev.sdu.api.SduHubExtensions) are owned by SDU, so the guard is on "sdu".
//
// the outer class names NO sdu type, so it is classload-safe when SDU is absent. It probes the exact API class with
// Class.forName + catch Throwable, so a drifted SDU degrades to a no-op instead of crashing client setup. The single
// sdu-referencing class (Api) is only reached after the probe succeeds.
public final class SduHubCompat {

    private SduHubCompat() {
    }

    // register the hub section during client setup. no-op unless SDU (which owns the hub) is present and its hub API
    // resolves. safe to call unconditionally from FMLClientSetupEvent.
    @OnlyIn(Dist.CLIENT)
    public static void register() {
        try {
            // probe the specific API type; if it is absent (API drift) we degrade silently.
            Class.forName("net.shurui.dev.sdu.api.SduHubExtensions");
            Api.register();
        } catch (Throwable ignored) {
            // SDU absent or its hub API changed: no hub entry. /rg dungeon edit still works on its own.
        }
    }

    // the only class that names sdu types; never classloaded unless the probe above succeeded.
    private static final class Api {
        static void register() {
            net.shurui.dev.sdu.api.SduHubExtensions
                    .section("dungeons",
                            Component.translatable("gui.dmz_ragnarok.dungeons.hub.section"))
                    .entry("dmz_ragnarok:dungeon_config",
                            Component.translatable("gui.dmz_ragnarok.dungeons.hub.dungeon_config"),
                            Component.translatable("gui.dmz_ragnarok.dungeons.hub.dungeon_config.tooltip"),
                            Api::openConfig)
                    .register();
        }

        // run client-side when the hub row is chosen. Reuses the "/rg dungeon edit" path so the server gathers the
        // rules + floor list and pushes them back, rather than opening an empty screen. Re-checks perm level 2 server-side.
        @OnlyIn(Dist.CLIENT)
        static void openConfig() {
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.sendCommand("rg dungeon edit");
            } else {
                Shuruis_dmz_dungeons.LOGGER.debug("[{}] hub: no connection to open the dungeon config GUI.",
                        Shuruis_dmz_dungeons.MODID);
            }
        }
    }
}
