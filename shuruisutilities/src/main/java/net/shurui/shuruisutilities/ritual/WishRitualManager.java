package net.shurui.shuruisutilities.ritual;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.api.key.RitualHooks;
import net.shurui.shuruisutilities.compat.dmz.RitualFormsCompat;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Central, DMZ-free server logic for the dragon-ball wish rituals. Fed by {@code MixinDmzGrantWish} on every granted
 * wish (with the ball-set id and how many wishes that summon granted).
 *
 * <p>Each player keeps a wish counter per ball set (Earth and Namek, in {@link WishRitualStore}); that counting is core
 * and runs with or without the Ragnarok Key. What the counters OPEN is private and lives in the key (feature
 * {@code rituals}, reached through {@link RitualHooks}):
 * <ul>
 *   <li><b>Earth</b>: the Super Saiyan 5 wish (the fusion with Shenron), offered only to a saiyan in Super Saiyan 4 at
 *       {@link #REQUIRED_LEVEL} or above, for a {@link #LEVEL_COST} level cost.</li>
 *   <li><b>Namek</b>: the eleventh wish spends the set and leaves a Porunga idol; a namekian restores a spent set at
 *       the idol for the same level floor and cost ({@link DragonBallRecreation}).</li>
 * </ul>
 * The Super Saiyan God knowledge wish is public and stays here. All DMZ access goes through {@link RitualFormsCompat},
 * so this class is safe with DMZ absent.
 */
public final class WishRitualManager
{
    private WishRitualManager() {}

    /** DMZ level required to perform either level-10k ritual (SSJ5 or Namekian ball-set recreation). */
    public static final int REQUIRED_LEVEL = 10_000;
    /** Levels removed by a completed ritual, taken randomly from stats without dropping any stat below zero. */
    public static final int LEVEL_COST = 5_000;

    /**
     * Called by the wish-grant mixin once per granted wish batch: {@code rawSet} is the DMZ ball-set id and
     * {@code wishes} is how many wishes that summon granted (each counts). Never throws into the caller.
     */
    public static void onWishGranted(ServerPlayer player, String rawSet, int wishes)
    {
        try
        {
            if (player == null || wishes <= 0)
                return;

            // The Super Dragon Balls (ball set "super") are handled HERE, ahead of normalizeSet, and deliberately not
            // folded into it. normalizeSet admits only the two sets that carry a wish COUNTER and a level-10k
            // recreation ritual (earth, namek); see WishRitualStore.isSuperSet for why widening it would be wrong. The
            // Super set has exactly one consequence, the kit entitlement, and it reaches us because DMZ's own ball
            // block summons a DragonWishEntity for the "super" set and MixinDmzGrantWish reads its ball-set id off the
            // GrantWishC2S packet. The earth/namek path below keeps its counters exactly as they were; what the
            // threshold opens moved to the Ragnarok Key unchanged.
            //
            // The Super-ball kit is retired (owner decision): Super Shenron's power wish replaced it on every server
            // (SuperWishes, SuperPowerWishCommand). A Super-ball wish no longer writes the kit permission; players who
            // already hold it keep it (nothing is revoked).
            if (WishRitualStore.isSuperSet(rawSet))
                return;

            String set = WishRitualStore.normalizeSet(rawSet);
            if (set == null)
                return;

            int newCount = WishRitualStore.add(player, set, wishes);
            LoggingHandler.sulog.debug("[ritual] {} now at {} {} wishes",
                    player.getGameProfile().getName(), newCount, set);

            // The offer opens on the eleventh wish and every wish thereafter until it is taken.
            if (newCount <= WishRitualStore.WISH_THRESHOLD)
                return;

            // What the threshold opens (the SSJ5 wish's eligibility reads the Earth counter; the Namek set is spent and
            // leaves a Porunga idol) is the Ragnarok Key's. Keyless this is a no-op and only the counter moves.
            RitualHooks.get().onWishCounted(player, set, newCount);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[ritual] onWishGranted failed: {}", t.toString());
        }
    }

    /**
     * Whether the Super Saiyan 5 wish should be OFFERED to this player at all (and, called again at grant time, whether
     * it may be granted: a wish list is a client side menu, so a check that only ran when the list was built would be
     * one a crafted packet could walk past). The answer is the Ragnarok Key's; keyless it is always false.
     */
    public static boolean canWishForSsj5(ServerPlayer player)
    {
        try
        {
            return RitualHooks.get().canWishForSsj5(player);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Whether the Knowledge of Super Saiyan God wish should be SHOWN to this player: a saiyan at or above the
     * configured level floor ({@link SUConfig#ssgWishMinLevel}, default 5000).
     *
     * <p>Deliberately does NOT ask whether they already carry the knowledge. {@code SuSsj5Wish.visibleTo} trims the
     * two Earth ritual rows as one nested suffix, and DMZ grants a wish by INDEX, so the SSG row can only be hidden
     * when the SSJ5 row directly below it is hidden too. A saiyan who already took the knowledge can still be
     * SSJ5-eligible, so hiding the SSG row on "already taken" would leave a visible SSJ5 row above a hole and shift
     * every index. The row therefore stays visible once earned, and the grant-time gate refuses it as a no-op. See
     * {@link #canWishForSsgKnowledge} and {@link #ssgKnowledgeWishBlocked}.
     */
    public static boolean ssgWishVisible(ServerPlayer player)
    {
        try
        {
            return player != null
                    && RitualFormsCompat.isSsgRace(player)
                    && RitualFormsCompat.level(player) >= SUConfig.ssgWishMinLevel;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Whether GRANTING the SSG knowledge wish would actually do something: a saiyan, at or above the level floor, who
     * does not already carry it. This is the authority the grant-time gate uses, so an ineligible take is refused and
     * the dragon is left for another wish rather than spent on nothing.
     */
    public static boolean canWishForSsgKnowledge(ServerPlayer player)
    {
        try
        {
            return player != null
                    && RitualFormsCompat.isSsgRace(player)
                    && RitualFormsCompat.level(player) >= SUConfig.ssgWishMinLevel
                    && !WishRitualStore.hasSsgKnowledge(player);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Grant-time gate for the SSG knowledge wish. Returns true when the wish must be REFUSED (the caller then leaves
     * the dragon unspent and lets the player pick again), after telling the player exactly why. Returns false when the
     * wish may proceed.
     */
    public static boolean ssgKnowledgeWishBlocked(ServerPlayer player)
    {
        if (canWishForSsgKnowledge(player))
            return false;
        if (player == null)
            return true;
        if (!RitualFormsCompat.isSsgRace(player))
            player.sendSystemMessage(Component.translatable("ritual.dmz_ragnarok.ssg.knowledge.not_saiyan"));
        else if (WishRitualStore.hasSsgKnowledge(player))
            player.sendSystemMessage(Component.translatable("ritual.dmz_ragnarok.ssg.knowledge.already"));
        else
            player.sendSystemMessage(
                    Component.translatable("ritual.dmz_ragnarok.ssg.knowledge.level", SUConfig.ssgWishMinLevel));
        return true;
    }

    /**
     * Grant-time gate for the SSJ5 wish. Returns true when the wish must be REFUSED (dragon unspent), after telling
     * the player why; false when it may proceed. Mirrors {@link #ssgKnowledgeWishBlocked}. The Ragnarok Key's answer;
     * keyless the wish is always refused.
     */
    public static boolean ssj5WishBlocked(ServerPlayer player)
    {
        try
        {
            return RitualHooks.get().ssj5WishBlocked(player);
        }
        catch (Throwable t)
        {
            return true;
        }
    }
}
