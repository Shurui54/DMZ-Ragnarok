package net.shurui.dev.shuruis_dmz_tournaments.tournament;

import java.util.UUID;

// a bout between two teams. b null means a gets a bye.
public class Match {
    public final Team a;
    public final Team b;
    public Team winner;
    public Team loser;

    public Match(Team a, Team b) {
        this.a = a;
        this.b = b;
    }

    public boolean isBye() {
        return b == null;
    }

    public Team opponentOf(Team team) {
        if (team == a) return b;
        if (team == b) return a;
        return null;
    }

    public boolean involves(UUID player) {
        return (a != null && a.contains(player)) || (b != null && b.contains(player));
    }

    public Team teamOf(UUID player) {
        if (a != null && a.contains(player)) return a;
        if (b != null && b.contains(player)) return b;
        return null;
    }

    public void resolve(Team winner) {
        this.winner = winner;
        this.loser = (winner == a) ? b : a;
    }
}
