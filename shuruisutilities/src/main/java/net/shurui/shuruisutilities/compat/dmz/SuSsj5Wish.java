package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dragonminez.common.wish.Wish;
import com.dragonminez.common.wish.wishes.CommandWish;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.ritual.WishRitualManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The Super Saiyan 5 entry on Shenron's wish list, and the rule that decides who can see it.
 *
 * <h2>Why it is a command wish</h2>
 * DragonMineZ persists and reloads wishes as JSON through a type adapter that only knows its own wish kinds, so a
 * bespoke {@code Wish} subclass would be written out and then fail to read back. Its {@code CommandWish} is a native
 * kind, so the entry survives a reload, shows up in DMZ's own editor, and grants by running a command. The command is
 * op gated and DMZ runs it from the server's own source, so it is a delivery mechanism rather than a back door.
 *
 * <h2>Why it goes on the END of the list, and why that is load bearing</h2>
 * DMZ grants a wish by INDEX into the dragon's list: the client sends which rows it picked and the server resolves
 * them against its own copy. So a player who is shown a different list from the one the server resolves against would
 * grant the wrong wish. Appending ours last means the only difference between the full list and the filtered one is
 * whether the final row exists, and every other index is identical on both sides. Inserting it anywhere else would
 * silently shift every wish below it for everyone who cannot see it.
 */
public final class SuSsj5Wish
{
    private SuSsj5Wish() {}

    /** DMZ's wish screen id for the Earth dragon. Its wish file is {@code <world>/dragonminez/wishes/shenron.json}. */
    public static final String EARTH_WISH_SCREEN = "shenron";

    /**
     * The command the wish entry runs; the Ragnarok Key registers it (feature {@code rituals}). {@code %player%} is
     * substituted by DMZ before execution. Stored verbatim in DMZ's wish file: never change the text.
     */
    public static final String SSJ5_COMMAND = "dmzragnarok_ssj5fusion %player%";

    private static final String NAME_KEY = "wish.dmz_ragnarok.ssj5.name";
    private static final String DESCRIPTION_KEY = "wish.dmz_ragnarok.ssj5.desc";

    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean(false);

    /** The entry itself. Recreated rather than cached, so a reload never hands out a stale object. */
    public static Wish build()
    {
        return new CommandWish(NAME_KEY, DESCRIPTION_KEY, SSJ5_COMMAND);
    }

    /** True when this list entry is our Super Saiyan 5 row, by its name key. Used to trim it and to gate a grant. */
    public static boolean isSsj5(Wish wish)
    {
        return wish != null && NAME_KEY.equals(wish.getName());
    }

    /**
     * Put the entry on the end of Shenron's list if it is not already there, returning the map to install.
     *
     * <p>Idempotent by name, because this runs on every datapack sync and a reload may or may not have taken our entry
     * with it.
     */
    public static Map<String, List<Wish>> withSsj5(Map<String, List<Wish>> wishes)
    {
        Map<String, List<Wish>> merged = new LinkedHashMap<>(wishes == null ? Map.of() : wishes);
        List<Wish> earth = new ArrayList<>(merged.getOrDefault(EARTH_WISH_SCREEN, List.of()));
        if (earth.isEmpty())
            return merged; // DMZ has not built Shenron's list yet; nothing to append to
        earth.removeIf(SuSsj5Wish::isSsj5);
        earth.add(build());
        merged.put(EARTH_WISH_SCREEN, earth);
        // Logged once. This runs inside a Throwable guard, so without a line here a DMZ change that quietly stopped the
        // append would look exactly like the wish simply never being eligible for anyone.
        if (ANNOUNCED.compareAndSet(false, true))
        {
            LoggingHandler.sulog.info("[ritual] Super Saiyan 5 added to Shenron's wish list as entry {} of {}.",
                    earth.size(), earth.size());
        }
        return merged;
    }

    /**
     * The wish map as this player should SEE it: everyone else's wishes untouched, and the two Earth ritual rows (the
     * SSG knowledge wish and the SSJ5 wish) present only where this player could take them.
     *
     * <h2>The two rows are trimmed together, as one nested suffix</h2>
     * DMZ resolves a picked wish by INDEX into this same list, so the per-player list has to remain a PREFIX of the
     * server's: only the TAIL may be removed, never a row from the middle. SSJ5 is the last row and the SSG knowledge
     * row sits directly above it, so the only safe visible states are "both shown", "SSJ5 hidden" and "both hidden".
     * A visible SSJ5 must never sit above a hidden SSG, or every index below the gap resolves to the wrong wish on the
     * server. SSJ5 eligibility already implies the SSG floor is cleared (an SSJ5-eligible saiyan is past the higher
     * level bar), and the {@code ssj5Visible ||} below makes that nesting hold even if an operator sets the SSG floor
     * higher than the SSJ5 requirement.
     */
    public static Map<String, List<Wish>> visibleTo(Map<String, List<Wish>> wishes, ServerPlayer player)
    {
        if (wishes == null)
            return Map.of();
        Map<String, List<Wish>> filtered = new LinkedHashMap<>(wishes);
        // Super Shenron's power wish is the LAST row of its list, so hiding it from a character that has used its
        // three shifts no other row's index.
        List<Wish> sup = filtered.get(SuperWishes.SUPER_SHENRON);
        if (sup != null && net.shurui.shuruisutilities.wish.SuperPowerWishCommand.exhausted(player))
        {
            List<Wish> trimmedSuper = new ArrayList<>(sup);
            trimmedSuper.removeIf(SuperWishes::isPower);
            filtered.put(SuperWishes.SUPER_SHENRON, trimmedSuper);
        }
        List<Wish> earth = filtered.get(EARTH_WISH_SCREEN);
        if (earth == null || earth.isEmpty())
            return filtered;

        boolean ssj5Visible = WishRitualManager.canWishForSsj5(player);
        boolean ssgVisible = ssj5Visible || WishRitualManager.ssgWishVisible(player);
        if (ssj5Visible && ssgVisible)
            return filtered;

        List<Wish> trimmed = new ArrayList<>(earth);
        if (!ssj5Visible)
            trimmed.removeIf(SuSsj5Wish::isSsj5);
        if (!ssgVisible)
            trimmed.removeIf(SuSsgKnowledgeWish::isSsgKnowledge);
        filtered.put(EARTH_WISH_SCREEN, trimmed);
        return filtered;
    }
}
