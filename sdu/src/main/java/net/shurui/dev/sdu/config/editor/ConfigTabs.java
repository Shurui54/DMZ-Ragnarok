package net.shurui.dev.sdu.config.editor;

import java.util.List;

/**
 * The tabs of the options editor, in display order. A tab with nothing in it for the viewer is not shown. Labels are
 * {@code gui.dmz_ragnarok.npc.options.tab.<id>}.
 */
public final class ConfigTabs {

    private ConfigTabs() {
    }

    public static final String GENERAL = "general";
    public static final String COMBAT = "combat";
    public static final String TRAINING = "training";
    public static final String RITUALS = "rituals";
    public static final String DRAGONBALLS = "dragonballs";
    public static final String NPCS = "npcs";
    public static final String DUNGEONS = "dungeons";
    public static final String RAIDS = "raids";
    public static final String TOURNAMENTS = "tournaments";
    public static final String SPACE = "space";
    public static final String EVENTS = "events";
    public static final String ECONOMY = "economy";
    public static final String GUILDS = "guilds";
    public static final String PROTECTION = "protection";
    public static final String CHAT = "chat";
    public static final String MAINTENANCE = "maintenance";
    /** Per-player options kept on the game client, edited without the server. */
    public static final String CLIENT = "client";
    /** The read-only directory of the other editors. */
    public static final String EDITORS = "editors";

    /** Server tabs in order (the client adds {@link #CLIENT} and {@link #EDITORS} itself). */
    public static final List<String> SERVER_ORDER = List.of(GENERAL, COMBAT, TRAINING, RITUALS, DRAGONBALLS, NPCS,
            DUNGEONS, RAIDS, TOURNAMENTS, SPACE, EVENTS, ECONOMY, GUILDS, PROTECTION, CHAT, MAINTENANCE);

    public static String langKey(String tab) {
        return "gui.dmz_ragnarok.npc.options.tab." + tab;
    }

    public static boolean known(String tab) {
        return SERVER_ORDER.contains(tab) || CLIENT.equals(tab) || EDITORS.equals(tab);
    }
}
