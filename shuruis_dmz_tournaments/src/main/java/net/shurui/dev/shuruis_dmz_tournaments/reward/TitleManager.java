package net.shurui.dev.shuruis_dmz_tournaments.reward;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.shuruis_dmz_tournaments.Config;
import net.shurui.dev.shuruis_dmz_tournaments.data.TournamentData;
import net.shurui.dev.shuruis_dmz_tournaments.util.Announcer;
import net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil;

import java.util.List;
import java.util.UUID;

/**
 * Configurable, transferable titles. Each id has one holder at a time; granting to a new player strips the previous
 * holder and announces the transfer. Holders persist in {@link TournamentData}.
 */
public final class TitleManager {
    private TitleManager() {}

    /**
     * Titles defined in CODE, not config, same {@code id|Display Name|Prefix} format. These three are roles, not
     * decorations: holding one grants an energy bar and abilities, so they must exist on every server with no config
     * line and survive a config replacement. The config half lives outside the save while the holder lives inside,
     * so a config-only definition can strand its holder. Config still WINS for these ids: an admin overrides by
     * adding a {@code titles.definitions} row with the same id.
     *
     * <p>{@code grand_zeno} carries the God of Destruction AND the Angel movesets at once. What it grants is decided
     * in SU (see {@code RoleTitles.grantsDestruction} / {@code grantsAngelic}), not here: this row only defines that
     * the id exists, what it is called, and the tag it hangs after a holder's name.
     */
    private static final String[] BUILT_IN_DEFS = {
            "god_of_destruction|&5&lGod of Destruction|&5&l [G.O.D.]",
            "angel|&b&lAngel|&b&l [Angel]",
            "grand_zeno|&d&lGrand Zeno|&d&l [Zeno]"
    };

    /** Every definition in effect: the config's rows first (so they override), then the built-in roles. */
    private static Iterable<String> allDefs() {
        java.util.List<String> defs = new java.util.ArrayList<>(Config.TITLE_DEFS.get());
        for (String builtIn : BUILT_IN_DEFS) {
            String id = builtIn.substring(0, builtIn.indexOf('|'));
            boolean overridden = false;
            for (String def : defs) {
                int sep = def.indexOf('|');
                if (sep > 0 && def.substring(0, sep).equalsIgnoreCase(id)) {
                    overridden = true;
                    break;
                }
            }
            if (!overridden) defs.add(builtIn);
        }
        return defs;
    }

    /** Definition format: {@code id|Display Name|Prefix}. Returns the requested part (1=display, 2=prefix). */
    private static String part(String titleId, int index) {
        for (String def : allDefs()) {
            String[] parts = def.split("\\|", -1);
            if (parts.length > 0 && parts[0].equalsIgnoreCase(titleId)) {
                return index < parts.length ? parts[index] : "";
            }
        }
        return index == 1 ? titleId : "";
    }

    public static String displayName(String titleId) {
        return part(titleId, 1);
    }

    /**
     * Colour-coded tag shown AFTER the holder's name (chat + above head). May be empty. Named "prefix" for the
     * config format ({@code id|Display|Tag}, existing rows keep working) but rendered as a SUFFIX; renaming would
     * break the third field's meaning for anyone who has written definitions.
     */
    public static String prefix(String titleId) {
        return part(titleId, 2);
    }

    public static boolean isDefined(String titleId) {
        for (String def : allDefs()) {
            int sep = def.indexOf('|');
            if (sep > 0 && def.substring(0, sep).equalsIgnoreCase(titleId)) return true;
        }
        return false;
    }

    /**
     * @return the title id this player holds, or null.
     *
     * <p>From the holder map, NOT by walking {@link Config#TITLE_DEFS}: the definition can go missing while the grant
     * is on record, and filtering by config reported those players as untitled (see
     * {@link TournamentData#heldTitleIds(UUID)}). A held title with no definition still resolves, and
     * {@link #displayName} falls back to the raw id.
     */
    public static String heldTitle(MinecraftServer server, UUID player) {
        if (server == null || player == null) return null;
        List<String> held = TournamentData.get(server).heldTitleIds(player);
        return held.isEmpty() ? null : held.get(0);
    }

    /** Transfer a title to the given player, removing it from any prior holder. */
    public static void grant(MinecraftServer server, String titleId, ServerPlayer newHolder) {
        if (!isDefined(titleId)) return;
        TournamentData data = TournamentData.get(server);
        UUID previous = data.getTitleHolder(titleId);
        data.setTitleHolder(titleId, newHolder.getUUID());

        String display = displayName(titleId);
        newHolder.sendSystemMessage(TextUtil.color("&aYou have been awarded the title: " + display));
        if (previous != null && !previous.equals(newHolder.getUUID())) {
            ServerPlayer old = server.getPlayerList().getPlayer(previous);
            if (old != null) {
                old.sendSystemMessage(TextUtil.color("&cYour title '" + display + "' has passed to " + newHolder.getGameProfile().getName() + "."));
                TitleDisplay.clear(old);
            }
        }
        TitleDisplay.apply(server, newHolder);
        Announcer.broadcast(server, "&e" + newHolder.getGameProfile().getName() + " &fnow holds the title &r" + display + "&f!");
    }

    /** Every title an admin can grant, config rows plus the built-in roles, for {@code title list}. */
    public static List<? extends String> definitions() {
        List<String> all = new java.util.ArrayList<>();
        allDefs().forEach(all::add);
        return all;
    }

    /**
     * Strip every defined title from the given player. Safe for any UUID (online or offline).
     * Called cross-mod (via reflection) from Shurui's Utilities' {@code /rgreset} command.
     *
     * @return the number of titles removed.
     */
    public static int clearAll(MinecraftServer server, UUID player) {
        if (server == null || player == null) return 0;
        TournamentData data = TournamentData.get(server);
        int removed = 0;
        // Holder map, not config: a reset must clear titles whose definition has gone missing too, or they linger
        // invisibly and reappear when the config is restored. Each clear goes through setTitleHolder(id, null), the
        // same choke point as the admin clear, so each leaves a dated tombstone and travels the shard network, and a
        // reset run while the player is on another shard strips them there rather than being merged back.
        for (String id : data.heldTitleIds(player)) {
            data.setTitleHolder(id, null);
            removed++;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) TitleDisplay.clear(online);
        return removed;
    }
}
