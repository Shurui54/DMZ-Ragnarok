package net.shurui.shuruisutilities.runes;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * The rune state carried by one armour piece: its tier, its per-stat affinity, and the runes socketed into it.
 *
 * <p>Stored under a single NBT compound so the whole system is additive: any armour item from any mod gains this the
 * first time it is rolled, and stripping the tag returns the piece to stock. Nothing here touches attributes; this
 * is the record, and applying it is a separate concern.
 *
 * <p>Affinity is held in ARROWS, not percentages: an integer from -{@link #MAX_ARROWS} to +{@link #MAX_ARROWS} per
 * stat, which is exactly what the tooltip draws. The multiplier is derived at the point of use
 * ({@link #ARROW_STEP} each), so the display and the effect can never disagree about what a row means.
 */
public final class ArmorRunes
{
    private ArmorRunes() {}

    /** Root tag on the stack. */
    public static final String TAG = "SUArmorRunes";

    private static final String TAG_TIER = "tier";
    private static final String TAG_AFFINITY = "aff";
    private static final String TAG_SOCKETS = "slots";

    /** Arrows a row can show in either direction. Matches the tooltip art. */
    public static final int MAX_ARROWS = 5;

    /** What one arrow is worth as a stat multiplier, positive or negative. */
    public static final double ARROW_STEP = 0.025;

    /** Rune slots on every piece. */
    public static final int SOCKETS = 2;

    /**
     * How far a piece's total upside may exceed its total downside, in arrows. Five arrows is 12.5% at
     * {@link #ARROW_STEP} each.
     *
     * <p>Everything that shapes a piece is held to this: the first roll and every socketed rune. Without it a lucky
     * piece could be +12.5% with a -2.5% price on it, which is not a trade, and the negative arrows the art makes so
     * much of would be decoration.
     *
     * <p>Raised from 2 as the direct consequence of easing the downside, not as a separate buff. The arithmetic
     * leaves no choice: gains are unchanged and the price is lower, so the gap between them is wider by
     * definition. Holding the ceiling at 2 would not have preserved the balance, it would have made the refund
     * claw the UP arrows back instead, which is the one thing that was not supposed to move.
     *
     * <p>Note what five does NOT mean. Two Mythical runes are worth two net arrows each, four between them, so the
     * ceiling sits one arrow beyond anything sockets alone can reach: the last arrow has to come from the roll. A
     * piece that is both well rolled AND well socketed is the only piece that gets there, which is the intent.
     */
    public static final int MAX_IMBALANCE_ARROWS = 5;

    /**
     * Ceiling on the FIRST roll, at half the row maximum.
     *
     * <p>The point is headroom. A piece that arrives already at five arrows has nowhere left to go, so a rune
     * socketed into it can only be absorbed by the clamp and the player sees nothing happen. Starting at half
     * leaves every piece room for runes to actually improve it, which is what runes are for.
     */
    public static final int INITIAL_MAX_ARROWS = MAX_ARROWS / 2;

    /**
     * How far below its tier's ceiling a socketed rune's gain may land, exclusive: at 4, a Mythical rune gives 5, 4,
     * 3 or 2 arrows.
     *
     * <p>This is the dial that decides how certain the top grade is. Tighten it and greater runes become a formality
     * rather than a roll; widen it and no grade feels different from the one below.
     */
    public static final int GAIN_SPREAD = 4;

    /**
     * What share of a socketed rune's gain the piece actually pays for, rounded up.
     *
     * <p>The bill used to be the whole gain, one arrow down for every arrow up, so a rune was a sideways move
     * dressed as an upgrade and the only thing separating a Mythical from a Lesser was which stat it moved. A rune
     * should be worth socketing.
     *
     * <p>At half price a five-arrow rune costs three, a four costs two, a two costs one, and a one-arrow rune
     * still costs one, so nothing is ever free and the downside arrows stay real. Rounded UP for exactly that
     * reason: rounding down would make small runes cost nothing at all. Half rather than 0.6 because at 0.6 a
     * two-arrow rune still came out net zero, which is the sideways move this was meant to stop.
     */
    public static final double DOWNSIDE_COST_RATIO = 0.5D;

    /** True when this stack has already been rolled. */
    public static boolean has(ItemStack stack)
    {
        return stack != null && !stack.isEmpty() && stack.hasTag() && stack.getTag().contains(TAG);
    }

    private static CompoundTag root(ItemStack stack, boolean create)
    {
        if (stack == null || stack.isEmpty())
            return null;
        if (create)
            return stack.getOrCreateTagElement(TAG);
        CompoundTag t = stack.getTag();
        return t != null && t.contains(TAG) ? t.getCompound(TAG) : null;
    }

    public static RuneTier tier(ItemStack stack)
    {
        CompoundTag r = root(stack, false);
        return r == null ? RuneTier.LOW : RuneTier.byName(r.getString(TAG_TIER));
    }

    public static void setTier(ItemStack stack, RuneTier tier)
    {
        CompoundTag r = root(stack, true);
        if (r != null)
            r.putString(TAG_TIER, tier.name());
    }

    /** Arrow count for one stat, clamped to the legal range. Zero when the piece has no rune data at all. */
    public static int arrows(ItemStack stack, RuneStat stat)
    {
        CompoundTag r = root(stack, false);
        if (r == null || stat == null)
            return 0;
        CompoundTag aff = r.getCompound(TAG_AFFINITY);
        return clampArrows(aff.getInt(stat.name()));
    }

    public static void setArrows(ItemStack stack, RuneStat stat, int value)
    {
        CompoundTag r = root(stack, true);
        if (r == null || stat == null)
            return;
        CompoundTag aff = r.getCompound(TAG_AFFINITY);
        aff.putInt(stat.name(), clampArrows(value));
        r.put(TAG_AFFINITY, aff);
    }

    /** Every stat's arrows, in tooltip order. */
    public static Map<RuneStat, Integer> affinity(ItemStack stack)
    {
        Map<RuneStat, Integer> out = new EnumMap<>(RuneStat.class);
        for (RuneStat s : RuneStat.values())
            out.put(s, arrows(stack, s));
        return out;
    }

    /** The multiplier a stat currently gets, e.g. three arrows = +0.075, two negative = -0.05. */
    public static double multiplier(ItemStack stack, RuneStat stat)
    {
        return arrows(stack, stat) * ARROW_STEP;
    }

    /**
     * One socketed rune: which stat it favours, the tier it was when socketed, and the EXACT affinity change it
     * made. The delta is stored rather than recomputed because socketing picks its victims at random: without a
     * record, pulling a rune back out could only guess which stats to give back, and repeated socket/unsocket
     * cycles would drift the piece's affinity away from anything the tooltip ever showed.
     */
    public record Socket(RuneStat stat, RuneTier tier, Map<RuneStat, Integer> delta) {}

    private static final String TAG_SOCKET_STAT = "stat";
    private static final String TAG_SOCKET_TIER = "tier";
    private static final String TAG_SOCKET_DELTA = "delta";

    /** The runes socketed into this piece, in slot order. At most {@link #SOCKETS}. */
    public static List<Socket> sockets(ItemStack stack)
    {
        List<Socket> out = new ArrayList<>();
        CompoundTag r = root(stack, false);
        if (r == null)
            return out;
        ListTag list = r.getList(TAG_SOCKETS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && out.size() < SOCKETS; i++)
        {
            CompoundTag e = list.getCompound(i);
            RuneStat st = RuneStat.byKey(e.getString(TAG_SOCKET_STAT));
            if (st == null)
                continue;
            RuneTier tier = RuneTier.byName(e.getString(TAG_SOCKET_TIER));
            Map<RuneStat, Integer> delta = new EnumMap<>(RuneStat.class);
            CompoundTag d = e.getCompound(TAG_SOCKET_DELTA);
            for (RuneStat s : RuneStat.values())
                if (d.contains(s.name()))
                    delta.put(s, d.getInt(s.name()));
            out.add(new Socket(st, tier, delta));
        }
        return out;
    }

    /** Just the stats, for callers that only care what is in the slots (pips, refunds). */
    public static List<RuneStat> socketStats(ItemStack stack)
    {
        List<RuneStat> out = new ArrayList<>();
        for (Socket s : sockets(stack))
            out.add(s.stat());
        return out;
    }

    private static void writeSockets(ItemStack stack, List<Socket> sockets)
    {
        CompoundTag r = root(stack, true);
        if (r == null)
            return;
        ListTag list = new ListTag();
        for (int i = 0; i < sockets.size() && i < SOCKETS; i++)
        {
            Socket so = sockets.get(i);
            CompoundTag e = new CompoundTag();
            e.putString(TAG_SOCKET_STAT, so.stat().name());
            e.putString(TAG_SOCKET_TIER, so.tier().name());
            CompoundTag d = new CompoundTag();
            for (Map.Entry<RuneStat, Integer> en : so.delta().entrySet())
                if (en.getValue() != 0)
                    d.putInt(en.getKey().name(), en.getValue());
            e.put(TAG_SOCKET_DELTA, d);
            list.add(e);
        }
        r.put(TAG_SOCKETS, list);
    }

    /** Free rune slots left on this piece. */
    public static int freeSockets(ItemStack stack)
    {
        return Math.max(0, SOCKETS - sockets(stack).size());
    }

    /**
     * Socket a rune, reshaping the piece's affinity the way a QQ Bang does: the rune's own stat is pushed up and the
     * cost is taken out of its OPPOSING stat, so a rune is a trade rather than free power. Returns false when the
     * piece is full or the input is unusable.
     *
     * <p>The whole cost lands on {@link RuneStat#opposite()} first. That makes what a rune does predictable enough
     * to build around: a Strength rune is bought with Ki Power every time, not with whichever stat the dice picked.
     * Only when the opposite has already bottomed out does the remainder spill onto random other stats, because the
     * arrows have to come from somewhere and a piece must never gain more than it gives.
     */
    /**
     * Fill a piece to the ceiling with an ADMIN rune: {@link #MAX_ARROWS} good arrows, and nothing paid for them.
     *
     * <p>Everything the ordinary {@link #socket} path does to keep runes honest is deliberately skipped here. No
     * tier is rolled, so the gain is not a roll; no downside is drained from the opposite stat or from bystanders,
     * so the piece gains without losing; and the imbalance clamp is not applied, because a maxed piece IS the
     * imbalance and clamping it would undo the whole point. This is an operator tool, not something a player can
     * reach: the runes are only ever given out.
     *
     * <p>It also does not consume a socket. Filling six stats would otherwise cost six sockets, which no piece has.
     *
     * @param stat the single stat to fill, or null to fill every stat
     * @return true when at least one stat actually moved, so the caller knows whether to consume the rune
     */
    public static boolean socketAdmin(ItemStack stack, RuneStat stat, Random rng)
    {
        if (stack == null || stack.isEmpty())
            return false;
        ensureRolled(stack, rng);
        boolean changed = false;
        for (RuneStat s : RuneStat.values())
        {
            if (stat != null && s != stat)
                continue;
            if (arrows(stack, s) != MAX_ARROWS)
            {
                setArrows(stack, s, MAX_ARROWS);
                changed = true;
            }
        }
        return changed;
    }

    public static boolean socket(ItemStack stack, RuneStat rune, RuneTier runeTier, Random rng)
    {
        if (stack == null || stack.isEmpty() || rune == null || freeSockets(stack) <= 0)
            return false;
        ensureRolled(stack, rng);

        Map<RuneStat, Integer> delta = new EnumMap<>(RuneStat.class);

        // The rune gives its tier's ceiling, or up to GAIN_SPREAD-1 arrows short of it. NOT a flat roll from 1 to the
        // ceiling: that made a Mythical rune average three arrows and land on one nearly as often as on five, so the
        // grade a player had hunted for barely showed on the piece. The tier decides what a rune is worth; the roll
        // only decides how far off its best this one is.
        //
        // The spread is what keeps the top grade short of a guarantee. At a spread of 2 a greater rune maxed a stat
        // nine times in ten, which is not a roll, it is a formality.
        int ceiling = Math.max(1, runeTier.maxArrows());
        int gain = Math.max(1, ceiling - rng.nextInt(GAIN_SPREAD));
        int beforeGain = arrows(stack, rune);
        setArrows(stack, rune, beforeGain + gain);
        delta.put(rune, arrows(stack, rune) - beforeGain);

        // The bill is what the rune actually GAVE, not what it rolled. A stat near the ceiling absorbs only part of
        // the roll, and charging the rolled figure for arrows the piece never received is how a piece ended up six
        // arrows down on the deal: paying full price for a stat that could not move.
        // Charged at DOWNSIDE_COST_RATIO of what landed, rounded up, so a rune is an upgrade rather than a trade
        // of one stat for another at par. Still never zero while the rune gave anything.
        int applied0 = Math.max(0, delta.getOrDefault(rune, 0));
        int cost = applied0 <= 0 ? 0 : Math.max(1, (int) Math.ceil(applied0 * DOWNSIDE_COST_RATIO));

        // The opposing stat pays the bill, down to the floor.
        int owed = drain(stack, rune.opposite(), cost, delta);

        // Anything the opposite could not cover is taken off the remaining stats, one arrow at a time.
        List<RuneStat> others = new ArrayList<>();
        for (RuneStat s : RuneStat.values())
            if (s != rune && s != rune.opposite())
                others.add(s);
        while (owed > 0 && !others.isEmpty())
        {
            RuneStat victim = others.get(rng.nextInt(others.size()));
            if (drain(stack, victim, 1, delta) > 0)
            {
                // already at the floor, so it can never help; stop asking it
                others.remove(victim);
                continue;
            }
            owed--;
        }

        // Whatever the piece could not pay for, the rune gives back. Measured on the PIECE, not on this rune:
        // allowing each socket its own slack let two runes stack their allowances on top of the first roll's, and a
        // piece came out seven arrows up on the deal. Checking the whole piece makes the limit inductive instead, so
        // it holds however many runes go in. Without it, socketing into an already-floored piece would be free
        // power: nothing can be taken, so the gain lands unpaid for.
        int applied = delta.getOrDefault(rune, 0);
        int excess = (upsideArrows(stack) - downsideArrows(stack)) - MAX_IMBALANCE_ARROWS;
        int give = Math.min(Math.max(0, excess), Math.max(0, applied));
        if (give > 0)
        {
            int before = arrows(stack, rune);
            // Bounded by what this rune actually applied, so a refund can never drag the stat below where it started:
            // a rune improves a piece or does nothing, it never makes it worse.
            setArrows(stack, rune, before - give);
            // The recorded delta has to follow, or unsocket would hand back arrows that were never applied.
            delta.merge(rune, arrows(stack, rune) - before, Integer::sum);
        }

        List<Socket> current = sockets(stack);
        current.add(new Socket(rune, runeTier, delta));
        writeSockets(stack, current);
        return true;
    }

    /**
     * Take up to {@code want} arrows off one stat without pushing it past the floor, recording what was actually
     * taken. Returns how many arrows could NOT be taken, so the caller can look elsewhere for the rest.
     */
    private static int drain(ItemStack stack, RuneStat stat, int want, Map<RuneStat, Integer> delta)
    {
        if (stat == null || want <= 0)
            return Math.max(0, want);
        int now = arrows(stack, stat);
        int room = now + MAX_ARROWS;
        int take = Math.min(want, Math.max(0, room));
        if (take > 0)
        {
            setArrows(stack, stat, now - take);
            delta.merge(stat, -take, Integer::sum);
        }
        return want - take;
    }

    /**
     * Pull a rune back out of a slot, undoing exactly the affinity it applied.
     *
     * <p>Returns the socket that was removed so the caller can decide what the player gets back, or null when the
     * index is not a filled slot. The piece is restored by subtracting the recorded delta, which is why the delta is
     * stored: recomputing it would be guesswork and would let repeated cycles drift the affinity.
     */
    public static Socket unsocket(ItemStack stack, int index)
    {
        List<Socket> current = sockets(stack);
        if (index < 0 || index >= current.size())
            return null;
        Socket removed = current.remove(index);
        for (Map.Entry<RuneStat, Integer> e : removed.delta().entrySet())
            setArrows(stack, e.getKey(), arrows(stack, e.getKey()) - e.getValue());
        writeSockets(stack, current);
        return removed;
    }

    /**
     * Give a piece its tier and starting affinity if it has none. Called the first time a piece is seen, so armour
     * that already exists in a world gains runes on pickup rather than needing to be recrafted.
     *
     * <p>The roll is made one OPPOSING PAIR at a time: each pair picks a winner, the winner goes up, and its
     * opposite goes down by up to as much. A piece therefore leans three ways at once and reads as a set of trades
     * rather than a bag of unrelated numbers, and it can never be all upside.
     *
     * <p>The loser's drop can land on zero, which is what keeps pieces from all looking alike; the fixup at the end
     * only steps in for the rare piece where every pair came up gentle, so that a piece is never strictly better
     * than stock.
     */
    public static void ensureRolled(ItemStack stack, Random rng)
    {
        if (has(stack))
            return;
        // A rolled piece is indestructible. This sits BELOW the has() guard on purpose, so it only ever runs the
        // one time a piece is actually rolled: a piece rolled by an older build (which has data but no Unbreakable
        // tag) is left untouched rather than silently rewritten. Unbreakable is the vanilla NBT the durability
        // system and the tooltip both honour, so nothing else has to enforce it.
        CompoundTag itemTag = stack.getOrCreateTag();
        itemTag.putBoolean("Unbreakable", true);
        // Hide the vanilla "Unbreakable" line: the rune tooltip already tells the player what the piece is, so the
        // extra white line would just be chrome. Bit 4 of HideFlags is the Unbreakable flag, OR'd in so any other
        // hide flags already on the piece survive.
        itemTag.putInt("HideFlags", itemTag.getInt("HideFlags") | 4);
        RuneTier tier = RuneTier.roll(rng);
        setTier(stack, tier);
        // Half the row maximum at most, so a fresh piece always has room left for runes to improve it.
        int cap = Math.max(1, Math.min(tier.maxArrows(), INITIAL_MAX_ARROWS));

        // The piece is allowed to come out this many arrows up on the deal overall, and no more. Spent across the
        // pairs below, which is what keeps upside and downside inside MAX_IMBALANCE_ARROWS of each other.
        int slack = rng.nextInt(MAX_IMBALANCE_ARROWS + 1);

        for (RuneStat leader : RuneStat.pairLeaders())
        {
            RuneStat winner = rng.nextBoolean() ? leader : leader.opposite();
            RuneStat loser = winner.opposite();
            int gain = 1 + rng.nextInt(cap);
            // The loser pays the same discounted price a socketed rune does, so a piece does not arrive already
            // carrying the full-price downside the sockets no longer charge. Rounded up and floored at one, so a
            // won pair always costs the loser something and the down arrows the art is built around stay real.
            int price = Math.max(1, (int) Math.ceil(gain * DOWNSIDE_COST_RATIO));
            // THE DISCOUNT IS PART OF THE BILL, and missing that is what let fresh pieces out over the ceiling.
            // Halving the price already leans a won pair positive before any slack is spent (a two-arrow gain at a
            // one-arrow price is net +1 on its own), and the budget below was only ever charged for the shave. So
            // three lucky pairs banked three free arrows the ceiling never saw, and the slack was then spent ON TOP:
            // an unsocketed piece could arrive six arrows up against a ceiling of four. Every arrow of it was
            // invisible to the check in socket(), which then clawed the difference back out of whatever was
            // socketed afterwards, so the reward for a good roll was a piece that runes could not improve.
            //
            // Charging the discount to the same budget fixes it at the source: whatever the budget cannot cover is
            // put straight back onto the price, so a pair the piece cannot afford to be generous with simply pays
            // full freight. Net upside now equals the slack actually spent, which is bounded by MAX_IMBALANCE_ARROWS
            // by construction, so the first roll can reach the ceiling and can never pass it.
            int discount = gain - price;
            int afford = Math.min(slack, discount);
            slack -= afford;
            price += discount - afford;
            // Shave the price rather than inflate the gain: the piece leans positive by at most the slack, and the
            // slack cannot outlast the three pairs, so at least one pair always keeps a real downside.
            int shave = Math.min(slack, price);
            slack -= shave;
            setArrows(stack, winner, gain);
            setArrows(stack, loser, -(price - shave));
        }
    }

    /** Total arrows a piece gives, ignoring what it takes. */
    public static int upsideArrows(ItemStack stack)
    {
        int sum = 0;
        for (RuneStat s : RuneStat.values())
            sum += Math.max(0, arrows(stack, s));
        return sum;
    }

    /** Total arrows a piece takes, as a positive number. */
    public static int downsideArrows(ItemStack stack)
    {
        int sum = 0;
        for (RuneStat s : RuneStat.values())
            sum += Math.max(0, -arrows(stack, s));
        return sum;
    }

    private static int clampArrows(int v)
    {
        return Math.max(-MAX_ARROWS, Math.min(MAX_ARROWS, v));
    }
}
