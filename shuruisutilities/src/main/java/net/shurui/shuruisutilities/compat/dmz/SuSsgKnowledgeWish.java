package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dragonminez.common.wish.Wish;
import com.dragonminez.common.wish.wishes.CommandWish;

import net.shurui.shuruisutilities.ritual.SsgKnowledgeCommand;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * "The knowledge of Super Saiyan God" on Shenron's wish list: the thing that makes the SSG charge ritual possible.
 *
 * <h2>Shenron only</h2>
 * The entry is appended to {@link SuSsj5Wish#EARTH_WISH_SCREEN} and to nothing else. Porunga's list, and every other
 * dragon's, is never read or written by this class. The wish is a Shenron wish because the ritual is Earth's.
 *
 * <h2>Why it is a command wish, and why it goes on the end</h2>
 * Both for the same reasons as {@link SuSsj5Wish}: DragonMineZ persists wishes through a type adapter that only knows
 * its own kinds, so a bespoke {@code Wish} subclass would be written out and fail to read back; and DMZ grants a wish
 * by INDEX into the dragon's list, so anything of ours has to sit at the end where it cannot shift the index of a
 * wish beneath it.
 *
 * <h2>How it is filtered, and why it is coupled to the SSJ5 row</h2>
 * This entry IS now hidden per player (saiyans at or above the configured level floor only), but it cannot be
 * filtered independently. Index-based granting means a per-player list is only safe while it is a PREFIX of the
 * server's list, and this row sits directly ABOVE the SSJ5 row. So the two are trimmed together as one nested suffix
 * in {@link SuSsj5Wish#visibleTo}: the only safe states are both shown, SSJ5 hidden, or both hidden. A visible SSJ5
 * above a hidden SSG would leave a hole, and every row after it would resolve to the wrong wish on the server. The
 * one thing this row is NOT trimmed on is "already taken": a saiyan who took the knowledge can still be SSJ5-eligible,
 * so hiding it then would reopen exactly that hole. It stays visible once earned, and the grant-time gate in
 * {@link net.shurui.shuruisutilities.ritual.WishRitualManager#ssgKnowledgeWishBlocked} refuses a repeat without
 * spending the dragon.
 */
public final class SuSsgKnowledgeWish
{
    private SuSsgKnowledgeWish() {}

    private static final String NAME_KEY = "wish.dmz_ragnarok.ssg_knowledge.name";
    private static final String DESCRIPTION_KEY = "wish.dmz_ragnarok.ssg_knowledge.desc";

    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean(false);

    /** The entry itself. Recreated rather than cached, so a reload never hands out a stale object. */
    public static Wish build()
    {
        return new CommandWish(NAME_KEY, DESCRIPTION_KEY, SsgKnowledgeCommand.COMMAND);
    }

    /** True when this list entry is our SSG knowledge row, by its name key. Used to trim it and to gate a grant. */
    public static boolean isSsgKnowledge(Wish wish)
    {
        return wish != null && NAME_KEY.equals(wish.getName());
    }

    /**
     * Put the entry on the end of Shenron's list if it is not already there, returning the map to install.
     *
     * <p>Idempotent by name, because this runs on every datapack sync and a reload may or may not have taken our
     * entry with it. Must be applied BEFORE {@link SuSsj5Wish#withSsj5}, which removes and re-appends its own row and
     * so has to end up last.
     */
    public static Map<String, List<Wish>> withSsgKnowledge(Map<String, List<Wish>> wishes)
    {
        Map<String, List<Wish>> merged = new LinkedHashMap<>(wishes == null ? Map.of() : wishes);
        List<Wish> earth = new ArrayList<>(merged.getOrDefault(SuSsj5Wish.EARTH_WISH_SCREEN, List.of()));
        if (earth.isEmpty())
            return merged; // DMZ has not built Shenron's list yet; nothing to append to
        earth.removeIf(SuSsgKnowledgeWish::isSsgKnowledge);
        earth.add(build());
        merged.put(SuSsj5Wish.EARTH_WISH_SCREEN, earth);
        // Logged once. This runs inside a Throwable guard, so without a line here a DMZ change that quietly stopped
        // the append would look exactly like the wish never having been added.
        if (ANNOUNCED.compareAndSet(false, true))
        {
            LoggingHandler.sulog.info(
                    "[ritual] Knowledge of Super Saiyan God added to Shenron's wish list as entry {} of {}.",
                    earth.size(), earth.size());
        }
        return merged;
    }
}
