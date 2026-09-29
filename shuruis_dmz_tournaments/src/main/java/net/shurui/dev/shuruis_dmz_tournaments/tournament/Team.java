package net.shurui.dev.shuruis_dmz_tournaments.tournament;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// a competing unit: one or more players who advance and are rewarded together. 1v1 (SOLO/FFA) is a
// one-member team. pure data, Bracket/TournamentInstance drive the logic.
public class Team {
    public final int id;
    public String name;
    public final List<UUID> members = new ArrayList<>();

    public Team(int id, String name, List<UUID> members) {
        this.id = id;
        this.name = name;
        if (members != null) this.members.addAll(members);
    }

    // single-player team for SOLO/FFA
    public static Team solo(int id, UUID player, String displayName) {
        Team t = new Team(id, displayName, List.of(player));
        return t;
    }

    public boolean contains(UUID player) {
        return player != null && members.contains(player);
    }

    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    // first member or null, handy for solo teams
    public UUID first() {
        return members.isEmpty() ? null : members.get(0);
    }

    public String displayName() {
        return (name != null && !name.isBlank()) ? name : ("Team #" + id);
    }
}
