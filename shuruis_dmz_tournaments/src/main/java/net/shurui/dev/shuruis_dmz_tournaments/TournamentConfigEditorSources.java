package net.shurui.dev.shuruis_dmz_tournaments;

import net.shurui.dev.sdu.config.editor.ConfigEditorRegistry;
import net.shurui.dev.sdu.config.editor.ConfigTabs;
import net.shurui.dev.sdu.config.editor.EditorLink;
import net.shurui.dev.sdu.config.editor.ForgeSpecSource;

import java.util.List;

/**
 * The Tournaments module's file in the SDU options editor ({@code config/shuruis_dmz_tournaments-common.toml}) and
 * the tournament editor on its Editors tab. Values are read live through the spec; a save also re-sends the title
 * list, as a reload of the file does.
 */
final class TournamentConfigEditorSources {

    private TournamentConfigEditorSources() {
    }

    static void register() {
        ConfigEditorRegistry.addServer(new ForgeSpecSource("tournaments", "config/shuruis_dmz_tournaments-common.toml",
                () -> Config.SPEC, ConfigTabs.TOURNAMENTS,
                () -> net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet.broadcastTitles(
                        net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()))
                .choices("schedule.mode", List.of("MANUAL", "DAILY", "WEEKLY", "MONTHLY")));
        ConfigEditorRegistry.addLink(EditorLink.command("tournaments.edit",
                "gui.dmz_ragnarok.npc.options.link.tournaments", "gui.dmz_ragnarok.npc.options.link.tournaments.tip",
                "rg tourney edit"));
    }
}
