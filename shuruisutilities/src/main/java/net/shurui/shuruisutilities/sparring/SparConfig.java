package net.shurui.shuruisutilities.sparring;

import java.util.ArrayList;
import java.util.List;

/**
 * Tunable server settings for the Sparring module, persisted to {@code <SUdir>/sparring/config.json} via the
 * v2 {@code DataManager}. A spar is a consensual, non-lethal training bout between two guild OR party members;
 * it grants DragonMineZ Training Points scaled by how long and how hard it was fought and how close in power the
 * two fighters are, so it acts as a group alternative to DMZ's solo training minigames.
 *
 * <p>The TP payout mirrors the minigame math on purpose: a spar earns a number of "training points" (the same
 * unit a minigame level rewards) which is then converted with the SAME progression curve DMZ uses
 * ({@link #rewardBaseCoefficient} times the player's per-stat cost raised to {@link #rewardCostExponent}), so a
 * spar's reward tracks a player's growth exactly like a minigame does instead of going trivial at endgame or
 * being farmable early.
 */
public class SparConfig
{

    /** Master switch for the whole sparring feature. */
    public boolean enabled = true;
    /** Allow spars between two members of the same guild. */
    public boolean allowGuildSpar = true;
    /** Allow spars between two members of the same DragonMineZ party. */
    public boolean allowPartySpar = true;

    /**
     * When true, a GUILD spar may only start while both fighters stand inside a chunk claimed by the guild they
     * share (their guild base). PARTY spars never require a base: party members may be guildless and there is no
     * "party base" concept, so a party spar only requires the two be near each other in the same dimension.
     */
    public boolean requireGuildBaseForGuildSpar = true;
    /**
     * Maximum distance in blocks the two fighters may drift apart before the spar ends as a draw (no TP). Leaving
     * the dimension ends it the same way. This is the "left the area" exit path.
     */
    public double maxSeparationBlocks = 64.0;

    /** Seconds a pending spar invite stays open before it expires. */
    public int inviteExpireSeconds = 30;
    /** Cooldown, in seconds, before a player may START another spar. PER PLAYER (see the module docs). Default 10 min. */
    public int cooldownSeconds = 600;
    /** No blow landed by either fighter for this many seconds ends the spar as a draw (no TP). */
    public int idleTimeoutSeconds = 20;
    /** Hard cap on a single spar's length in seconds; on reaching it the spar ends as a draw (no TP). */
    public int maxDurationSeconds = 300;

    /** Block the use of healing items for both fighters during a spar. */
    public boolean blockHealingItems = true;
    /**
     * When true, ALL edible items are blocked during a spar, not just the healing set below. Off by default so
     * only the healing items are blocked.
     */
    public boolean blockAllFood = false;
    /**
     * Extra item registry ids blocked during a spar on top of Shurui's Utilities' own health-restoring beans
     * (which are always blocked when {@link #blockHealingItems} is on). Defaults to the DragonMineZ senzu bean and
     * the vanilla golden apples.
     */
    public List<String> extraBlockedHealingItemIds = defaultBlockedIds();

    private static List<String> defaultBlockedIds()
    {
        List<String> l = new ArrayList<>();
        l.add("dragonminez:senzu_bean");
        l.add("minecraft:golden_apple");
        l.add("minecraft:enchanted_golden_apple");
        return l;
    }

    /**
     * "Training points" earned per full bar of health of damage a fighter deals to their opponent. Damage is
     * measured relative to the opponent's max health, so it is power-neutral (one KO's worth of damage is about one
     * bar regardless of level). This is the main "how hard you fought" term.
     */
    public double tpPointsPerHealthBar = 3.0;
    /** "Training points" earned per minute the spar lasts. A small term so a long fight pays a little more. */
    public double tpPointsPerMinute = 2.0;
    /**
     * Winner's reward is a TRANSFER, not minted TP: the winner takes this percent (0-100) of the training points the
     * LOSER earned this bout, and the loser keeps the rest. It is zero sum between the two fighters, so nothing is
     * created out of nothing. The transfer can never exceed what the loser earned, so a loser who earned little gives
     * up little and a loser who earned nothing gives up nothing, and the loser's own payout can never go negative.
     * Set to 0 to disable the transfer entirely (both fighters just keep their own effort TP).
     */
    public double winnerTpTransferPercent = 25.0;
    /** Cap on the effort "training points" (health-bar + duration terms) a single spar can earn. */
    public double perSparPointCap = 40.0;

    /** Global multiplier on every spar payout. 1.0 = the tuned defaults. */
    public double globalTpMultiplier = 1.0;

    /**
     * Power-closeness curve exponent. closeness = (weakerBP / strongerBP) ^ exponent, so a higher exponent
     * punishes mismatches harder. 1.0 is linear; the 2.0 default makes a 2:1 power gap pay a quarter. The two BP
     * values compared here are BASE (untransformed) battle power, so a transformation never moves the ratio.
     */
    public double closenessExponent = 2.0;
    /**
     * If the weaker fighter's BASE battle power is below this fraction of the stronger's, the spar pays ZERO. This
     * is the hard anti-farm floor: a much stronger player cannot grind TP off a weak one, and because it is keyed
     * to base (not transformed) power, neither fighter can slip under or over the floor by timing a transformation.
     * Default 0.34 (about a 3:1 gap).
     */
    public double minClosenessRatio = 0.34;

    /**
     * When true, a spar's "training points" are converted to TP with DragonMineZ's own progression curve so the
     * reward tracks the fighter's growth exactly like a minigame. Per point of reward the player gets
     * {@link #rewardBaseCoefficient} * (perStatCost ^ {@link #rewardCostExponent}) TP, where perStatCost is DMZ's
     * cost of the player's next stat point. When false, each point is worth a flat {@link #flatTpPerPoint} TP.
     */
    public boolean scaleByProgression = true;
    /** DMZ reward coefficient; kept equal to DMZ's training.json default so a spar point equals a minigame level. */
    public double rewardBaseCoefficient = 3.4;
    /** DMZ reward cost exponent; kept equal to DMZ's training.json default. */
    public double rewardCostExponent = 0.6;
    /** Flat TP per reward point when {@link #scaleByProgression} is off. */
    public int flatTpPerPoint = 5000;
}
