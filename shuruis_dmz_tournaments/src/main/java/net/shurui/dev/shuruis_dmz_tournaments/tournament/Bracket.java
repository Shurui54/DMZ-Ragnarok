package net.shurui.dev.shuruis_dmz_tournaments.tournament;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

// single-elim bracket run one match at a time through one arena. teams pair up (odd one out gets a bye),
// winners advance until one champion. final loser is the runner-up. 1v1 is a one-member team so SOLO/DUOS/
// TRIOS all use this. FFA doesn't.
public class Bracket {
    private final List<Team> currentRound;
    private final List<Team> nextRoundWinners = new ArrayList<>();
    private final Deque<Match> pending = new ArrayDeque<>();
    private int roundNumber = 1;

    private Team champion;
    private Team runnerUp;

    public Bracket(List<Team> contestants, boolean shuffle) {
        this.currentRound = new ArrayList<>(contestants);
        if (shuffle) Collections.shuffle(this.currentRound);
        buildRoundMatches();
    }

    private void buildRoundMatches() {
        pending.clear();
        List<Team> teams = currentRound;
        for (int i = 0; i < teams.size(); i += 2) {
            Team a = teams.get(i);
            Team b = (i + 1 < teams.size()) ? teams.get(i + 1) : null;
            pending.add(new Match(a, b));
        }
    }

    // next match to play, auto-advancing byes; null when the tournament is over
    public Match nextMatch() {
        while (true) {
            if (pending.isEmpty()) {
                // round done, advance winners
                if (nextRoundWinners.size() <= 1) {
                    if (nextRoundWinners.size() == 1) champion = nextRoundWinners.get(0);
                    return null; // done
                }
                currentRound.clear();
                currentRound.addAll(nextRoundWinners);
                nextRoundWinners.clear();
                roundNumber++;
                buildRoundMatches();
            }
            Match match = pending.peek();
            if (match.isBye()) {
                pending.poll();
                nextRoundWinners.add(match.a);
                continue;
            }
            return match;
        }
    }

    public void completeMatch(Match match, Team winner) {
        match.resolve(winner);
        pending.remove(match);
        nextRoundWinners.add(winner);
        // last real match of the final round: grab the runner-up
        if (pending.isEmpty() && currentRound.size() == 2) {
            runnerUp = match.loser;
        }
    }

    public int roundNumber() {
        return roundNumber;
    }

    public int remainingInRound() {
        return pending.size();
    }

    public Team champion() {
        return champion;
    }

    public Team runnerUp() {
        return runnerUp;
    }
}
