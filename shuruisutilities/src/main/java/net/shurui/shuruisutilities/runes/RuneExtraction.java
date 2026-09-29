package net.shurui.shuruisutilities.runes;

import java.util.Random;

import net.minecraft.world.item.ItemStack;

/**
 * What happens to a rune when it is prised back out of a piece of armour.
 *
 * <p>Removal is allowed, but it is meant to hurt: a socketed rune has been worked into the piece, so getting it back
 * intact is the exception rather than the rule. One roll decides everything, at {@link #DAMAGE_CHANCE}:
 *
 * <ul>
 *   <li>A rune above the lowest tier DEGRADES one tier.</li>
 *   <li>A rune already at the lowest tier BREAKS and is gone.</li>
 *   <li>Otherwise it survives untouched.</li>
 * </ul>
 *
 * <p>One roll rather than two chained ones on purpose: chaining would make a Low rune's true survival odds 1%, not
 * the 10% the number suggests, and the warning shown to the player has to be honest about what they are risking.
 */
public final class RuneExtraction
{
    private RuneExtraction() {}

    /** Chance that removing a rune damages it. The same number governs degrading and breaking. */
    public static final float DAMAGE_CHANCE = 0.90f;

    /** Outcome of prising a rune out, for messaging as much as for the item itself. */
    public enum Result
    {
        INTACT,
        DEGRADED,
        BROKEN
    }

    /** What a removal produced: the item to hand back (empty when broken) and which of the three happened. */
    public record Outcome(Result result, ItemStack rune) {}

    /**
     * Roll the outcome for a rune coming out of a socket.
     *
     * @param socket the socket being emptied, which carries the rune's stat and the tier it went in at
     */
    public static Outcome extract(ArmorRunes.Socket socket, Random rng)
    {
        if (socket == null)
            return new Outcome(Result.BROKEN, ItemStack.EMPTY);

        RuneTier tier = socket.tier();
        boolean damaged = rng.nextFloat() < DAMAGE_CHANCE;

        if (!damaged)
        {
            ItemStack intact = new ItemStack(RuneItems.of(socket.stat()).get());
            RuneItem.setTier(intact, tier);
            return new Outcome(Result.INTACT, intact);
        }

        RuneTier lower = degrade(tier);
        if (lower == null)
        {
            // already at the bottom: nothing to degrade to, so it is destroyed
            return new Outcome(Result.BROKEN, ItemStack.EMPTY);
        }
        ItemStack worse = new ItemStack(RuneItems.of(socket.stat()).get());
        RuneItem.setTier(worse, lower);
        return new Outcome(Result.DEGRADED, worse);
    }

    /** The tier one step down, or null when already at the lowest. */
    public static RuneTier degrade(RuneTier tier)
    {
        RuneTier[] all = RuneTier.values();
        int i = tier.ordinal();
        return i <= 0 ? null : all[i - 1];
    }

    /** True when removing this rune risks destroying it outright rather than merely degrading it. */
    public static boolean wouldRiskDestruction(RuneTier tier)
    {
        return degrade(tier) == null;
    }
}
