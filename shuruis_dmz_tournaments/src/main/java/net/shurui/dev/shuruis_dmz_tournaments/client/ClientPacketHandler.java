package net.shurui.dev.shuruis_dmz_tournaments.client;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.shurui.dev.shuruis_dmz_tournaments.client.gui.BrowserScreen;
import net.shurui.dev.shuruis_dmz_tournaments.client.gui.SignupScreen;
import net.shurui.dev.shuruis_dmz_tournaments.client.gui.TournamentListScreen;
import net.shurui.dev.shuruis_dmz_tournaments.network.OpenBrowserPacket;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentInstance;

import java.util.List;

/**
 * Client-only packet handlers. Isolated so the dedicated server never classloads GUI types
 * (only referenced via {@code DistExecutor}).
 */
public final class ClientPacketHandler {
    private ClientPacketHandler() {}

    public static void openSignup(String defId, String name, boolean signupOpen, boolean signedUp, int count,
                                  int stateOrdinal, int formatOrdinal, int teamSize, List<TournamentInstance.TeamView> teams) {
        Minecraft.getInstance().setScreen(new SignupScreen(defId, name, signupOpen, signedUp, count, stateOrdinal,
                formatOrdinal, teamSize, teams));
    }

    public static void openBrowser(List<OpenBrowserPacket.Entry> entries) {
        Minecraft.getInstance().setScreen(new BrowserScreen(entries));
    }

    public static void openLoadout(List<String> kiIds, List<String> strikeIds, List<String> selected,
                                   int kiMax, int strikeMax, boolean creation) {
        Minecraft.getInstance().setScreen(new net.shurui.dev.shuruis_dmz_tournaments.client.gui.LoadoutScreen(
                kiIds, strikeIds, selected, kiMax, strikeMax, creation));
    }

    public static void openEditor(List<CompoundTag> defs) {
        Minecraft.getInstance().setScreen(new TournamentListScreen(defs));
    }

    /** Update an open edit screen with the region the server just captured from a WorldEdit selection. */
    public static void boundsResult(String defId, String regionKey, boolean success, CompoundTag defNbt) {
        if (!success) return;
        if (Minecraft.getInstance().screen instanceof net.shurui.dev.shuruis_dmz_tournaments.client.gui.TournamentEditScreen edit) {
            edit.onBoundsUpdated(defId, defNbt);
        }
    }
}

