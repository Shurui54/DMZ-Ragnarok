package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.RandomSource;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The single source of a cosmetic crate's odds: what the published table shows AND what the roll uses.
 *
 * <h2>One array, two readers, so they cannot drift</h2>
 * {@link #of(CosmeticCrate)} builds one cumulative weight array from the crate's entries. {@link #rows()} renders
 * THAT array for the odds screen, and {@link #pick(RandomSource)} WALKS the same array for the roll. There is no
 * second place in the codebase where a cosmetic-crate probability is computed, so a reviewer can prove the screen
 * and the roll agree by finding one construction site. {@code /cosmetic simulate} exists to demonstrate it
 * empirically.
 *
 * <h2>Order of a roll, fixed</h2>
 * <ol>
 *   <li>Pick the cosmetic by weight (this array).</li>
 *   <li>Roll Magic: {@link CosmeticCrate#magicPercent}, but only if the cosmetic's eligibility set contains
 *       MAGIC.</li>
 *   <li>Roll Super: {@link CosmeticCrate#superPercent}, but only if the eligibility set contains SUPER.</li>
 *   <li>If Magic, draw an effect from the crate's pool (or the cosmetic's own default pool), by weight.</li>
 * </ol>
 * Steps 2 and 3 are independent, so a Super Magic copy is possible at {@code super% * magic%}, which is the TF2
 * behaviour. The single-valued {@link CosmeticQuality} on an ownership row cannot record BOTH, so a copy that
 * rolls both is stored as MAGIC and keeps its counter menu regardless: the counter lives on the copy and the
 * effect is what a player and everyone else actually sees. Recorded here rather than silently dropped so the odds
 * screen can show all four combinations honestly.
 *
 * <h2>An eligibility miss shows an honest zero</h2>
 * A cosmetic whose set excludes MAGIC is listed on the odds table at 0% Magic rather than being renormalised
 * away, because a table that quietly does not add up the way a player expects is worse than one showing a zero.
 *
 * <p>Pure and side-effect free apart from a loud log when the weights are malformed. No statics, no IO, so the
 * merge and the roll can be exercised by a test.
 */
public final class CrateOdds
{
    /** One publishable line of the odds table: a cosmetic and the chance of each quality it can come out at. */
    public static final class Row
    {
        public final String catalogId;
        public final String displayName;

        /** The chance of drawing this cosmetic AT ALL, 0 to 1, before the quality rolls. */
        public final double drawChance;

        /** The chance THIS cosmetic comes out Normal (neither Super nor Magic), 0 to 1 of the whole crate. */
        public final double normalChance;

        /** The chance this cosmetic comes out Super but not Magic, 0 to 1 of the whole crate. */
        public final double superOnlyChance;

        /** The chance this cosmetic comes out Magic (with or without Super), 0 to 1 of the whole crate. */
        public final double magicChance;

        Row(String catalogId, String displayName, double drawChance, double normalChance, double superOnlyChance,
                double magicChance)
        {
            this.catalogId = catalogId;
            this.displayName = displayName;
            this.drawChance = drawChance;
            this.normalChance = normalChance;
            this.superOnlyChance = superOnlyChance;
            this.magicChance = magicChance;
        }
    }

    /** The outcome of one roll: which cosmetic, at which quality, with which effect from which pool. */
    public static final class Roll
    {
        public final String catalogId;
        public final CosmeticQuality quality;

        /** The rolled effect id, blank unless {@link #quality} is MAGIC. */
        public final String effectId;

        /** Which pool the effect was drawn from, HISTORY, blank when nothing rolled one. */
        public final String effectPoolId;

        /** Whether the roll also passed the Super gate, kept for a reveal line even when quality is MAGIC. */
        public final boolean alsoSuper;

        Roll(String catalogId, CosmeticQuality quality, String effectId, String effectPoolId, boolean alsoSuper)
        {
            this.catalogId = catalogId;
            this.quality = quality;
            this.effectId = effectId == null ? "" : effectId;
            this.effectPoolId = effectPoolId == null ? "" : effectPoolId;
            this.alsoSuper = alsoSuper;
        }
    }

    private final CosmeticCrate crate;
    private final List<CosmeticCrate.Entry> live = new ArrayList<>();
    private final int[] cumulative;
    private final int total;

    private CrateOdds(CosmeticCrate crate)
    {
        this.crate = crate;
        for (CosmeticCrate.Entry e : crate.entries)
            if (e != null && e.weight > 0 && !e.catalogId.isBlank())
                live.add(e);
        this.cumulative = new int[live.size()];
        int running = 0;
        for (int i = 0; i < live.size(); i++)
        {
            running += live.get(i).weight;
            cumulative[i] = running;
        }
        this.total = running;
    }

    /** Build the odds for a crate. Pure. */
    public static CrateOdds of(CosmeticCrate crate)
    {
        return new CrateOdds(crate);
    }

    public boolean rollable()
    {
        return total > 0;
    }

    public int totalWeight()
    {
        return total;
    }

    /** The crate-wide chance of Super among rolls that are eligible, 0 to 1. Published as a summary. */
    public double superChance()
    {
        return crate.superPercent / 100.0D;
    }

    public double magicChance()
    {
        return crate.magicPercent / 100.0D;
    }

    /**
     * The published table, one row per LISTED entry (including weight-zero ones, which show 0% draw so an admin
     * can see they are parked). Probabilities are of the whole crate, so a reader can add the four numbers on a
     * row to its draw chance and add every draw chance to 1.
     */
    public List<Row> rows()
    {
        List<Row> out = new ArrayList<>();
        double sMagic = crate.magicPercent / 100.0D;
        double sSuper = crate.superPercent / 100.0D;
        double sum = 0.0D;
        for (CosmeticCrate.Entry e : crate.entries)
        {
            if (e == null || e.catalogId.isBlank())
                continue;
            double draw = total <= 0 ? 0.0D : (double) e.weight / (double) total;
            CosmeticDef def = CosmeticCatalog.get(e.catalogId);
            boolean canMagic = def != null && def.allows(CosmeticQuality.MAGIC);
            boolean canSuper = def != null && def.allows(CosmeticQuality.SUPER);
            double pMagic = canMagic ? sMagic : 0.0D;
            double pSuper = canSuper ? sSuper : 0.0D;
            // Magic and Super roll independently, so "Super only" is Super passing while Magic does not.
            double magicChance = draw * pMagic;
            double superOnly = draw * pSuper * (1.0D - pMagic);
            double normal = draw * (1.0D - pMagic) * (1.0D - pSuper);
            String name = def != null && def.displayName != null && !def.displayName.isBlank() ? def.displayName
                    : e.catalogId;
            out.add(new Row(e.catalogId, name, draw, normal, superOnly, magicChance));
            sum += draw;
        }
        // A loud log, never a throw: a crate an admin is mid-editing can briefly not sum to 1, and taking the
        // odds screen or the roll down over it would be worse than a warning nobody but a developer reads.
        if (total > 0 && Math.abs(sum - 1.0D) > 1.0E-6D)
            LoggingHandler.sulog.warn(
                    "[Cosmetics] Crate '{}' draw chances sum to {} rather than 1. This is a rounding artefact only "
                            + "if the difference is tiny; otherwise an entry has a stale weight.",
                    crate.crateName, sum);
        return out;
    }

    /**
     * Roll once. Null only when the crate can produce nothing (no positive-weight entry).
     *
     * <p>Walks the SAME cumulative array {@link #rows()} renders from. The quality rolls follow, in the fixed
     * order the class note gives.
     */
    public Roll pick(RandomSource random)
    {
        if (total <= 0 || live.isEmpty())
            return null;
        int roll = random.nextInt(total);
        CosmeticCrate.Entry chosen = live.get(live.size() - 1);
        for (int i = 0; i < cumulative.length; i++)
            if (roll < cumulative[i])
            {
                chosen = live.get(i);
                break;
            }
        CosmeticDef def = CosmeticCatalog.get(chosen.catalogId);
        boolean canMagic = def != null && def.allows(CosmeticQuality.MAGIC);
        boolean canSuper = def != null && def.allows(CosmeticQuality.SUPER);
        boolean magic = canMagic && rollPercent(random, crate.magicPercent);
        boolean superRoll = canSuper && rollPercent(random, crate.superPercent);
        if (magic)
        {
            String poolId = crate.magicPoolId != null && !crate.magicPoolId.isBlank() ? crate.magicPoolId
                    : (def == null ? "" : def.defaultEffectPoolId);
            String effectId = rollEffect(random, poolId);
            // A Magic roll that could not draw an effect (no pool, empty pool, all zero weight) falls back to the
            // next best outcome rather than a Magic copy with no effect, which would render as nothing and read as
            // a broken roll. Super if it also passed, else Normal.
            if (effectId.isBlank())
                return new Roll(chosen.catalogId, superRoll ? CosmeticQuality.SUPER : CosmeticQuality.NORMAL, "", "",
                        superRoll);
            return new Roll(chosen.catalogId, CosmeticQuality.MAGIC, effectId, poolId, superRoll);
        }
        if (superRoll)
            return new Roll(chosen.catalogId, CosmeticQuality.SUPER, "", "", true);
        return new Roll(chosen.catalogId, CosmeticQuality.NORMAL, "", "", false);
    }

    private static boolean rollPercent(RandomSource random, int percent)
    {
        if (percent <= 0)
            return false;
        if (percent >= 100)
            return true;
        return random.nextInt(100) < percent;
    }

    /** Draw an effect from a pool by weight, or blank when the pool is missing, empty or all zero weight. */
    private static String rollEffect(RandomSource random, String poolId)
    {
        CosmeticEffectPool pool = CosmeticCatalog.pool(poolId);
        if (pool == null || !pool.rollable())
            return "";
        int total = pool.totalWeight();
        if (total <= 0)
            return "";
        int roll = random.nextInt(total);
        for (CosmeticEffectPool.WeightedEffect e : pool.entries)
        {
            if (e == null || e.weight <= 0 || e.effectId.isBlank())
                continue;
            roll -= e.weight;
            if (roll < 0)
                return e.effectId;
        }
        return "";
    }
}
