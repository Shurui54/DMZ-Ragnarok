package net.shurui.dev.shuruis_raid_bosses.client;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.shurui.dev.shuruis_raid_bosses.client.gui.RaidBrowserScreen;
import net.shurui.dev.shuruis_raid_bosses.client.gui.RaidEditScreen;
import net.shurui.dev.shuruis_raid_bosses.client.gui.RaidHubScreen;
import net.shurui.dev.shuruis_raid_bosses.client.gui.RaidListScreen;
import net.shurui.dev.shuruis_raid_bosses.client.gui.RaidSignupScreen;
import net.shurui.dev.shuruis_raid_bosses.network.OpenBrowserPacket;

import java.util.List;

/**
 * Client-only packet handlers. Isolated so the dedicated server never classloads GUI types
 * (only referenced via {@code DistExecutor}).
 */
public final class ClientPacketHandler {
    private ClientPacketHandler() {}

    public static void openSignup(String defId, String name, boolean signupOpen, boolean signedUp, int count, int stateOrdinal) {
        Minecraft.getInstance().setScreen(new RaidSignupScreen(defId, name, signupOpen, signedUp, count, stateOrdinal));
    }

    public static void openHub() {
        Minecraft.getInstance().setScreen(new RaidHubScreen());
    }

    public static void openBrowser(List<OpenBrowserPacket.Entry> entries) {
        Minecraft.getInstance().setScreen(new RaidBrowserScreen(entries));
    }

    public static void openEditor(List<CompoundTag> defs) {
        Minecraft.getInstance().setScreen(new RaidListScreen(defs));
    }

    public static void openRiftEditor(List<CompoundTag> rifts, List<CompoundTag> raids, List<String> regionNames) {
        Minecraft.getInstance().setScreen(
                new net.shurui.dev.shuruis_raid_bosses.client.gui.RiftListScreen(rifts, raids, regionNames));
    }

    /** Update an open edit screen with the arena the server just captured from a WorldEdit selection. */
    public static void boundsResult(String defId, String regionKey, boolean success, CompoundTag defNbt) {
        if (!success) return;
        if (Minecraft.getInstance().screen instanceof RaidEditScreen edit) {
            edit.onBoundsUpdated(defId, defNbt);
        }
    }
}
