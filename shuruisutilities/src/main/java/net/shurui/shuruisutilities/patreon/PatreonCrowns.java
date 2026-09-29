package net.shurui.shuruisutilities.patreon;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * Supporter crowns: a bitmap glyph drawn immediately before a supporter's name in chat, the tab list and above
 * their head, in the form {@code <crown> | Name}.
 *
 * <p>Which tier gets which crown is NOT decided here. Each crown is an ordinary reward key
 * ({@code crown.bronze} .. {@code crown.prismatic}) attached to a tier on the fixed ladder in PatreonTiers, like
 * every other reward, so an operator can re-hang crowns on different tiers without a code change. A permanent
 * grant in {@code permanent_grants.json} therefore also grants a crown, which is how the owner and the named
 * saiyans get theirs without pledging. {@link #REWARD_CROWN_DEVELOPER} is the one crown deliberately left off
 * every tier, so it can only ever arrive through such a grant.
 *
 * <p>The glyphs live in the {@code dmz_ragnarok:crowns} font shipped in this jar, so a crown renders on any server
 * running the mod with no resource pack installed. The codepoints match the standalone RagnarokCrowns pack
 * (U+E100..U+E104); the developer crown at U+E105 exists only here, since that pack predates it.
 *
 * <p>Glyph geometry is ascent 7 / height 9 over 9x9 sources: a 1:1 blit (so the art stays crisp, with no
 * resampling), one pixel taller than a text glyph, and the largest size that still sits fully inside all three
 * surfaces it is drawn on. It spans rows 2..10 of the 13px tab row (centred, 2px clear top and bottom), fits the
 * name-tag background box, and fits the 9px chat line. Rank badges are ascent 8 / height 11 and do overhang the
 * latter two slightly, so a crown reads marginally shorter than a rank badge sitting next to it; that is the
 * deliberate cost of never spilling out of a box.
 */
public final class PatreonCrowns
{
    private PatreonCrowns() {}

    /** Font holding the six crown glyphs. Shipped in this jar under assets/dmz_ragnarok/font/crowns.json. */
    public static final ResourceLocation FONT = new ResourceLocation("dmz_ragnarok", "crowns");

    /** First crown codepoint. The crowns are contiguous from here, weakest first, developer last. */
    public static final int FIRST_CODEPOINT = 0xE100;

    public static final String REWARD_CROWN_BRONZE = "crown.bronze";
    public static final String REWARD_CROWN_SILVER = "crown.silver";
    public static final String REWARD_CROWN_GOLD = "crown.gold";
    public static final String REWARD_CROWN_DIAMOND = "crown.diamond";
    public static final String REWARD_CROWN_PRISMATIC = "crown.prismatic";

    /**
     * The pink developer crown. Deliberately NOT on the fixed tier ladder, so it cannot be bought: it is
     * held only by whoever carries a permanent grant for it (the two owner accounts, whose {@code "*"} grant matches
     * every reward key). Being last in {@link #CROWN_REWARDS} it also outranks every purchasable crown, so an owner
     * shows the developer crown rather than the prismatic one they would otherwise qualify for.
     */
    public static final String REWARD_CROWN_DEVELOPER = "crown.developer";

    // Weakest first. The index into this array IS the offset from FIRST_CODEPOINT, and a player holding several
    // crown keys (tier plus a permanent grant, say) is drawn the strongest one: the last match wins.
    private static final String[] CROWN_REWARDS = {
            REWARD_CROWN_BRONZE,
            REWARD_CROWN_SILVER,
            REWARD_CROWN_GOLD,
            REWARD_CROWN_DIAMOND,
            REWARD_CROWN_PRISMATIC,
            REWARD_CROWN_DEVELOPER
    };

    /** The separator between the crown and the player's own name. */
    private static final String SEPARATOR = " | ";

    /**
     * The codepoint of the strongest crown this UUID currently holds, or 0 for none. UUID-keyed so login-time
     * hooks can ask before the player entity exists. Reads the grace-aware effective tier via {@link PatreonAPI},
     * so a supporter keeps their crown through a backend outage.
     */
    public static int codepointFor(UUID uuid)
    {
        if (uuid == null)
            return 0;
        int best = 0;
        for (int i = 0; i < CROWN_REWARDS.length; i++)
        {
            if (PatreonAPI.hasReward(uuid, CROWN_REWARDS[i]))
                best = FIRST_CODEPOINT + i;
        }
        return best;
    }

    /** The codepoint of the strongest crown this player currently holds, or 0 for none. */
    public static int codepointFor(Player player)
    {
        return player == null ? 0 : codepointFor(player.getUUID());
    }

    /** True when the codepoint is one of the crown glyphs. */
    public static boolean isCrown(int codepoint)
    {
        return codepoint >= FIRST_CODEPOINT && codepoint < FIRST_CODEPOINT + CROWN_REWARDS.length;
    }

    /** A single crown glyph in the crowns font. */
    public static MutableComponent glyph(int codepoint)
    {
        return Component.literal(String.valueOf((char) codepoint)).withStyle(Style.EMPTY.withFont(FONT));
    }

    /**
     * {@code <crown> | rest}, or {@code rest} unchanged when the player has no crown.
     *
     * <p>The glyph is a sibling of a neutral empty parent rather than a parent of the rest, so the crowns font
     * never leaks onto the following text (which would render the whole name as missing-glyph boxes). The
     * separator and {@code rest} carry no style of their own, so they still inherit whatever colour the
     * surrounding chat format, team or formatter applied.
     */
    public static Component decorate(int codepoint, Component rest)
    {
        if (codepoint <= 0)
            return rest;
        return Component.empty()
                .append(glyph(codepoint))
                .append(Component.literal(SEPARATOR))
                .append(rest);
    }
}
