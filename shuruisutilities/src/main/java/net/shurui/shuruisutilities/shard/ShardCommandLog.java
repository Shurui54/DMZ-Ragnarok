package net.shurui.shuruisutilities.shard;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;

import com.mojang.brigadier.tree.CommandNode;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.key.ShardHooks;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.api.permissions.GroupEntry;
import net.shurui.shuruisutilities.audit.AuditLog;
import net.shurui.shuruisutilities.staff.StaffRole;
import net.shurui.shuruisutilities.staff.StaffRoster;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The one place a command run on this server is written to the shared network database, so an admin can ask, with
 * {@code /commandlookup}, what any staff member last did across every shard.
 *
 * <h2>What is captured, and what is not</h2>
 * A command is logged when it was run by a PLAYER who is staff, when its root is a staff/admin command whoever runs
 * it, and always when it came from the CONSOLE or over RCON. Command blocks are deliberately skipped: they are far
 * too noisy to be a moderation signal. See {@link #onCommand}. Secrets are redacted at the door: any auth command
 * ({@code login}, {@code register}, {@code changepassword}, and their common aliases) has its arguments replaced with
 * {@code [redacted]} before the row is ever built, so a password never reaches the database or the {@code [audit]}
 * log line.
 *
 * <h2>What is key gated (Sh1)</h2>
 * The capture and the {@code [audit]} log line are NOT: exactly the reasoning of {@link ShardPunishments} and
 * {@link AuditLog}, a server that cannot answer "who ran what" cannot be moderated, whatever key tier it runs. The
 * shared DATABASE half (the batched insert, {@code /commandlookup}, the daily retention prune) is the network's, so it
 * is the key's ({@code ShardCommandLogStore} behind {@link ShardHooks}), still gated inside on whether the shard
 * database is configured ({@link ShardConfig.Values#enabled}). Keyless, on singleplayer, LAN, or any server with the
 * shard layer off, the database half is a silent no-op and the {@code [audit]} log line is still written.
 *
 * <h2>Timestamps</h2>
 * {@code created_at} defaults to the DATABASE clock ({@code CURRENT_TIMESTAMP(3)}), never this server's wall clock,
 * because the shard hosts' clocks run hours apart. See the shard clock rule.
 *
 * <h2>Threading</h2>
 * {@link #onCommand} runs on the SERVER THREAD (it is called from a mixin on {@code Commands.performPrefixedCommand}),
 * so it does only cheap in-memory work: classify the source, redact, snapshot a {@link Row}, write the {@code [audit]}
 * line, and hand the row to a queue. The database insert runs off the tick on {@link ShardExecutor#submit(Runnable)}
 * (the single global shard thread). Rows that land in the same tick are BATCHED into one statement on one connection,
 * so a spammy command macro cannot open a connection per command.
 */
public final class ShardCommandLog
{
    private ShardCommandLog() {}

    /** Command outcomes, best effort, from the return of {@code performPrefixedCommand} plus a couple of cheap probes. */
    public static final String OK = "OK";
    public static final String FAILED = "FAILED";
    public static final String NO_PERMISSION = "NO_PERMISSION";
    public static final String UNKNOWN_COMMAND = "UNKNOWN_COMMAND";

    private static final int MAX_COMMAND = 512;
    private static final int MAX_ROOT = 64;
    private static final int MAX_NAME = 64;

    /**
     * Command roots whose arguments are a secret. Their arguments are replaced with {@code [redacted]} before a row is
     * built, so a password never reaches the database or the audit log. Kept as a lower cased set, matched on the root.
     */
    private static final Set<String> REDACT_ROOTS = Set.of(
            "login", "register", "l", "reg", "changepassword", "changepass", "passwd", "2fa",
            // Common aliases from the auth mods this could sit beside.
            "unregister", "password", "pass", "authme", "auth");

    /** Whether the shared database is configured, so callers can tell an empty log from a database that is off. */
    public static boolean available()
    {
        return ShardHooks.get().commandLogAvailable();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Capture. Called on the server thread from MixinCommandsLogging, once per top-level command line.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Consider one executed command for the audit log. Never throws: a logging fault must never take a command down.
     *
     * @param src        the command source that ran it
     * @param rawCommand the command line as run, with or without a leading slash
     * @param result     the return of {@code performPrefixedCommand} (>0 succeeded, 0 did nothing or failed)
     */
    public static void onCommand(CommandSourceStack src, String rawCommand, int result)
    {
        try
        {
            capture(src, rawCommand, result);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[shard] Command log capture failed: {}", t.toString());
        }
    }

    private static void capture(CommandSourceStack src, String rawCommand, int result)
    {
        if (src == null || rawCommand == null)
            return;
        String full = rawCommand.trim();
        if (full.startsWith("/"))
            full = full.substring(1).trim();
        if (full.isEmpty())
            return;

        // Classify the issuer. Players and console/rcon are logged; command blocks and other machinery are not.
        UUID playerId = null;
        String playerName;
        boolean staff = false;
        boolean logIt;

        MinecraftServer server = src.getServer();
        if (src.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer))
        {
            GameProfile profile = player.getGameProfile();
            playerId = player.getUUID();
            playerName = profile.getName();
            staff = isStaff(playerId, profile, server);
            // Rule (a): the player is staff. Rule (b): the command root is a staff/admin command whoever runs it.
            logIt = staff || isStaffCommand(src, server, firstToken(full));
        }
        else
        {
            String issuer = consoleName(src);
            if (issuer == null)
                return; // a command block or other non-player, non-console source: too noisy, skip
            playerName = issuer;
            logIt = true; // every console and rcon command is logged
        }

        if (!logIt)
            return;

        String root = clip(firstToken(full).toLowerCase(Locale.ROOT), MAX_ROOT);
        String stored = clip(redact(full, root), MAX_COMMAND);
        String outcome = outcome(src, server, full, root, result);

        // Position, when the source has one. Console and rcon do not.
        String dim = null;
        Integer x = null, y = null, z = null;
        try
        {
            if (src.getLevel() != null)
                dim = src.getLevel().dimension().location().toString();
            Vec3 pos = src.getPosition();
            if (pos != null && src.getEntity() != null)
            {
                x = (int) Math.round(pos.x);
                y = (int) Math.round(pos.y);
                z = (int) Math.round(pos.z);
            }
        }
        catch (Throwable ignored)
        {
        }

        String shard = clip(safeServerId(), 32);

        // Server log first, so the trail exists even without a database and even if the write below fails.
        AuditLog.log("command {} ran /{}{}", playerName, stored,
                outcome == null ? "" : " (" + outcome + ")");

        // The database row is the key's (keyless: no row, the log line above is the whole record).
        ShardHooks.get().commandLogRow(new Row(playerId, clip(playerName, MAX_NAME), stored, root, staff, outcome, dim, x, y, z, shard));
    }

    /** The first whitespace-delimited token, never null (the caller guarantees a non-empty string). */
    private static String firstToken(String full)
    {
        int space = full.indexOf(' ');
        return space < 0 ? full : full.substring(0, space);
    }

    /** Replace a secret command's arguments with a marker, leaving the root visible. */
    private static String redact(String full, String root)
    {
        if (!REDACT_ROOTS.contains(root))
            return full;
        int space = full.indexOf(' ');
        return space < 0 ? full : full.substring(0, space) + " [redacted]";
    }

    /**
     * Rule (a): whether a player counts as staff. True if they have a staff roster entry, OR are a vanilla operator at
     * level 2 or above, OR belong to any SU permission group that is a staff role group. All cheap, in-memory checks.
     */
    private static boolean isStaff(UUID id, GameProfile profile, MinecraftServer server)
    {
        try
        {
            if (StaffRoster.isStaff(id))
                return true;
        }
        catch (Throwable ignored)
        {
        }
        try
        {
            if (server != null && profile != null)
            {
                ServerOpListEntry entry = server.getPlayerList().getOps().get(profile);
                if (entry != null && entry.getLevel() >= 2)
                    return true;
            }
        }
        catch (Throwable ignored)
        {
        }
        try
        {
            if (APIRegistry.perms != null)
            {
                for (GroupEntry e : APIRegistry.perms.getPlayerGroups(UserIdent.get(id)))
                    if (StaffRole.byGroup(e.getGroup()) != null)
                        return true;
            }
        }
        catch (Throwable ignored)
        {
        }
        return false;
    }

    /**
     * Rule (b): whether the command root is a staff/admin command whoever runs it. Answered cheaply by asking the
     * command node's own requirement predicate against a level-0 source: a command that a non-privileged source may
     * not run (every vanilla op command, and every SU command registered OP-by-default) fails that test.
     */
    private static boolean isStaffCommand(CommandSourceStack src, MinecraftServer server, String rootToken)
    {
        try
        {
            if (server == null)
                return false;
            CommandNode<CommandSourceStack> root =
                    server.getCommands().getDispatcher().getRoot().getChild(rootToken.toLowerCase(Locale.ROOT));
            if (root == null)
                root = server.getCommands().getDispatcher().getRoot().getChild(rootToken);
            if (root == null)
                return false;
            CommandSourceStack lowered = src.withPermission(0);
            return !root.getRequirement().test(lowered);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Best-effort outcome. A positive result means the command did something; zero means it did nothing, which for a
     * player is often a denied permission (our own gate) or an unknown command, both determinable cheaply here.
     */
    private static String outcome(CommandSourceStack src, MinecraftServer server, String full, String root, int result)
    {
        if (result > 0)
            return OK;
        try
        {
            // Unknown command: no such root in the dispatcher.
            if (server != null
                    && server.getCommands().getDispatcher().getRoot().getChild(root) == null
                    && server.getCommands().getDispatcher().getRoot().getChild(firstToken(full)) == null)
                return UNKNOWN_COMMAND;
            // Denied by SU permissions: the same question the execution guard asks, for a player only.
            if (src.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)
                    && APIRegistry.perms != null
                    && !net.shurui.shuruisutilities.commands.util.CommandExecutionGuard.isOperator(
                            player.getGameProfile(), server))
            {
                var parse = server.getCommands().getDispatcher().parse(full, src);
                if (net.shurui.shuruisutilities.commands.util.CommandExecutionGuard.firstDeniedNode(
                        parse, UserIdent.get(player), server) != null)
                    return NO_PERMISSION;
            }
        }
        catch (Throwable ignored)
        {
        }
        return FAILED;
    }

    /** The console label for a non-player source, or null if this source is not one we log (e.g. a command block). */
    private static String consoleName(CommandSourceStack src)
    {
        try
        {
            if (src.getEntity() != null)
                return null; // a named entity via /execute as, or similar: not console, not a player we log
            String tn = src.getTextName();
            if ("Rcon".equals(tn))
                return "RCON";
            if (tn == null || tn.isBlank() || "Server".equals(tn))
                return "Console";
            // Anything else with no entity (a command block's custom name, "@", ...) is skipped as too noisy.
            return null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The row handed to the database half.
    // ---------------------------------------------------------------------------------------------------------------

    /** One captured command, snapshotted on the server thread and written off the tick by the key's store. */
    public record Row(UUID playerId, String playerName, String command, String root, boolean staff, String outcome,
                       String dimension, Integer x, Integer y, Integer z, String shard)
    {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Read side, for /commandlookup.
    // ---------------------------------------------------------------------------------------------------------------

    /** One command log row, as {@code /commandlookup} prints it. */
    public static final class Entry
    {
        public String playerName;
        public String command;
        public String root;
        public boolean staff;
        public String outcome;
        public String dimension;
        public Integer x;
        public Integer y;
        public Integer z;
        public String shard;
        public Timestamp createdAt;
    }

    /**
     * Read the most recent command rows for a player, off the tick, newest first, then hand them back on the caller's
     * thread of choice. Matches on the player UUID when one is known, and always on the name (case-insensitively), so
     * an offline or renamed player still resolves. Degrades to an empty list when the shard database is off.
     */
    public static void lookup(UUID playerId, String playerName, int limit, int offset, Consumer<List<Entry>> onDone,
            Consumer<Throwable> onError)
    {
        ShardHooks.get().commandLookup(playerId, playerName, limit, offset, onDone, onError);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Small helpers.
    // ---------------------------------------------------------------------------------------------------------------

    private static String safeServerId()
    {
        try
        {
            return ShardConfig.get().serverId;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static String clip(String s, int max)
    {
        if (s == null)
            return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
