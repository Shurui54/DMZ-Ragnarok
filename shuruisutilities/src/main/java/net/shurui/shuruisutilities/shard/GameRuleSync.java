package net.shurui.shuruisutilities.shard;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Cross-shard game rule sync. Every registered game rule (vanilla and modded, boolean and integer) travels as a single
 * whole-record entry through {@link ShardStateSync}, so a {@code /gamerule} change (or any code path that sets a
 * {@link GameRules.Value}) made on one shard takes effect on every other shard live, without a restart.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Game rules live in each world's {@code level.dat} and nothing syncs them, so on a network that is meant to behave
 * as one server they drift: {@code allowKiGriefingMobs}, {@code allowKiGriefingPlayers} and {@code terrainRegen} had
 * gone {@code false} on OW1 while they were {@code true} on main and OW2. This carries the whole rule set so an edit
 * anywhere reaches everywhere.
 *
 * <h2>Shape of the record</h2>
 *
 * <p>One entry, {@link #STATE_KEY}, holding a {@link CompoundTag} of rule name to serialized value string. The name is
 * the rule's {@link GameRules.Key#getId() id} and the value is exactly what the game itself writes to {@code level.dat}
 * ({@code "true"}/{@code "false"} for a boolean, the decimal for an integer), so nothing has to know a rule's type to
 * carry it. Whole-record last-write-wins, the same trade the other {@code su:cfg_*} knobs make: two admins editing
 * different rules inside one poll window keep the later edit, which is fine for deliberate admin work.
 *
 * <h2>Applying a record</h2>
 *
 * <p>Each rule is set through its proper API ({@link GameRules.BooleanValue#set} / {@link GameRules.IntegerValue#set},
 * both on the server thread) so the rule's change callback fires exactly as {@code /gamerule} would (DMZ's ki-griefing
 * and terrain-regen rules hang callbacks off this). A rule already holding the incoming value is left untouched so no
 * callback fires needlessly. A name this shard does not have as a live rule, or a value that does not parse, is not
 * applied.
 *
 * <h2>Echo and ping-pong</h2>
 *
 * <p>{@link ShardStateSync} already suppresses the echo of an applied change: {@code writeOne} records the incoming
 * hash, and the next read hashes to the same bytes, so nothing is republished. That holds here because {@link #read}
 * is deterministic (the rule set is visited in id order and the values are serialized the game's own way) and because
 * a rule name this shard does not own is carried through {@link #PASSTHROUGH} rather than dropped: a shard therefore
 * re-serializes the exact record it received, so two shards converge in one pass and never fight over a rule one of
 * them lacks (a mixed-version rollout window, say). {@code PASSTHROUGH} never applies anything, it only relays.
 *
 * <h2>Boot</h2>
 *
 * <p>Seeding is {@link ShardStateSync}'s: the first shard to boot into an empty network seeds {@link #STATE_KEY} from
 * its own {@code level.dat} rules; a shard booting into a network that already holds the row does NOT publish its own
 * and instead adopts the network row on the first poll. So the network record is the authority and a freshly booted
 * shard takes the network's rules over its local file.
 *
 * <h2>Inert off the network</h2>
 *
 * <p>Nothing here runs on its own: it is only reached through {@link ShardStateSync}'s poll, which is gated on
 * {@link ShardSync#active()}. On a single server the entry is registered and never touched.
 */
public final class GameRuleSync
{
    private GameRuleSync() {}

    /** The state-sync entry key. Renaming it orphans the row, the same as renaming a persisted class orphans its folder. */
    public static final String STATE_KEY = "su:gamerules";

    /**
     * Rules deliberately kept local, by id. Empty: the shard worlds are copies, so even spawn-position rules
     * ({@code spawnRadius}) make sense to share. Add an id here only for a rule that genuinely cannot cross shards.
     */
    private static final Set<String> EXCLUDED = Set.of();

    /**
     * Rule names that arrived in a record but are not live rules on this shard, kept so the record is relayed intact
     * rather than silently shrunk. Nothing here is ever applied; it only preserves another shard's (or another jar
     * version's) rule so this shard does not wipe it back off the network. Concurrent: written on the server thread by
     * {@link #write} and read on the server thread by {@link #read}, but kept concurrent for safety.
     */
    private static final java.util.Map<String, String> PASSTHROUGH = new ConcurrentHashMap<>();

    /** Read the current rule set into a name to value tag. Deterministic: rules are visited in id order. */
    public static CompoundTag read(MinecraftServer server)
    {
        if (server == null)
        {
            return null;
        }
        GameRules rules = server.getGameRules();
        CompoundTag tag = new CompoundTag();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor()
        {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type)
            {
                String id = key.getId();
                if (EXCLUDED.contains(id))
                {
                    return;
                }
                tag.putString(id, rules.getRule(key).serialize());
            }
        });
        // Carry through anything this shard does not have as a live rule, so it is not stripped from the network.
        for (java.util.Map.Entry<String, String> pass : PASSTHROUGH.entrySet())
        {
            if (!EXCLUDED.contains(pass.getKey()) && !tag.contains(pass.getKey(), Tag.TAG_STRING))
            {
                tag.putString(pass.getKey(), pass.getValue());
            }
        }
        return tag;
    }

    /** Apply a received rule set into the live rules, firing each rule's callback. Runs on the server thread. */
    public static void write(MinecraftServer server, CompoundTag tag)
    {
        if (server == null || tag == null)
        {
            return;
        }
        GameRules rules = server.getGameRules();
        Set<String> handled = new java.util.HashSet<>();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor()
        {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type)
            {
                String id = key.getId();
                if (EXCLUDED.contains(id) || !tag.contains(id, Tag.TAG_STRING))
                {
                    return;
                }
                handled.add(id);
                applyOne(rules.getRule(key), id, tag.getString(id), server);
            }
        });
        // A name we did not apply is one this shard has no live rule for: relay it, do not apply it. Anything we DID
        // apply is a live rule now, so drop any stale passthrough copy of it.
        for (String name : tag.getAllKeys())
        {
            if (EXCLUDED.contains(name))
            {
                continue;
            }
            if (handled.contains(name))
            {
                PASSTHROUGH.remove(name);
            }
            else
            {
                PASSTHROUGH.put(name, tag.getString(name));
            }
        }
    }

    /** Set one rule from its string value, only when it differs, so a callback fires only on a real change. */
    private static void applyOne(GameRules.Value<?> value, String id, String raw, MinecraftServer server)
    {
        if (value instanceof GameRules.BooleanValue bool)
        {
            if (!"true".equals(raw) && !"false".equals(raw))
            {
                LoggingHandler.sulog.warn("[shard] Ignoring unparseable boolean game rule '{}' = '{}'.", id, raw);
                return;
            }
            boolean next = Boolean.parseBoolean(raw);
            if (bool.get() != next)
            {
                bool.set(next, server);
            }
        }
        else if (value instanceof GameRules.IntegerValue integer)
        {
            int next;
            try
            {
                next = Integer.parseInt(raw.trim());
            }
            catch (NumberFormatException ex)
            {
                LoggingHandler.sulog.warn("[shard] Ignoring unparseable integer game rule '{}' = '{}'.", id, raw);
                return;
            }
            if (integer.get() != next)
            {
                integer.set(next, server);
            }
        }
        // Any other value type (none exist in vanilla or the mod's rules) is left untouched.
    }
}
