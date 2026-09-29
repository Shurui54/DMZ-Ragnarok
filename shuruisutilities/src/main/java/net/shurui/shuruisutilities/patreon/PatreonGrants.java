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
import java.util.UUID;

import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.api.key.ShardHooks;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.shard.ShardSync;
import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Permanent Patreon reward grants that hold regardless of Patreon status, so they keep working when the backend is
 * unconfigured or unreachable (which is the whole point of them). Two sources feed this:
 *
 * <ul>
 *   <li>The operator-editable file {@code ShuruisUtilities/patreon/permanent_grants.json}. Its defaults are the two
 *       owner accounts ({@code Shuruiiii}, {@code Shuruiiiii}), each granted {@link #ALL_REWARDS} ("*") forever. An
 *       operator can add more people (by name or, better, by uuid) without a rebuild.</li>
 *   <li>The custom-saiyan owners, derived at load time from the {@link SaiyanAppearance.NamedSaiyan} rows themselves
 *       (never duplicated here), each granted {@link PatreonAPI#REWARD_FORM_COSMETIC}. Adding a new named saiyan there
 *       therefore grants that account the form cosmetic automatically.</li>
 * </ul>
 *
 * <p>Matching is by canonical UUID when a grant carries one (survives a rename), otherwise by username matched
 * case-insensitively. The named-saiyan grants and the owner defaults are given as NAMES, so a rename of one of those
 * accounts would move the grant to whoever next holds the name; give a grant an explicit {@code uuid} to pin it.
 *
 * <p>All reads happen on the server thread through {@link PatreonManager}; a plain volatile field swap on reload is
 * enough (the maps are built fully, then assigned).
 */
public final class PatreonGrants
{
    private PatreonGrants() {}

    /** Sentinel reward key meaning "every reward, present and future". Used for the owner accounts. */
    public static final String ALL_REWARDS = "*";

    /** One grant row. Public fields so Gson maps the JSON directly. */
    public static final class GrantEntry
    {
        // Minecraft username, matched case-insensitively. Used only when uuid is empty.
        public String name = "";
        // Canonical dashed UUID (optional). When set it is matched exactly and wins over name, surviving a rename.
        public String uuid = "";
        // Reward keys granted forever. Use ["*"] for every reward.
        public List<String> rewards = new ArrayList<>();
    }

    /** Root of permanent_grants.json. The leading {@code _comment} is documentation only and ignored by the loader. */
    public static final class GrantFile
    {
        public String _comment =
                "Permanent Patreon reward grants that apply regardless of Patreon status (they work even when the "
                        + "backend is unconfigured or down). Match by 'uuid' (preferred, survives a rename) or by 'name' "
                        + "(case-insensitive). 'rewards' are reward keys; use [\"*\"] for every reward. Reward keys SU acts "
                        + "on today: 'cosmetic.form', 'perk.player_limit_bypass', the five tier crowns 'crown.bronze' "
                        + "through 'crown.prismatic', and 'crown.developer' (the pink developer crown, which is on no "
                        + "tier and so can only ever come from a grant listed here). Note: the accounts that own a custom "
                        + "named saiyan already get 'cosmetic.form' automatically from the game's named-saiyan list, so do "
                        + "NOT list them here. Edit this file then run /rgreload to apply without a restart.";
        public List<GrantEntry> grants = new ArrayList<>();
    }

    // username (lower-cased) -> reward key set (may contain ALL_REWARDS). Rebuilt on every load.
    private static volatile Map<String, Set<String>> byName = Collections.emptyMap();
    // uuid -> reward key set (may contain ALL_REWARDS). Rebuilt on every load.
    private static volatile Map<UUID, Set<String>> byUuid = Collections.emptyMap();

    // The operator grants read from the file on the last load, kept so the shard boot import can carry them over
    // without re-parsing. Never includes the derived named-saiyan grants, which every server builds for itself.
    private static volatile List<GrantEntry> lastFileGrants = Collections.emptyList();

    private static File file()
    {
        File dir = new File(ShuruisUtilities.getSUDirectory(), "patreon");
        if (!dir.exists())
            dir.mkdirs();
        return new File(dir, "permanent_grants.json");
    }

    /** Loads permanent_grants.json (writing a commented default first if absent) and merges the named-saiyan owners. */
    public static void load()
    {
        File f = file();
        if (!f.exists())
            writeDefault(f);

        GrantFile data = null;
        try
        {
            data = DataManager.load(GrantFile.class, f);
        }
        catch (RuntimeException ex)
        {
            LoggingHandler.sulog.error("[Patreon] Could not parse permanent_grants.json; treating it as empty. "
                    + ex.getMessage());
        }

        List<GrantEntry> fileGrants = new ArrayList<>();
        if (data != null && data.grants != null)
            for (GrantEntry g : data.grants)
                if (g != null)
                    fileGrants.add(g);
        lastFileGrants = fileGrants;

        // Build the local maps from the file straight away, so a read never sees an empty set in the window before
        // the network sync (if any) lands. This is also the whole of the behaviour when the shard system is off.
        rebuild(fileGrants);
        LoggingHandler.sulog.info("[Patreon] Loaded permanent grants for {} name(s) and {} uuid(s)",
                byName.size(), byUuid.size());

        // A live reload on a networked server pushes the (possibly newly hand-added) grants out and pulls the whole
        // shared set back in, so an operator's edit reaches the other servers without a restart. The initial boot
        // import is done by ShardPatreonGrants on ServerStartedEvent instead, so this only fires for a genuine
        // reload, never twice at startup.
        if (ShardSync.active() && ServerLifecycleHooks.getCurrentServer() != null)
            ShardHooks.get().patreonGrantsReloaded(new ArrayList<>(fileGrants));
    }

    /** The operator grants read from the file on the last load, for the shard boot import. Never the derived ones. */
    public static List<GrantEntry> fileGrants()
    {
        return new ArrayList<>(lastFileGrants);
    }

    /**
     * Replace the live maps with the network's shared grants plus the derived named-saiyan grants.
     *
     * <p>Called by {@code ShardPatreonGrants} once the shared table has been read. The shared set already contains
     * this server's own file grants (they were imported first), so nothing local is dropped: the maps only widen to
     * every server's grants. A plain volatile swap, safe to call from the shard thread because reads only ever take
     * the whole map reference.
     */
    public static void applyShared(List<GrantEntry> sharedGrants)
    {
        rebuild(sharedGrants == null ? new ArrayList<>() : sharedGrants);
    }

    /** Build {@link #byName} and {@link #byUuid} from a grant list plus the derived named-saiyan grants, and swap. */
    private static void rebuild(List<GrantEntry> grants)
    {
        Map<String, Set<String>> names = new LinkedHashMap<>();
        Map<UUID, Set<String>> uuids = new LinkedHashMap<>();

        if (grants != null)
        {
            for (GrantEntry g : grants)
            {
                if (g == null || g.rewards == null || g.rewards.isEmpty())
                    continue;
                Set<String> keys = cleanKeys(g.rewards);
                if (keys.isEmpty())
                    continue;
                if (g.uuid != null && !g.uuid.isBlank())
                {
                    try
                    {
                        uuids.computeIfAbsent(UUID.fromString(g.uuid.trim()), k -> new LinkedHashSet<>()).addAll(keys);
                        continue;
                    }
                    catch (IllegalArgumentException ignored)
                    {
                        // fall through to name matching if the uuid is malformed
                    }
                }
                if (g.name != null && !g.name.isBlank())
                    names.computeIfAbsent(g.name.trim().toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>()).addAll(keys);
            }
        }

        // Derive the custom-saiyan owners from the enum rows themselves, so adding a named saiyan grants it too.
        for (SaiyanAppearance.NamedSaiyan ns : SaiyanAppearance.NamedSaiyan.values())
        {
            String user = ns.username();
            if (user == null || user.isBlank())
                continue;
            names.computeIfAbsent(user.trim().toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                    .add(PatreonAPI.REWARD_FORM_COSMETIC);
        }

        byName = Collections.unmodifiableMap(names);
        byUuid = Collections.unmodifiableMap(uuids);
    }

    private static Set<String> cleanKeys(List<String> raw)
    {
        Set<String> keys = new LinkedHashSet<>();
        for (String k : raw)
        {
            if (k == null)
                continue;
            String t = k.trim();
            if (!t.isEmpty())
                keys.add(t);
        }
        return keys;
    }

    /**
     * Writes the default grants: the two owner accounts, pinned by UUID so a rename cannot move the grant to whoever
     * next holds the name. Each gets {@link #ALL_REWARDS} plus an explicit
     * {@link PatreonCrowns#REWARD_CROWN_DEVELOPER}. The explicit crown key is redundant while the {@code "*"} is
     * there (a {@code "*"} grant already matches every key) but it states the intent, and it keeps the developer
     * crown working if someone later narrows the wildcard to a specific list.
     */
    private static void writeDefault(File f)
    {
        GrantFile def = new GrantFile();
        GrantEntry owner1 = new GrantEntry();
        owner1.name = "Shuruiiii";
        owner1.uuid = "a53f8095-df8a-4adb-9b9b-ed65670a5113";
        owner1.rewards = new ArrayList<>(List.of(ALL_REWARDS, PatreonCrowns.REWARD_CROWN_DEVELOPER));
        GrantEntry owner2 = new GrantEntry();
        owner2.name = "Shuruiiiii";
        owner2.uuid = "3db01403-b235-491a-81f1-8e7ec8ebcdea";
        owner2.rewards = new ArrayList<>(List.of(ALL_REWARDS, PatreonCrowns.REWARD_CROWN_DEVELOPER));
        def.grants = new ArrayList<>(List.of(owner1, owner2));
        DataManager.save(def, f);
        LoggingHandler.sulog.info("[Patreon] Wrote default permanent grants to " + f.getAbsolutePath());
    }

    /** Reward keys permanently granted to this UUID (may contain {@link #ALL_REWARDS}); empty when none. */
    public static Set<String> rewardsForUuid(UUID uuid)
    {
        if (uuid == null)
            return Collections.emptySet();
        return byUuid.getOrDefault(uuid, Collections.emptySet());
    }

    /** Reward keys permanently granted to this username (may contain {@link #ALL_REWARDS}); empty when none. */
    public static Set<String> rewardsForName(String name)
    {
        if (name == null || name.isBlank())
            return Collections.emptySet();
        return byName.getOrDefault(name.trim().toLowerCase(Locale.ROOT), Collections.emptySet());
    }
}
