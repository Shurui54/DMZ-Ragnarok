package net.shurui.dev.shuruis_dmz_tournaments.reward;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.shurui.dev.shuruis_dmz_tournaments.Config;
import net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil;

/**
 * Renders a holder's colour-coded tag AFTER their name (above head and in chat) via a scoreboard team suffix.
 * Controlled by {@code titles.nametagPrefix}; the chat-only path (nametag off) lives in the chat event in
 * {@code ForgeEventHandler}.
 */
public final class TitleDisplay {
    private static final String TEAM_PREFIX = "sdt_";

    private TitleDisplay() {}

    /** Apply the correct team (or none) for whatever title this player currently holds. */
    public static void apply(MinecraftServer server, ServerPlayer player) {
        clear(player);
        if (!Config.TITLE_NAMETAG_PREFIX.get()) return;
        String titleId = TitleManager.heldTitle(server, player.getUUID());
        if (titleId == null) return;
        String prefix = TitleManager.prefix(titleId);
        if (prefix == null || prefix.isEmpty()) return;

        Scoreboard sb = server.getScoreboard();
        String teamName = teamName(titleId);
        PlayerTeam team = sb.getPlayerTeam(teamName);
        if (team == null) team = sb.addPlayerTeam(teamName);
        // We own the sdt_ teams, so set BOTH affixes authoritatively. Titles used to be a PREFIX; a team from that
        // build keeps its prefix in scoreboard.dat, and setting only the suffix left BOTH on, showing the tag on
        // each side of the name and pushing it off-centre. Clearing the prefix heals any such team on the next apply.
        team.setPlayerPrefix(Component.empty());
        // SUFFIX, not prefix: the tag trails the name above the head and in chat.
        team.setPlayerSuffix(TextUtil.color(prefix));
        sb.addPlayerToTeam(player.getScoreboardName(), team);
    }

    /** Remove the player from any of our title teams. */
    public static void clear(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Scoreboard sb = server.getScoreboard();
        PlayerTeam current = sb.getPlayersTeam(player.getScoreboardName());
        if (current != null && current.getName().startsWith(TEAM_PREFIX)) {
            sb.removePlayerFromTeam(player.getScoreboardName(), current);
        }
    }

    private static String teamName(String titleId) {
        String name = TEAM_PREFIX + titleId;
        return name.length() <= 16 ? name : name.substring(0, 16);
    }
}
