package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dragonminez.common.wish.Wish;
import com.dragonminez.common.wish.wishes.CommandWish;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.wish.ReleaseBoostCommand;
import net.shurui.shuruisutilities.wish.StatRelocateWishCommand;
import net.shurui.shuruisutilities.wish.SuperPowerWishCommand;
import net.shurui.shuruisutilities.wish.ZeniWishCommand;

/**
 * The standing wishes on Super Shenron's list: a fortune in zeni, a stat redistribution, a permanent lift to the
 * ceiling on ki release, and the power wish (which replaced the old Super-ball kit on every server).
 *
 * <h2>Why these are appended and not seeded</h2>
 * {@link SuDragonBallDefinitions#reapplyWishes()} seeds a dragon's default list only when that dragon has NO wish
 * data at all. That is right for the first-run defaults, because it never clobbers an operator's edit, but it is
 * useless for adding a wish later: DMZ writes any edit to {@code <world>/dragonminez/wishes/super_shenron.json}, and
 * once that file exists the list is never empty again, so a new default seeded that way would never reach a server
 * that has already run. These three are therefore appended on EVERY datapack sync (every reload, every login,
 * every restart), the same mechanism {@link SuSsgKnowledgeWish} uses, which is what makes them defaults in the only
 * sense that matters: present on an existing world, not just a fresh one.
 *
 * <h2>Why they are command wishes</h2>
 * Same reason as {@link SuSsj5Wish} and {@link SuSsgKnowledgeWish}: DragonMineZ persists wishes through a type
 * adapter that knows only its own kinds, so a bespoke {@code Wish} subclass would be written out and then fail to
 * read back. A {@code CommandWish} pointing at an op-level command survives the round trip.
 *
 * <h2>Order, and the conditional rows</h2>
 * DMZ grants a wish by INDEX into the list, so ours go on the end where they cannot shift a wish beneath them.
 * The zeni row is the only one that can be absent: the Super ball set is public tier but the economy is NOT (it is
 * only in the Ragnarok Key, see {@code ZeniWishCommand.economyLive()}), so a server can be entitled to summon Super
 * Shenron and have nothing to pay out of. Offering a wish that can only fail is worse than not offering it, so the
 * row is left off entirely there, and it sits LAST so that its coming and going cannot move the two above it.
 *
 * <p>That omission is safe against index-based granting in a way per-player filtering is not: it is decided per
 * SERVER, so every client is shown the same list the server holds. {@link SuSsj5Wish} is the case that has to be
 * careful, because it hides a row from some players and not others, and that is why it must remain the very last
 * entry on Earth's list.
 */
public final class SuperWishes
{
    private SuperWishes() {}

    /** DMZ's dragon id for the Super set, the key its wish list is stored under. */
    public static final String SUPER_SHENRON = "super_shenron";

    private static final String RELEASE_NAME = "wish.dmz_ragnarok.release.name";
    private static final String RELEASE_DESC = "wish.dmz_ragnarok.release.desc";
    private static final String RELOCATE_NAME = "wish.dmz_ragnarok.relocate.name";
    private static final String RELOCATE_DESC = "wish.dmz_ragnarok.relocate.desc";
    private static final String ZENI_NAME = "wish.dmz_ragnarok.zeni.name";
    private static final String ZENI_DESC = "wish.dmz_ragnarok.zeni.desc";
    private static final String POWER_NAME = "wish.dmz_ragnarok.superpower.name";
    private static final String POWER_DESC = "wish.dmz_ragnarok.superpower.desc";

    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean(false);

    /** True for the power wish row, which is hidden per player once that character has used it up. */
    public static boolean isPower(Wish wish)
    {
        return wish != null && POWER_NAME.equals(wish.getName());
    }

    private static boolean isOurs(Wish wish)
    {
        if (wish == null)
            return false;
        String name = wish.getName();
        return RELEASE_NAME.equals(name) || RELOCATE_NAME.equals(name) || ZENI_NAME.equals(name)
                || POWER_NAME.equals(name);
    }

    /**
     * Put the three entries on the end of Super Shenron's list if they are not already there, returning the map to
     * install.
     *
     * <p>Idempotent by name: every entry of ours is stripped first and then re-added, so running on each sync
     * neither duplicates a row nor leaves a stale one behind when a condition changes.
     */
    public static Map<String, List<Wish>> withSuperWishes(Map<String, List<Wish>> wishes)
    {
        Map<String, List<Wish>> merged = new LinkedHashMap<>(wishes == null ? Map.of() : wishes);
        List<Wish> list = new ArrayList<>(merged.getOrDefault(SUPER_SHENRON, List.of()));
        if (list.isEmpty())
            return merged; // DMZ has not built Super Shenron's list yet; nothing to append to
        list.removeIf(SuperWishes::isOurs);
        list.add(new CommandWish(RELEASE_NAME, RELEASE_DESC, ReleaseBoostCommand.COMMAND));
        list.add(new CommandWish(RELOCATE_NAME, RELOCATE_DESC, StatRelocateWishCommand.COMMAND));
        boolean economy = ZeniWishCommand.economyLive();
        if (economy)
            list.add(new CommandWish(ZENI_NAME, ZENI_DESC, ZeniWishCommand.COMMAND));
        // The power wish replaced the Super-ball kit on every server (owner decision). It is LAST so that hiding it
        // per player (SuSsj5Wish.visibleTo, once a character has used its three) shifts no row beneath it.
        boolean power = true;
        list.add(new CommandWish(POWER_NAME, POWER_DESC, SuperPowerWishCommand.COMMAND));
        merged.put(SUPER_SHENRON, list);
        // Logged once. This runs inside a Throwable guard, so without a line here a DMZ change that quietly stopped
        // the append would look exactly like the wishes never having been added.
        if (ANNOUNCED.compareAndSet(false, true))
        {
            LoggingHandler.sulog.info(
                    "[wish] Super Shenron's list now ends with our {} entries ({} rows total){}{}.",
                    2 + (economy ? 1 : 0) + (power ? 1 : 0), list.size(),
                    economy ? "" : "; the zeni wish is off because this server may not run the economy",
                    power ? "; the power wish (3 per character) ends the list" : "");
        }
        return merged;
    }
}
