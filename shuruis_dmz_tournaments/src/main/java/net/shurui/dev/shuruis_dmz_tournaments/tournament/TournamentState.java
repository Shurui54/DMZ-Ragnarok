package net.shurui.dev.shuruis_dmz_tournaments.tournament;

public enum TournamentState {
    IDLE,          // nothing running, scheduler may open sign-ups
    SIGNUP,        // sign-ups open
    COUNTDOWN,     // fighters placed + healed, pre-match countdown ticking
    MATCH,         // bout live, watching for knockout / ring-out / timeout
    INTERMISSION,  // short pause between bouts
    FINISHED       // champion decided, rewards handed out
}
