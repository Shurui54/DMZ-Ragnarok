package net.shurui.shuruisutilities.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The note chart behind a melee clash, as maths both sides can run.
 *
 * <p>Like {@link DashPath}, this is a plain function library with no entities and no side in it, because the client
 * has to draw exactly the chart the server scored against. Both sides build it from the same seed, so the notes are
 * never sent over the wire and the two can never disagree about what was played.
 *
 * <h2>Both fighters get the SAME chart</h2>
 * One seed per clash, not one per player. A rhythm duel where each side plays different notes is not a contest, it is
 * two solo games sharing a timer, and the loser can always claim they drew the harder hand.
 *
 * <h2>What is in a chart</h2>
 * Ten seconds of four lane arrows, with the spacing randomised rather than metronomic so a chart has to be read
 * instead of memorised. Three kinds of note:
 * <ul>
 *   <li>a TAP, pressed as it crosses the line;</li>
 *   <li>a DOUBLE, two arrows landing on the same tick in different lanes, pressed together;</li>
 *   <li>a SUSTAIN, pressed and then HELD, worth more the longer it runs and forfeiting its bonus if dropped early.</li>
 * </ul>
 * All four lanes are guaranteed to appear, so a chart never quietly turns into a three key game.
 *
 * <h2>Difficulty</h2>
 * Arrows travel fast, but the hit windows stay generous: a late press still scores something, so a fumbled note costs
 * the lead rather than the fight. A perfect is worth double a good, which is what makes precision rather than mashing
 * decide a close clash.
 */
public final class ClashRhythm
{
    private ClashRhythm() {}

    /** How long a clash lasts. Ten seconds. */
    public static final int DURATION_TICKS = 200;

    /**
     * Ticks an arrow is visible before the tick it is meant to be pressed on.
     *
     * <p>This is the SPEED dial: the travel distance on screen is fixed, so fewer lead ticks means the arrow crosses
     * it faster. Short enough to feel quick, long enough that an arrow is still readable when it appears.
     */
    public static final int LEAD_TICKS = 14;

    /** The first note lands late enough that nobody is punished for still reading the screen. */
    private static final int FIRST_NOTE_TICK = 22;

    /** Randomised spacing between notes, rather than a fixed metronome. */
    private static final int MIN_GAP_TICKS = 8;
    private static final int MAX_GAP_TICKS = 15;

    /** Extra spacing after a sustain, so its tail never collides with the next arrow. */
    private static final int POST_SUSTAIN_GAP = 6;

    /** Last tick a note may land on, leaving room for its window to close before the clash ends. */
    private static final int LAST_NOTE_TICK = DURATION_TICKS - 16;

    /** Chance a note is a double, and the chance it is a sustain. Doubles are never also sustains. */
    private static final double DOUBLE_CHANCE = 0.18;
    private static final double SUSTAIN_CHANCE = 0.22;

    private static final int MIN_SUSTAIN_TICKS = 12;
    private static final int MAX_SUSTAIN_TICKS = 26;

    /** Press within this many ticks of the note for a PERFECT. */
    public static final int PERFECT_WINDOW = 3;

    /** Press within this many ticks for a GOOD. Beyond it the note is missed. */
    public static final int GOOD_WINDOW = 7;

    public static final int PERFECT_SCORE = 2;
    public static final int GOOD_SCORE = 1;

    /** A held sustain is worth one extra point per this many ticks it runs. */
    public static final int SUSTAIN_TICKS_PER_POINT = 6;

    /** How late a sustain may be released and still pay out, so a frame of slack does not cost the hold. */
    public static final int SUSTAIN_RELEASE_GRACE = 3;

    /** Up, left, down, right. Indices are the wire format and the lane order, so do not reorder them. */
    public static final int DIR_UP = 0;
    public static final int DIR_LEFT = 1;
    public static final int DIR_DOWN = 2;
    public static final int DIR_RIGHT = 3;

    /**
     * One arrow.
     *
     * @param hitTick   the tick it should be pressed on
     * @param direction the lane, one of the DIR constants
     * @param holdTicks 0 for a tap, otherwise how long it must be held after the press
     */
    public record Note(int hitTick, int direction, int holdTicks)
    {
        public boolean isSustain()
        {
            return holdTicks > 0;
        }

        /** The tick a sustain must be held until. Meaningless for a tap. */
        public int releaseTick()
        {
            return hitTick + holdTicks;
        }

        /** Points the hold itself is worth, on top of the press that started it. */
        public int sustainBonus()
        {
            return holdTicks / SUSTAIN_TICKS_PER_POINT;
        }
    }

    /**
     * The full chart for a clash, in ascending tick order. Deterministic in {@code seed} alone.
     */
    public static List<Note> timeline(long seed)
    {
        Random random = new Random(seed);
        List<Note> notes = new ArrayList<>();
        boolean[] laneUsed = new boolean[4];
        int previous = -1;
        int tick = FIRST_NOTE_TICK;

        while (tick <= LAST_NOTE_TICK)
        {
            // Never the same lane twice running: a repeat reads as a missed note rather than a new one, and it lets a
            // player hold a key through both, which is not rhythm.
            int direction = random.nextInt(4);
            if (direction == previous)
            {
                direction = (direction + 1 + random.nextInt(3)) % 4;
            }
            previous = direction;
            laneUsed[direction] = true;

            double roll = random.nextDouble();
            int gap;
            if (roll < DOUBLE_CHANCE)
            {
                // A double: a second arrow on the same tick, in a different lane. Both are taps, because a held
                // double asks for two keys down at once for a second and reads as a mistake rather than a flourish.
                int second = (direction + 1 + random.nextInt(3)) % 4;
                laneUsed[second] = true;
                notes.add(new Note(tick, direction, 0));
                notes.add(new Note(tick, second, 0));
                // The pair counts as its own beat, so leave a little more room after it.
                gap = MIN_GAP_TICKS + 2 + random.nextInt(MAX_GAP_TICKS - MIN_GAP_TICKS);
                previous = second;
            }
            else if (roll < DOUBLE_CHANCE + SUSTAIN_CHANCE)
            {
                int hold = MIN_SUSTAIN_TICKS + random.nextInt(MAX_SUSTAIN_TICKS - MIN_SUSTAIN_TICKS + 1);
                // Do not let a tail run past the end of the chart.
                hold = Math.min(hold, Math.max(MIN_SUSTAIN_TICKS, DURATION_TICKS - 6 - tick));
                notes.add(new Note(tick, direction, hold));
                gap = hold + POST_SUSTAIN_GAP + random.nextInt(MAX_GAP_TICKS - MIN_GAP_TICKS);
            }
            else
            {
                notes.add(new Note(tick, direction, 0));
                gap = MIN_GAP_TICKS + random.nextInt(MAX_GAP_TICKS - MIN_GAP_TICKS + 1);
            }
            tick += gap;
        }

        // Guarantee every lane appears. With randomised lanes a short chart can miss one, and a clash where a whole
        // direction never shows up teaches the wrong thing about which keys matter.
        for (int dir = 0; dir < 4; dir++)
        {
            if (!laneUsed[dir] && !notes.isEmpty())
            {
                int index = random.nextInt(notes.size());
                Note old = notes.get(index);
                notes.set(index, new Note(old.hitTick(), dir, old.holdTicks()));
            }
        }

        notes.sort((x, y) -> Integer.compare(x.hitTick(), y.hitTick()));
        return notes;
    }

    /** Highest score obtainable on this chart, used to sanity-cap a reported score. */
    public static int maxScore(long seed)
    {
        int total = 0;
        for (Note note : timeline(seed))
        {
            total += PERFECT_SCORE + note.sustainBonus();
        }
        return total;
    }

    /**
     * What a press on {@code direction} at {@code tick} is worth against {@code note}, or 0 if it does not count.
     * This is the PRESS only; a sustain's hold is scored separately as it is released.
     */
    public static int judge(Note note, int direction, int tick)
    {
        if (note == null || note.direction() != direction)
        {
            return 0;
        }
        int off = Math.abs(tick - note.hitTick());
        if (off <= PERFECT_WINDOW)
        {
            return PERFECT_SCORE;
        }
        return off <= GOOD_WINDOW ? GOOD_SCORE : 0;
    }

    /**
     * An NPC's score on this chart, from its power relative to the player it is fighting.
     *
     * <p>An NPC cannot press keys, so its side is simulated: decide how accurate it is, then let the chart decide the
     * number. Accuracy runs from a floor, so a weak NPC still lands something and a clash is never a free win, up to
     * near perfect for one that outclasses the player, with an even fight around three quarters.
     *
     * <p>Seeded from the clash seed so a replay resolves the same way, and jittered so an evenly matched NPC is not a
     * fixed number that can be memorised.
     */
    public static int npcScore(long seed, double npcPower, double playerPower)
    {
        List<Note> notes = timeline(seed);
        double ratio = playerPower <= 0.0 ? 1.0 : npcPower / playerPower;
        double accuracy = Math.max(0.45, Math.min(0.95, 0.75 * Math.sqrt(Math.max(0.05, ratio))));
        Random random = new Random(seed * 31L + 17L);
        int score = 0;
        for (Note note : notes)
        {
            double roll = random.nextDouble();
            if (roll < accuracy * 0.6)
            {
                score += PERFECT_SCORE;
                // A confident NPC holds its sustains too.
                score += note.sustainBonus();
            }
            else if (roll < accuracy)
            {
                score += GOOD_SCORE;
                // A shakier hit drops the tail about half the time.
                if (random.nextBoolean())
                {
                    score += note.sustainBonus();
                }
            }
        }
        return score;
    }
}
