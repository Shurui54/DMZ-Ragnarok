package net.shurui.dev.sdu.util;

/**
 * Overflow-safe arithmetic for DMZ training-point (TP) gains.
 *
 * <p>DMZ's {@code DMZEvent.TPGainEvent} carries the gain as a plain {@code int} and the final write in
 * {@code Resources.addTrainingPoints} is {@code setTrainingPoints(oldValue + (float) event.getTpGain())}.
 * Live saga rewards reach 2,000,000,000 TP (the_buu_saga b49/b50/b51), so any suite listener that scales the
 * gain with {@code (int) Math.round(gain * factor)} wraps past {@link Integer#MAX_VALUE} into a NEGATIVE int.
 * That negative gain then drives the player's total DOWN (DMZ clamps the sum at 0), which is exactly how a
 * claimed reward can pay nothing. DMZ's own {@code calculateTPGain}/{@code applyTpBoosts} are safe only because
 * they cast a {@code double} to {@code int} (a saturating narrowing); {@code Math.round} returns a {@code long},
 * and a {@code long}-to-{@code int} cast TRUNCATES, so it must be clamped explicitly.
 *
 * <p>This helper lives in sdu on purpose: sdu imports nothing from shuruisutilities, and the other four trees
 * may read from sdu, so both sides of the suite can share one clamp without inverting that invariant.
 *
 * <h2>Wide (double) accumulator for quest rewards</h2>
 *
 * <p>The int carriers above cap a SINGLE gain at {@link Integer#MAX_VALUE} (which the float write then rounds up
 * to 2^31 = 2,147,483,648, because Integer.MAX_VALUE has no exact float). That is fine for kills, mining and
 * travel (a handful of TP), but a story-quest reward of 2e9 TP times any real multiplier (DMZ's own STORY boost
 * plus the shrine, /tpboost, prestige and TP gems) is meant to reach tens of billions. DMZ's per-gain plumbing
 * is int, but DMZ STORES training points as a {@code float} (Resources.trainingPoints, ceiling Float.MAX_VALUE),
 * so a total in the billions is legal and already common; only the per-gain event is too narrow.
 *
 * <p>So for the quest-reward path (and only that path) we run a parallel {@code double} accumulator: it is armed
 * with the true (uncapped) {@code base * difficultyMultiplier}, multiplied in double space by every stage that
 * would otherwise multiply the int event (DMZ's {@code calculateTPGain} and each suite listener, all of which
 * route through {@link #scaleGain(int, double)} or the {@code StatsData}/{@code Resources} mixins), and written
 * to {@code trainingPoints} as one float at the end. The int event still carries the clamped mirror so any
 * foreign listener and the client sync see a sensible value. Every other TP source leaves the accumulator
 * inactive and behaves exactly as before. State is per thread; DMZ's TP writes are all on the server thread.
 */
public final class TpMath {

    // Armed but not yet consumed: the true base gain (base * difficultyMultiplier) for the NEXT quest-reward
    // addTrainingPoints call on this thread. NaN means nothing armed.
    private static final ThreadLocal<double[]> ARMED = ThreadLocal.withInitial(() -> new double[]{Double.NaN});
    // Active accumulator for the quest-reward addTrainingPoints frame currently running. NaN means inactive,
    // so every non-quest TP gain keeps the plain int behaviour untouched.
    private static final ThreadLocal<double[]> WIDE = ThreadLocal.withInitial(() -> new double[]{Double.NaN});

    private TpMath() {
    }

    /**
     * Clamp a {@code double} TP value to the {@code int} range that {@code TPGainEvent} can hold, rounding to the
     * nearest whole point. NaN becomes 0. Negative results are preserved (some paths legitimately remove TP);
     * only the positive overflow past {@link Integer#MAX_VALUE} is capped.
     */
    public static int clampToInt(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        double rounded = Math.round(value);
        if (rounded >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (rounded <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) rounded;
    }

    /**
     * Scale an existing TP gain by {@code factor}, clamped to the {@code int} range. Use this instead of
     * {@code (int) Math.round(gain * factor)} anywhere a suite listener multiplies {@code event.getTpGain()}.
     *
     * <p>When the wide quest-reward accumulator is active, the {@code int gain} passed in is only the clamped
     * mirror; the real value lives in the accumulator, so we multiply THAT (in double) and return its clamped
     * int mirror. That is what lets a single quest reward exceed {@link Integer#MAX_VALUE} once written as a
     * float. When the accumulator is inactive (every other TP source) this is the plain int behaviour.
     */
    public static int scaleGain(int gain, double factor) {
        if (isWideActive()) {
            return clampToInt(multiplyWide(factor));
        }
        return clampToInt((double) gain * factor);
    }

    // --- wide accumulator, quest-reward path only -----------------------------------------------------------

    /** Arm the next quest-reward addTrainingPoints frame with the true base gain (base * difficultyMultiplier). */
    public static void armWide(double baseGain) {
        ARMED.get()[0] = baseGain;
    }

    /** True while an armed base gain is waiting to be consumed by an addTrainingPoints frame. */
    public static boolean isArmed() {
        return !Double.isNaN(ARMED.get()[0]);
    }

    /** Take the armed base gain and clear it, so a nested or unrelated gain cannot reuse it. */
    public static double consumeArmed() {
        double[] cell = ARMED.get();
        double v = cell[0];
        cell[0] = Double.NaN;
        return v;
    }

    /** Clear any armed base gain that was never consumed (belt and braces after the reward call returns). */
    public static void disarmWide() {
        ARMED.get()[0] = Double.NaN;
    }

    /** Begin the wide accumulator for the running addTrainingPoints frame, seeded with the true base gain. */
    public static void beginWide(double base) {
        WIDE.get()[0] = base;
    }

    /** True while a quest-reward addTrainingPoints frame is running (its multipliers must feed the accumulator). */
    public static boolean isWideActive() {
        return !Double.isNaN(WIDE.get()[0]);
    }

    /** Multiply the accumulator by a stage's factor (double space) and return the new running value. */
    public static double multiplyWide(double factor) {
        double[] cell = WIDE.get();
        if (!Double.isNaN(cell[0])) {
            cell[0] *= factor;
        }
        return cell[0];
    }

    /** The accumulator's current value (the true, uncapped multiplied gain). */
    public static double currentWide() {
        return WIDE.get()[0];
    }

    /** End the wide accumulator frame. */
    public static void endWide() {
        WIDE.get()[0] = Double.NaN;
    }
}
