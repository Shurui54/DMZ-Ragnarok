package net.shurui.dev.sdu.modules;

import java.util.HashMap;
import java.util.Map;

import net.shurui.dev.sdu.api.ClientGate;

/**
 * Which module switch each hub row belongs to, so a screen stops offering what the server has switched off.
 *
 * <p>The operator per-feature switchboard was removed in batch M, so {@link ClientGate#on(String)} now answers
 * always-true. The rows that belong to a PRIVATE feature are listed separately in {@link #PRIVATE_ENTRIES} and drawn
 * only while the connected server holds the key ({@link ClientGate#key()}); without it they are not drawn at all,
 * never greyed out. The server refuses those editors keyless as well, so hiding the row is not the only gate.
 *
 * <h2>One table instead of a check in every addon</h2>
 * The rows arrive from four different source trees through the {@code SduHubExtensions} API, and the obvious fix was
 * a switch check inside each addon's {@code SduHubCompat} before it registers its section. That spreads the same
 * decision over four files, three of which would then need to reach the client's copy of the switchboard, and it
 * gets the timing wrong: sections are registered once at client setup, long before the client has been told anything
 * by any server, so a check there would answer for whatever server it last spoke to. Filtering when the hub is
 * OPENED asks at the only moment the answer is known and correct for the server actually being played on.
 *
 * <h2>Ids, not labels</h2>
 * Keyed on the section and entry ids the addons register, which are stable strings chosen for exactly this kind of
 * lookup, rather than on the display names, which are translated and will not match anything on a French client.
 * An id that is not in this table has no switch and is always shown: a row nobody has mapped must not vanish.
 */
public final class HubGate
{
    private HubGate() {}

    private static final Map<String, String> SECTION_KEYS = new HashMap<>();
    private static final Map<String, String> ENTRY_KEYS = new HashMap<>();

    /**
     * Rows of a PRIVATE feature (OWNER-SPECS section 3): Shurui's Utilities' administration, economy and social
     * editors, and rifts. Deliberately NOT here: the SDU editors, dungeons (the public spawner config), raids,
     * tournaments, and Hoverbikes (public since 2.0), which all stay visible keyless.
     */
    private static final java.util.Set<String> PRIVATE_ENTRIES = java.util.Set.of(
            "dmz_ragnarok:rifts",
            "dmz_ragnarok:permissions",
            "dmz_ragnarok:guilds",
            "dmz_ragnarok:economy",
            "dmz_ragnarok:holograms",
            "dmz_ragnarok:crates",
            "dmz_ragnarok:portals",
            "dmz_ragnarok:worldborder",
            "dmz_ragnarok:chat",
            "dmz_ragnarok:protection",
            "dmz_ragnarok:regions",
            "dmz_ragnarok:npcregions",
            "dmz_ragnarok:banitem",
            "dmz_ragnarok:prestige",
            "dmz_ragnarok:shrines",
            "dmz_ragnarok:sparringsettings");

    static
    {
        // whole dropdown sections
        SECTION_KEYS.put("npc", "Sdu");
        SECTION_KEYS.put("dungeons", "Dungeons");
        SECTION_KEYS.put("raid", "Raids");
        SECTION_KEYS.put("tournaments", "Tournaments");
        // "core" is Shurui's Utilities' own section: it has no single switch, because its rows belong to a dozen
        // different modules. Each row is mapped individually below and the section disappears when they all do.

        // SDU's own editor rows, registered by SduHubScreen itself
        ENTRY_KEYS.put("dmz_ragnarok:race", "Sdu.Races");
        ENTRY_KEYS.put("dmz_ragnarok:form", "Sdu.Forms");
        ENTRY_KEYS.put("dmz_ragnarok:saga", "Sdu.Sagas");
        ENTRY_KEYS.put("dmz_ragnarok:sidequest", "Sdu.Sagas");
        ENTRY_KEYS.put("dmz_ragnarok:wish", "Sdu.Wishes");
        ENTRY_KEYS.put("dmz_ragnarok:shrine", "Sdu.Wishes");
        // "options" is deliberately unmapped: it is the settings screen for whatever is left, so it stays.

        // the addons
        ENTRY_KEYS.put("dmz_ragnarok:dungeon_config", "Dungeons");
        ENTRY_KEYS.put("dmz_ragnarok:raids", "Raids.Bosses");
        ENTRY_KEYS.put("dmz_ragnarok:rifts", "Raids.Rifts");
        ENTRY_KEYS.put("dmz_ragnarok:tournaments", "Tournaments");

        // Shurui's Utilities' rows, each to the module that actually serves it
        ENTRY_KEYS.put("dmz_ragnarok:permissions", "Admin.Permissions");
        ENTRY_KEYS.put("dmz_ragnarok:guilds", "Social.Guilds");
        ENTRY_KEYS.put("dmz_ragnarok:economy", "Economy");
        ENTRY_KEYS.put("dmz_ragnarok:holograms", "Social.Holograms");
        ENTRY_KEYS.put("dmz_ragnarok:crates", "Economy.Crates");
        ENTRY_KEYS.put("dmz_ragnarok:worldborder", "Admin.WorldBorder");
        ENTRY_KEYS.put("dmz_ragnarok:chat", "Social.Chat");
        ENTRY_KEYS.put("dmz_ragnarok:protection", "Admin.Protection");
        ENTRY_KEYS.put("dmz_ragnarok:regions", "Admin.Regions");
        ENTRY_KEYS.put("dmz_ragnarok:npcregions", "Content.NpcRegions");
        ENTRY_KEYS.put("dmz_ragnarok:banitem", "Admin.BanItem");
        ENTRY_KEYS.put("dmz_ragnarok:prestige", "Content.Prestige");
        ENTRY_KEYS.put("dmz_ragnarok:shrines", "Content.Shrine");
        ENTRY_KEYS.put("dmz_ragnarok:hoverbikes", "Content.Hoverbikes");
        ENTRY_KEYS.put("dmz_ragnarok:sparringsettings", "Content.Sparring");
    }

    /** Should this whole dropdown section be drawn? */
    public static boolean sectionVisible(String sectionId)
    {
        String key = sectionId == null ? null : SECTION_KEYS.get(sectionId);
        return key == null || ClientGate.on(key);
    }

    /** Should this row inside a section be drawn? A private row needs the key as well. */
    public static boolean entryVisible(String entryId)
    {
        if (entryId != null && PRIVATE_ENTRIES.contains(entryId) && !ClientGate.key())
            return false;
        String key = entryId == null ? null : ENTRY_KEYS.get(entryId);
        return key == null || ClientGate.on(key);
    }
}
