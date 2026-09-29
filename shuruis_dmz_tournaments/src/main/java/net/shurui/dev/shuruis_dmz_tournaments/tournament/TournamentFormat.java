package net.shurui.dev.shuruis_dmz_tournaments.tournament;

// tournament format. everything is modelled as a contest between Teams (1v1 = one-member team).
// SOLO/DUOS/TRIOS run a bracket; FFA (Tournament of Power) is one simultaneous free-for-all.
public enum TournamentFormat {
    SOLO("1v1", 1),
    DUOS("2v2", 2),
    TRIOS("3v3", 3),
    FFA("FFA", 1);

    private final String label;
    private final int teamSize;

    TournamentFormat(String label, int teamSize) {
        this.label = label;
        this.teamSize = teamSize;
    }

    // members per team (1 for SOLO/FFA)
    public int teamSize() {
        return teamSize;
    }

    // the grouped bracket formats
    public boolean isTeam() {
        return this == DUOS || this == TRIOS;
    }

    public boolean isFfa() {
        return this == FFA;
    }

    public String label() {
        return label;
    }

    // parse enum name or short label, defaults to SOLO
    public static TournamentFormat fromId(String s) {
        if (s == null || s.isBlank()) return SOLO;
        String t = s.trim();
        for (TournamentFormat f : values()) {
            if (f.name().equalsIgnoreCase(t) || f.label.equalsIgnoreCase(t)) return f;
        }
        return SOLO;
    }
}
