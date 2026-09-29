package net.shurui.shuruisutilities.guilds;

import java.util.HashMap;
import java.util.Map;

/**
 * Tunable server settings for the Guilds module, persisted to {@code <SUdir>/guilds/config.json}.
 * A guild's claim limit scales with its aggregate DragonMineZ battle power, so a stronger guild
 * controls more land.
 */
public class GuildConfig
{
    /** Zeni cost to found a guild (0 = free). */
    public long creationCost = 0L;
    /** Zeni cost per chunk claimed (0 = free). */
    public long claimCost = 0L;
    /** Hard cap on members per guild. */
    public int maxMembers = 20;

    /**
     * Claims every guild gets regardless of size or power: the STARTING allowance, and an absolute floor under the
     * scaling below. 25 chunks, so a new guild has real territory to build in before it has the members or the battle
     * power to unlock any more. It is a floor, NOT a term that is added on top, so the scaling below only starts to
     * bite once it clears 25 on both halves at once: 5 chunks per member puts the size ceiling past 25 at 6 members,
     * and one chunk per 500,000 BP puts the power gate past 25 at 13M aggregate BP. Under either of those a guild
     * simply sits at 25. The intended shape is a block of 25 for every 5 members, each block paid for in battle
     * power, with the first block free.
     */
    public int baseClaimsPerGuild = 25;
    /**
     * Chunks per member the guild's SIZE allows (the ceiling half of the claim limit). Default 5: the member count
     * caps how much territory a guild can ever hold, while battle power ({@link #battlePowerPerClaim}) gates how much
     * of that ceiling is actually unlocked. The two are combined with MIN, not added, so a guild needs both the
     * members and the power to reach a given claim count.
     */
    public double baseClaimsPerMember = 5.0;
    /** Aggregate battle power required to unlock one chunk of the member-count ceiling. Default: one chunk per
     * 500,000 BP, so a 10-member guild needs 25,000,000 BP to unlock all 50 of its chunks. */
    public double battlePowerPerClaim = 500_000.0;
    /** Absolute ceiling on how many chunks a single guild may ever hold, no matter its power or size. 0 or negative
     * disables the cap; default 0 so the member-and-power scaling is the only limit. */
    public int maxClaimsHardCap = 0;

    /** Allow enemies to seize chunks from a guild whose claim count exceeds its power-based limit. */
    public boolean allowOverclaim = true;
    /** Master switch for territory protection (block/interaction/explosion/mob handlers). */
    public boolean protectClaims = true;
    /** If true, PvP is blocked inside a claim unless the territory's PVP flag is on or the players are enemies. */
    public boolean pvpProtectionInClaims = true;
    /** Teleport warmup seconds for /guild home and /guild warp (0 = instant). */
    public int homeWarmupSeconds = 0;

    /**
     * How long, in milliseconds, an attacker guild is locked out of raiding the SAME defender again after a
     * FAILED raid. Defaults to three days. Stored as millis (not seconds) so it can be compared directly against
     * the {@link System#currentTimeMillis()} stamps the lockout store keeps.
     */
    public long raidFailureLockoutMillis = 3L * 24L * 60L * 60L * 1000L;

    /**
     * How long, in milliseconds, a SUCCESSFUL raid locks the attacker out of raiding that same defender again.
     * Defaults to three days, matching {@link #raidFailureLockoutMillis}: a win now sets the same cooldown as a
     * loss, so a stronger guild cannot immediately re-raid a weaker one and grind it out of the game. Set this to
     * zero (or negative) to disable the success lockout and let a winner raid the same defender again at once.
     *
     * <p>Config gotcha: an operator whose {@code <SUdir>/guilds/config.json} already lists this key keeps the
     * value on disk (the loader never overwrites an existing key with a changed default), so a config generated
     * while the default was 0 stays at 0 until they edit or delete that line. A fresh config, or one that never
     * had the key, picks up this three-day default.
     */
    public long raidSuccessLockoutMillis = 3L * 24L * 60L * 60L * 1000L;

    /**
     * Seconds the raid stays in its COUNTDOWN phase before the fight goes ACTIVE. Phase 1 only drives the state
     * machine; later phases teleport attackers and spawn the defending clones when this elapses.
     */
    public int raidCountdownSeconds = 15;

    /**
     * Hard ceiling in seconds on how long a single raid's ACTIVE phase may run before it auto-resolves as a
     * failure for the attackers. A safety valve so a raid can never wedge a guild into a permanent "in a raid"
     * state if later phases never report a win or a loss. Zero or negative = no time limit.
     */
    public int raidTimeLimitSeconds = 600;

    /**
     * Seconds the defending guild is given to opt into a raid before the fight goes live. When a raid is declared
     * and at least one defender is online, the raid holds in an opt-in window this long; any online defender may
     * run {@code /guild raiddefend} to teleport to their planet and fight, and EACH volunteer replaces one attacking
     * clone (floored at zero clones). The window runs exactly once and the raid always proceeds when it elapses, so
     * it can never be used to stall a raid. Zero or negative disables the window (the raid starts with the full
     * clone count, unchanged from a planet with no online defenders). Default 60 (one minute).
     */
    public int raidDefenderOptInSeconds = 60;

    /**
     * Fraction (0..1) of a DESTROYED planet's container CONTENTS recovered into its owning guild's salvage vault.
     * Default 1.0 (100%): a guild loses its world but its stored items are handed back in full, to be withdrawn from
     * the guild GUI by a sufficiently ranked member. Set below 1 to recover only a share; 0 recovers no container
     * contents. Values outside 0..1 are clamped at capture time.
     */
    public double salvageContainerRate = 1.0;

    /**
     * Fraction (0..1) of every OTHER (non-container-content) block on a destroyed planet's surface recovered as the
     * block's item form. Default 0.5 (50%). This applies to the natural terrain (stone, dirt, grass, ...) as well as
     * anything the guild placed, so a guild that built a base gets half its materials back. To keep bulk terrain out
     * of the salvage entirely, set the terrain block types to 0 in {@link #salvageBlockRateOverrides}. Values outside
     * 0..1 are clamped at capture time.
     */
    public double salvageBlockRate = 0.5;

    /**
     * Per-block-type recovery-rate overrides, keyed by block registry id (e.g. {@code "minecraft:stone"} or
     * {@code "dragonminez:namek_stone"}), each a fraction 0..1. A block whose id is listed here uses its override
     * instead of {@link #salvageBlockRate}; an id set to 0 is excluded from salvage entirely (the natural way to keep
     * a destroyed planet's bulk terrain out of the vault). Editable from the admin guilds GUI. Empty by default, so
     * every block uses the flat {@link #salvageBlockRate} out of the box.
     */
    public Map<String, Double> salvageBlockRateOverrides = new HashMap<>();

    /**
     * How many blocks ABOVE the fixed surface play altitude the salvage scan reaches when a planet is destroyed. The
     * scan already starts at the deepest terrain block, so this bounds how tall a base the capture covers. Default 64,
     * generous for a surface build while keeping the walk bounded (the scan is also hard-capped on total block reads).
     */
    public int salvageScanHeightAbove = 64;

    /**
     * How many raid tickets each attacking raider who actually took part is granted when their guild WINS a planet
     * raid. The ticket is the existing content item {@code shuruisutilities:ss_ticket}, reused as-is and spendable at
     * an operator-configured CustomNPCs raid shop; this feature only GRANTS the tickets, it never redeems them.
     *
     * <p>Default 3: a win is a rare, hard-fought event, so the reward is deliberately small and meant to accumulate
     * across several wins before it buys anything. Set to 0 to disable the reward entirely (a zero grant is skipped
     * cleanly, so no ticket is minted and no "you received tickets" line is shown). Negative values are treated as 0.
     * Only raiders who were actually brought onto the planet earn tickets (they hold a raid vault stash); a member who
     * was offline when the raid started never entered and so earns nothing, exactly as they hold no stash to return.
     */
    public int raidWinTicketReward = 3;
}
