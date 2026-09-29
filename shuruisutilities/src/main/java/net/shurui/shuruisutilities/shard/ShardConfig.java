package net.shurui.shuruisutilities.shard;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.shurui.shuruisutilities.api.key.ShardHooks;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Which server this is, and how to reach the shared player vault.
 *
 * <h2>Why this is its own file and not a Forge config</h2>
 * It carries a database password. A Forge SERVER config lives in {@code <world>/serverconfig} and is SENT TO EVERY
 * CLIENT that joins, so a credential in one is a credential handed to the playerbase. This lives beside SU's other
 * install-scoped state instead, is never synced, never travels with a world copy, and never appears in a backup of
 * the world folder.
 *
 * <p>Written with a commented default on first run so an operator has something to fill in rather than having to
 * guess the shape.
 */
public final class ShardConfig
{
    private ShardConfig() {}

    /** What a server is FOR. It decides which dimensions belong here and where players are sent by default. */
    public enum Role
    {
        /**
         * The front door. One IP, no world of its own worth keeping, and its job is to put a player onto the
         * server they belong on. Holds no builds, so it can be restarted freely.
         */
        HUB,
        /**
         * The persistent world. Everything a player BUILT lives here and must never be regenerated: the overworld,
         * the nether, the end, Namek, Vegeta and the generated planets. Kept as one server on purpose, because
         * splitting build dimensions across servers is what makes a base become unreachable later.
         */
        SMP,
        /**
         * Everything disposable: instanced dungeons, raid arenas, tournament grounds, events. These churn chunks
         * and entities hard, which is exactly why they are worth keeping off the server holding people's houses.
         */
        OPENWORLD
    }

    public static final class Values
    {
        /** Master switch. Off means SU behaves exactly as a single server, and no driver is ever loaded. */
        public boolean enabled = false;

        /** Unique, stable name for THIS server. It is written into the vault as the lock owner, so it must
         *  differ on every server and must not change on restart. */
        public String serverId = "smp";

        /** {@link Role}. Case insensitive. */
        public String role = "SMP";

        /** JDBC url, e.g. {@code jdbc:mariadb://10.0.0.5:3306/ragnarok}. Works against MySQL or MariaDB. */
        public String jdbcUrl = "jdbc:mariadb://127.0.0.1:3306/ragnarok";
        public String username = "ragnarok";
        public String password = "";

        /** Table name prefix, so one database can host more than one network. */
        public String tablePrefix = "su_";

        /**
         * How many days of staff command history to keep in {@code <prefix>command_log} before it is pruned.
         *
         * <p>The command audit log ({@link ShardCommandLog}) grows for as long as staff run commands, so it is
         * trimmed once at boot and daily thereafter with a cheap, capped, idempotent DELETE run on every shard.
         * 180 days is roughly two full seasons of moderation history, which is what answering "who did this, and
         * had they done it before" actually needs. Set to 0 or below to keep everything and never prune.
         */
        public int commandLogRetentionDays = 180;

        /**
         * REFUSE A LOGIN THE VAULT CANNOT SERVE. On by default and it should stay on.
         *
         * <p>With it off, a player joins on their local copy when the database is unreachable, plays, and their
         * result is written over whatever the vault holds the moment it comes back. Two servers doing that at once
         * is how inventories get duplicated. Refusing to let somebody in is a visible, temporary annoyance;
         * duplicating their items is a permanent economy problem.
         */
        public boolean refuseLoginWhenUnavailable = true;

        /**
         * How long a lock may go unrefreshed before another server may take it, in seconds.
         *
         * <p>This is purely crash recovery: a healthy server refreshes every online player's lock on a heartbeat,
         * so this only elapses when the owning server died holding it. Too short and a lagging server has its
         * players stolen; too long and a crash locks people out for that long. Two minutes is a compromise that
         * survives a long GC pause.
         */
        public int lockTimeoutSeconds = 120;

        /** How often to refresh the locks of everyone online, in seconds. Must be well under the timeout. */
        public int heartbeatSeconds = 20;

        /** Seconds a login may wait for the vault before giving up. Login is blocked for this long at worst. */
        public int loginTimeoutSeconds = 10;

        /**
         * The whole NETWORK's player ceiling, not one server's max-players. 0 disables it entirely.
         *
         * <p>This is a SOFT cap: the bypass list (staff, donators, qualifying Patreon supporters) is always
         * admitted, so the real population may sit above this number. That is intended, so a full network is
         * never a locked door to the people who paid to skip the queue.
         *
         * <p>It lives here in shard.json rather than in a Forge config on purpose. It is network scoped operator
         * config, so it must never ship to a client (a SERVER config would), and it has to be readable as early as
         * the rest of this layer, which is hand parsed at mod construction well before Forge configs load.
         */
        public int networkPlayerCap = 150;

        /**
         * Send arriving players straight on to a play server. Only meaningful on the HUB.
         *
         * <p>This is why no proxy plugin is needed: the hub is a Forge server running this mod, it knows every
         * arriving player's address, and it can already move them. Velocity's own try list only ever picks the
         * same server for everybody, so the choice has to be made somewhere that can see the player, and this is
         * the only such place.
         */
        public boolean routeOnJoin = true;

        /**
         * The servers a hub may route to, in preference order. SMP is deliberately absent: it is reached by
         * teleporting to somebody on it, never by joining.
         */
        public List<String> routeTargets = new ArrayList<>(List.of("open-1", "open-2"));

        /**
         * How a hub chooses between {@link #routeTargets}.
         *
         * <ul>
         *   <li>{@code PLAYER_COUNT} sends them to whichever has fewest players. Right when the shards sit in
         *       one datacentre, where "closest" is meaningless and an even split is the only thing worth
         *       optimising.</li>
         *   <li>{@code REGION} matches the player's address against {@link #routeRegions} and falls back to
         *       player count when nothing matches. For shards in DIFFERENT datacentres.</li>
         * </ul>
         *
         * <p>Note that neither is a ping measurement. Nobody has measured a ping to a backend at the moment
         * they connect; the proxy only knows an address. Every "closest server" feature works this way.
         */
        public String routeStrategy = "PLAYER_COUNT";

        /**
         * Address prefix to server, for {@code REGION}. Longest matching prefix wins, so a general rule and a
         * more specific exception can sit side by side.
         *
         * <p>Written as plain prefixes rather than CIDR on purpose: they are readable, they are editable by
         * somebody who is not a network engineer, and being wrong costs a slightly worse route rather than a
         * failed login.
         */
        public Map<String, String> routeRegions = new LinkedHashMap<>();

        /**
         * Ticks to leave a player where they joined before the balancer moves them, so the join finishes first.
         *
         * <p>Was 20 (one second). A client with this pack is still loading terrain a second after the server fires
         * PlayerLoggedInEvent, and a proxy switch started then has to redo the Forge handshake with a client that is
         * not ready: on 2026-09-12 every player routed from ow1 to ow2 in the first minute after a restart sent no
         * mod list reply and was dropped as a slow login 30 s later. Ten seconds lets the join actually finish.
         */
        public int routeDelayTicks = 200;

        /**
         * How many fewer players a target must have before an OPENWORLD server hands an arrival on.
         *
         * <p>Only consulted when there is no hub and the open shards balance each other. A hub always routes,
         * because it is not a destination and keeping anybody there is the one thing it must not do.
         *
         * <p>Zero would ping-pong. Player joins open-1 at 11 against open-2's 10, gets moved; arriving on open-2
         * makes it 11 against 10 the other way, and the same rule bounces them straight back. A threshold means
         * the move only happens when it actually evens things out, and the destination then has no reason to send
         * them anywhere. Two is the smallest value that cannot oscillate.
         */
        public int routeMinAdvantage = 2;

        /**
         * How {@link #dimensions} is read.
         *
         * <ul>
         *   <li>{@code OFF} (the default) ignores the list entirely, which is how a single server behaves.</li>
         *   <li>{@code DENY} treats it as dimensions this server must NOT host. Everything else works.</li>
         *   <li>{@code ALLOW} treats it as the only dimensions this server hosts. Everything else is refused.</li>
         * </ul>
         *
         * <p>DENY is what the shipped templates use, and the reason is the direction each mode fails in. A
         * dimension nobody remembered to list stays reachable under DENY and becomes unreachable under ALLOW, so
         * forgetting one costs a divergent copy in the first case and a broken feature in the second. Start with
         * DENY, naming the dimensions that genuinely live elsewhere, and move to ALLOW once the list is known to
         * be complete.
         */
        public String dimensionMode = "OFF";

        /**
         * The SU warp a player is sent to when they are found in a dimension this server does not host.
         * Empty means fall back to the overworld spawn, which is what this always used to do.
         *
         * <p>WHY this had to exist. The eviction fallback was {@code server.overworld()}. That is fine on a
         * shard that hosts the overworld, and a trap on one that does not: the recovery sweep runs every second,
         * so a server whose dimension rules exclude the overworld would throw the player INTO a dimension it
         * does not host and evict them again a second later, for ever. An SMP that is only meant to serve its
         * own dimension is exactly that case, and it is the reason this setting exists rather than a preference.
         *
         * <p>The warp's own dimension is checked against the rules before use. A warp that itself sits somewhere
         * this server does not host would just move the loop, so it is refused and the overworld fallback runs
         * instead.
         */
        public String arrivalWarp = "";

        /**
         * The serverId of the server that hosts player building, i.e. the SMP.
         *
         * <p>Homes, personal warps and guild homes may only be set in a dimension this server OWNS (per
         * {@link #dimensionOwners}), and using one from elsewhere routes the player here. Every dimension it owns
         * counts, not just its main world: a base in its nether or on one of its planets is still a base on the
         * SMP.
         *
         * <p>Read against dimensionOwners rather than a second dimension list so the rule cannot drift from the
         * routing and gating that already use that map.
         */
        public String smpServerId = "smp";

        /**
         * Show translucent stand-ins for players on the servers listed in {@link #ghostPeers}.
         *
         * <p>Off by default. It is the one feature here that costs bandwidth in proportion to how many people are
         * near each other rather than to how many are online, so it should be switched on deliberately.
         */
        public boolean ghostsEnabled = false;

        /**
         * The servers whose players should appear here as ghosts.
         *
         * <p>ONLY servers running a COPY OF THIS SAME WORLD belong in this list. A position on a server with
         * different terrain is meaningless here: the ghost would stand inside a hill, under the floor, or in
         * mid air, because those coordinates describe somewhere else. That is why the two open world shards
         * list each other and the SMP lists nobody.
         */
        public List<String> ghostPeers = new ArrayList<>();

        /** How far a ghost is sent, in blocks. This is what keeps the cost proportional to local crowding. */
        public int ghostRadius = 96;

        /** How often positions are exchanged. 100ms is smooth once the client interpolates between updates. */
        public int ghostUpdateMillis = 100;

        /** Dimension ids, read according to {@link #dimensionMode}. */
        public List<String> dimensions = new ArrayList<>();

        /**
         * Dimension id to the server that does host it.
         *
         * <p>This is no longer only a label. An entry here makes travel into a dimension this server does not host
         * HAND THE PLAYER TO THAT SERVER instead of refusing the move: see
         * {@link net.shurui.shuruisutilities.shard.ShardDimensions#onTravel}. A dimension missing from here is still
         * refused, just without naming where it lives, so an absent entry is a working, quieter fallback rather than
         * a fault.
         *
         * <p>TWO CONSEQUENCES OF THAT.
         *
         * <p>First, the ids have to be the ones actually IN USE, not the ones we ship JSON for. The dimensions the
         * network runs in are {@code minecraft:overworld} and {@code dragonminez:otherworld}; writing
         * {@code dmz_ragnarok:overworld} or {@code dmz_ragnarok:otherworld} here names dimensions nobody ever enters,
         * so the lookup returns null and every hop that depended on it falls through to the refusal. That exact
         * typo is why the death-to-Otherworld hop and the Instant Transmission hop both looked broken.
         *
         * <p>Second, only give an owner to a dimension a player is meant to LIVE in. Anything technical or transient
         * ({@code ae2:spatial_storage} is the standing example) would fling a player onto another server the moment
         * they used it, which is far worse than the refusal it replaced.
         */
        public Map<String, String> dimensionOwners = new LinkedHashMap<>();

        /**
         * Mark each player in chat and in the tab list with the server they are on.
         *
         * <p>On by default, because the whole point of a unified chat and a unified tab list is that you can see
         * people who are NOT standing next to you, and without this there is nothing to tell you that walking
         * over to them is impossible.
         */
        public boolean showServerTags = true;

        /**
         * Server id to the short label shown next to a player's name. Only an override list: an id that is not
         * here gets a label derived from the id itself ({@code open-1} becomes {@code OW1}, {@code smp} becomes
         * {@code SMP}), which is why this can be left empty and still read correctly.
         *
         * <p>Every server derives labels the same way from the same ids, so this does NOT have to be kept in step
         * across the network to work. It only has to be if an operator wants a name the rule would not produce.
         */
        public Map<String, String> serverTags = new LinkedHashMap<>();

        /**
         * Forward a player targeting command to the shard the target is on.
         *
         * <p>OFF by default, and it should stay off until a network has launch tested it. When on, a command like
         * {@code /smite player Bob} typed on a shard where Bob is not online, but that names somebody who IS online
         * on another shard, is sent to that shard, run there WITH THE SENDER'S OWN authority (re-checked against the
         * destination's synced permissions, never as console), and its feedback is returned to the sender. This is a
         * privilege sensitive path: a mistake in how authority is carried is an escalation, which is why it is opt in
         * rather than on with sharding.
         *
         * <p>An operator can also kill it from the module switchboard key {@code Shard.CommandForwarding}; both must
         * be true for a command to cross.
         */
        public boolean forwardPlayerCommands = false;

        /**
         * Command roots that must NEVER be forwarded, whatever their target resolves to.
         *
         * <p>These are positional, teleport or routing commands: forwarding {@code /tp Bob} is not "act on Bob over
         * there", it is a shard hop, which cross-server {@code /tpa} and {@code /server} already do properly.
         * {@code execute} and {@code doas} are here because they can launder a different command or identity past the
         * gate. This is a DENY list on purpose, because the requirement is that every OTHER player targeting command
         * inherits forwarding; it is the one hand maintained list in this feature and the place to add anything that
         * turns out to move a player rather than act on them. It is checked against the first command token, lower
         * cased.
         */
        public List<String> commandForwardDenyList = new ArrayList<>(List.of(
                "tp", "teleport", "tphere", "tphereall", "tpa", "tpahere", "tpaccept", "tpdeny", "tpo", "tpohere",
                "tpall", "spawn", "server", "back", "home", "warp", "top", "bottom", "execute", "doas"));

        /**
         * Whether the social spy feed crosses servers.
         *
         * <p>On by default, because a spy on one shard being blind to the other is most of the point of having
         * the feature on a network at all.
         *
         * <p>It has a cost worth knowing about: the feed carries PRIVATE MESSAGES, so leaving this on means
         * private message text is written to the shared chat table and lives there until the ordinary chat prune
         * removes it. Public chat has always been written there; private messages have not. Turn this off to keep
         * spying local, in which case nothing private ever leaves the server it was said on and a spy only sees
         * conversations happening beside them. Staff and admin chat are unaffected either way.
         */
        public boolean spyAcrossServers = true;

        /**
         * Make one authoritative dragon ball set per type across the whole network, instead of each shard holding its
         * own independent copy.
         *
         * <p>ON by default. It is deliberately safe to default on because the whole feature is INERT unless a set's
         * home dimension is hosted by more than one shard, i.e. a twin exists ({@code ghostPeers} non-empty). A single
         * server, singleplayer, LAN, or the SMP (which has no twin) all resolve an empty managed set and the authority
         * does nothing, deletes nothing and mirrors nothing. Enforcement of the "one set" invariant must not hinge on
         * an operator remembering to flip a switch, so the flag defaults to the enforcing state and only bites where a
         * twin actually duplicates a set. When it does bite: balls in a dimension hosted by more than one shard (today
         * only {@code minecraft:overworld}, carried by the two open world twins) are mirrored from a single shared
         * record, the same ball exists at the same coordinates on both twins, and collecting it on one removes it on
         * the other. A shard that does not host a set's home dimension (the SMP for earth) holds none of that set's
         * balls and never scatters it.
         *
         * <p>This gates the cross-shard authority AND the periodic delete sweep AND the one shot 1.0 rescatter
         * (see {@code DragonBallOneShot}). With it off, DragonMineZ behaves
         * exactly as it does today and the radar keeps its current cross-shard view
         * ({@link net.shurui.shuruisutilities.compat.dmz.CrossShardRadar}), so a disabled feature can never make the
         * radar disagree with the world.
         */
        public boolean dragonBallAuthority = true;

        /**
         * A deliberate, one-shot trigger for the destructive reconcile that collapses the currently divergent per-shard
         * ball state into one network set.
         *
         * <p>OFF by default. Set it true ONLY when you have decided the moment is right: on the next boot with
         * {@link #dragonBallAuthority} also on, each twin-hosted set (earth, cerulean) is cleared on every shard that
         * hosts its home dimension (its loose ball BLOCKS removed with no drop), the stale copy on a shard that does
         * NOT host the home dimension (the SMP's leftover earth stars) is wiped from its saved data, and one elected
         * shard scatters a single fresh set that both twins then mirror. It is guarded by a run-exactly-once marker in
         * the shared database, so leaving this true does not repeat the wipe on later boots; it is safe (and tidy) to
         * set it back to false afterwards. Every removal and every fresh scatter is logged.
         *
         * <p>It is a separate switch from {@link #dragonBallAuthority} on purpose: turning the feature on must not, by
         * itself, delete blocks from a live world. The wipe happens only when this specific key says so.
         */
        public boolean dragonBallReconcile = false;

        /**
         * Publish live player positions to {@code <prefix>map_players} so the public website map can draw who is where
         * on the open worlds. See {@code ShardMapPositions}.
         *
         * <p>ON by default, but it only ever writes on a shard whose {@link #role} is {@code OPENWORLD} and whose shard
         * layer is {@link #enabled}: the SMP and the hub publish nothing whatever this says, because the two open world
         * twins are the only shards whose terrain the shared map is drawn against. Turn this off to keep an open world
         * shard off the public map without disabling the rest of the shard layer.
         */
        public boolean mapPublishEnabled = true;

        /**
         * How often, in seconds, the open world shards push one batched position upsert. Rounded up to at least one
         * second. Five is smooth on a web map that interpolates and cheap on the database (one statement per shard per
         * interval, plus two small cleanup deletes).
         */
        public int mapPublishIntervalSeconds = 5;

        /**
         * The only dimensions whose players are published to the map. A player anywhere else is never written and is
         * removed promptly when they cross into an unlisted dimension. Defaults to the three open world dimensions the
         * public map draws: the overworld and the two DMZ otherworlds.
         */
        public List<String> mapDimensions = new ArrayList<>(List.of(
                "minecraft:overworld", "dmz_ragnarok:namekow", "dmz_ragnarok:kaiow"));
    }

    private static Values values = new Values();
    private static boolean loaded;

    public static Values get()
    {
        if (!loaded)
            load();
        return values;
    }

    /**
     * This server's id when the shard network is actually live, and null otherwise.
     *
     * <h2>Why null rather than the configured id</h2>
     * {@link Values#serverId} defaults to {@code "smp"}, so reading it blind on singleplayer, on a LAN world or on
     * any ordinary unsharded server answers "smp" for a server that is not part of a network at all. Anything that
     * RECORDS which server a position was on has to be able to tell those apart: a stored "smp" would later be read
     * as "that position is on the SMP shard, hand the player over", which is exactly how a single server copy of a
     * network world would start routing people at a network that is not there. Null means "this server, whichever
     * one that is", which is the only honest answer off a network.
     *
     * <p>{@link ShardSync#active()} rather than {@code enabled} alone, so a keyless server (where the shard system
     * is configured but never runs) also answers null.
     */
    public static String selfId()
    {
        if (!ShardSync.active())
            return null;
        String id = get().serverId;
        return id == null || id.isBlank() ? null : id;
    }

    public static Role role()
    {
        try
        {
            return Role.valueOf(get().role.trim().toUpperCase(Locale.ROOT));
        }
        catch (Exception e)
        {
            return Role.SMP;
        }
    }

    /** Table name for a logical table, with the configured prefix applied. */
    public static String table(String name)
    {
        return get().tablePrefix + name;
    }

    private static File file()
    {
        return new File(ShuruisUtilities.getSUDirectory(), "shard.json");
    }

    public static synchronized void load()
    {
        loaded = true;
        File f = file();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try
        {
            if (!f.isFile())
            {
                f.getParentFile().mkdirs();
                Files.write(f.toPath(), gson.toJson(new Values()).getBytes(StandardCharsets.UTF_8));
                LoggingHandler.sulog.info(
                        "[shard] No shard.json; wrote a disabled default at {}. Multi server sync is OFF.",
                        f.getAbsolutePath());
                values = new Values();
                return;
            }
            Values read = gson.fromJson(Files.readString(f.toPath()), Values.class);
            values = read == null ? new Values() : read;
            if (values.enabled)
            {
                // The password is never logged, here or anywhere else. Keyless, shard.json can still say enabled,
                // but no engine will ever connect, so say so rather than read like a live network member. The key
                // installs its hook during mod construction, well before the first read of this config.
                LoggingHandler.sulog.info("[shard] Enabled as '{}' (role {}), vault at {}{}.",
                        values.serverId, role(), safeUrl(values.jdbcUrl),
                        ShardHooks.available() ? "" : " (inactive: no Ragnarok Key)");
            }
        }
        catch (IOException | RuntimeException e)
        {
            // A broken config must not start a server that thinks it is alone when it is not, nor one that
            // half joins a network. Disabled is the only safe reading of "I could not tell".
            values = new Values();
            LoggingHandler.sulog.error("[shard] Could not read {}: {}. Multi server sync is OFF.",
                    f.getAbsolutePath(), e.toString());
        }
    }

    /** A url with any embedded credentials stripped, so it can be logged. */
    private static String safeUrl(String url)
    {
        if (url == null)
            return "";
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
