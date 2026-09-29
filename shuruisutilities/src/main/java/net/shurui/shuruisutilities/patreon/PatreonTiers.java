package net.shurui.shuruisutilities.patreon;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The Patreon tier to reward ladder. FIXED IN CODE, identical on every server.
 *
 * <p>It used to load {@code ShuruisUtilities/patreon/tiers.json} and let an operator re-hang any reward on any
 * tier. That is no longer true, deliberately. A supporter pays the author once and should get the same thing
 * wherever they play; a per-server mapping meant a server could quietly strip a tier's rewards, or hand a tier's
 * rewards to people who never pledged, and the supporter would have no way to know which. Fixing the ladder here
 * is what makes "your pledge works everywhere" a statement rather than a hope.
 *
 * <p>Any {@code tiers.json} left over from an older install is IGNORED, not deleted and not migrated. Deleting an
 * operator's file to make a point would be rude, and migrating it would reintroduce exactly the per-server
 * variation this removes. It is left on disk and mentioned once in the log so nobody edits it for an hour
 * wondering why nothing changes.
 *
 * <p>Nothing here decides what a reward key MEANS; features read a player's tier or reward keys through
 * {@link PatreonAPI} and act on them themselves.
 */
public final class PatreonTiers
{
    private PatreonTiers() {}

    /** One tier of the fixed ladder. */
    public static final class Tier
    {
        public String id = "";
        public int rank = 0;
        public String displayName = "";
        public List<String> rewards = new ArrayList<>();
    }

    // id (lower-cased) -> tier, in ladder order. Rebuilt on every load; read on the server thread by the API, so
    // a plain field swap is enough (no partial-state window: we build then assign).
    private static volatile Map<String, Tier> byId = Collections.emptyMap();

    /**
     * Build the fixed ladder. Safe to call again for a reload; it produces the same map every time.
     *
     * <p>Kept as a load step rather than a static initialiser so {@code /rgreload} still has something to call and
     * the log still states the ladder, which is how an operator confirms which rewards their build carries.
     */
    public static void load()
    {
        Map<String, Tier> map = new LinkedHashMap<>();
        for (Tier t : LADDER)
            map.put(t.id.toLowerCase(Locale.ROOT), t);
        byId = Collections.unmodifiableMap(map);

        LoggingHandler.sulog.info("[Patreon] {} fixed tier(s) active: {}", byId.size(), summary());
        warnAboutStaleFileOnce();
    }

    /** The ladder as one line, so the log says exactly what this build grants. */
    private static String summary()
    {
        StringBuilder sb = new StringBuilder();
        for (Tier t : LADDER)
        {
            if (sb.length() > 0)
                sb.append("; ");
            sb.append(t.displayName).append('=').append(String.join(",", t.rewards));
        }
        return sb.toString();
    }

    private static boolean staleFileWarned;

    /**
     * Say once, if an old tiers.json is still lying around, that it does nothing now.
     *
     * <p>Silence here would be the cruel option: the file still looks authoritative, and an operator would edit it
     * and reload and edit it again before working out that it is inert.
     */
    private static void warnAboutStaleFileOnce()
    {
        if (staleFileWarned)
            return;
        staleFileWarned = true;
        try
        {
            File legacy = new File(new File(ShuruisUtilities.getSUDirectory(), "patreon"), "tiers.json");
            if (legacy.exists())
                LoggingHandler.sulog.warn("[Patreon] {} is no longer read. Supporter tiers are fixed in the mod so "
                        + "a pledge grants the same rewards on every server. The file is left alone; you can "
                        + "delete it.", legacy.getAbsolutePath());
        }
        catch (Throwable ignored)
        {
            // reporting a stale file is a courtesy, never a reason to fail startup
        }
    }

    /**
     * The five tiers of the Ragnarok Network campaign, each with its crown plus what its Patreon description
     * promises. Ids match what the backend Worker reports (its TIER_MAP maps each Patreon tier id onto one of
     * these), so the two sides line up without configuration.
     */
    private static final List<Tier> LADDER = List.of(
            tier("z_fighter", 1, "Z-Fighter",
                    PatreonCrowns.REWARD_CROWN_BRONZE),
            tier("elite_warrior", 2, "Elite Warrior",
                    PatreonCrowns.REWARD_CROWN_SILVER),
            tier("super_saiyan", 3, "Super Saiyan",
                    PatreonCrowns.REWARD_CROWN_GOLD, PatreonAPI.REWARD_FORM_COSMETIC),
            tier("destroyer_god", 4, "Destroyer God",
                    PatreonCrowns.REWARD_CROWN_DIAMOND, PatreonAPI.REWARD_FORM_COSMETIC,
                    PatreonAPI.REWARD_PLAYER_LIMIT_BYPASS),
            tier("angel", 5, "Angel",
                    PatreonCrowns.REWARD_CROWN_PRISMATIC, PatreonAPI.REWARD_FORM_COSMETIC,
                    PatreonAPI.REWARD_PLAYER_LIMIT_BYPASS));

    private static Tier tier(String id, int rank, String displayName, String... rewards)
    {
        Tier t = new Tier();
        t.id = id;
        t.rank = rank;
        t.displayName = displayName;
        t.rewards = List.of(rewards);
        return t;
    }

    /** The tier with this id, or null if none is defined. Case-insensitive. */
    public static Tier get(String id)
    {
        if (id == null || id.isBlank())
            return null;
        return byId.get(id.toLowerCase(Locale.ROOT));
    }

    /** The rank of this tier id, or 0 when the id is empty or undefined (treated as "no tier"). */
    public static int rankOf(String id)
    {
        Tier t = get(id);
        return t == null ? 0 : t.rank;
    }

    /** Reward keys for this tier id, or an empty list when the id is empty or undefined. */
    public static List<String> rewardsOf(String id)
    {
        Tier t = get(id);
        return t == null ? Collections.emptyList() : Collections.unmodifiableList(t.rewards);
    }

    /** Display name for this tier id, falling back to the raw id when it is undefined. */
    public static String displayNameOf(String id)
    {
        Tier t = get(id);
        if (t != null && t.displayName != null && !t.displayName.isBlank())
            return t.displayName;
        return id == null ? "" : id;
    }

    /** The fixed ladder, lowest rank first, as {@code [id, displayName]} pairs (for editor dropdowns). */
    public static List<String[]> ladder()
    {
        List<String[]> out = new ArrayList<>(LADDER.size());
        for (Tier t : LADDER)
            out.add(new String[] {t.id, t.displayName});
        return out;
    }

    /** True when a tier with this id is defined. */
    public static boolean isDefined(String id)
    {
        return get(id) != null;
    }

    /** Every distinct reward key attached to any configured tier. Used to expand an "all rewards" permanent grant. */
    public static Set<String> allRewardKeys()
    {
        Set<String> keys = new LinkedHashSet<>();
        for (Tier t : byId.values())
            if (t.rewards != null)
                keys.addAll(t.rewards);
        return keys;
    }
}
