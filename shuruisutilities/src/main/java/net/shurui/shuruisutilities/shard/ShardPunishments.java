package net.shurui.shuruisutilities.shard;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import net.shurui.shuruisutilities.api.key.ShardHooks;
import net.shurui.shuruisutilities.audit.AuditLog;

/**
 * The one place an in-game punishment is written to the shared network database, so the staff-application web portal
 * can show a player's full punishment history by in-game name across every shard.
 *
 * <h2>What this is, and what it is not</h2>
 * This is a moderation AUDIT sink, the database twin of {@link AuditLog}. Every hook that runs a punishment
 * (mute, jail, ban, kick, freeze, warn and their reversals) calls {@link #record} once on SUCCESS, and this both
 * writes the {@code [audit]} line to the server log and appends a row to {@code <prefix>punishments}. The two always
 * go together, so a punishment is never in one place and missing from the other.
 *
 * <h2>What is key gated (Sh1)</h2>
 * The {@code [audit]} server log line is NOT: exactly the reasoning of {@link AuditLog}, a server that cannot answer
 * "who punished whom, when and why" cannot be moderated, whatever key tier it runs, so it is written here, in core,
 * on every server. The shared DATABASE half (the row, and the {@code /history} read) is the network's, so it is the
 * key's ({@code ShardPunishmentStore} behind {@link ShardHooks}), and inside it the gate is still only whether the
 * shard database is configured ({@link ShardConfig.Values#enabled}). Keyless, on a singleplayer world, a LAN game or
 * any server with the shard layer switched off, the database half is a silent no-op and the log line is still
 * written.
 *
 * <h2>Timestamps</h2>
 * {@code created_at} defaults to the DATABASE clock ({@code CURRENT_TIMESTAMP(3)}) and {@code expires_at} is computed
 * as {@code NOW(3) + duration} inside the INSERT, never from this server's own wall clock, because the shard hosts'
 * clocks run hours apart and a stamp from the publishing server would be arbitrated against a different reader's
 * clock. See the shard clock rule.
 *
 * <h2>Threading</h2>
 * Every database touch runs off the tick on {@link ShardExecutor#submit(Runnable)} (the single global shard thread),
 * so a punishment command never blocks the server thread on a remote query. The {@code [audit]} log line is written
 * synchronously first, so the server log has it even if the database write later fails.
 */
public final class ShardPunishments
{
    private ShardPunishments() {}

    /** target_uuid is NOT NULL in the contract; an IP ban and any other target with no player UUID uses this. */
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    // Canonical action names. The web portal is coded against exactly these strings, so do not rename them.
    public static final String MUTE = "MUTE";
    public static final String TEMPMUTE = "TEMPMUTE";
    public static final String UNMUTE = "UNMUTE";
    public static final String JAIL = "JAIL";
    public static final String UNJAIL = "UNJAIL";
    public static final String KICK = "KICK";
    public static final String BAN = "BAN";
    public static final String TEMPBAN = "TEMPBAN";
    public static final String UNBAN = "UNBAN";
    public static final String IPBAN = "IPBAN";
    public static final String UNBANIP = "UNBANIP";
    public static final String FREEZE = "FREEZE";
    public static final String UNFREEZE = "UNFREEZE";
    public static final String WARN = "WARN";

    /** Whether the shared database is configured, so callers can tell an empty history from a database that is off. */
    public static boolean available()
    {
        return ShardHooks.get().punishmentsAvailable();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Entry points. Every hook is one line.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Record a punishment whose issuer is a command source (a player, the console, a command block, or rcon).
     *
     * @param src             the command source that ran the action; the issuer is read from it
     * @param action          one of the canonical action constants on this class
     * @param targetId        the target's UUID, dashed lowercase in the row; {@code null} becomes the nil UUID
     * @param targetName      the target's in-game name at the time of the action
     * @param reason          the reason as given, or {@code null} for none
     * @param durationSeconds the timed length in seconds, or {@code <= 0} for an untimed action
     */
    public static void record(CommandSourceStack src, String action, UUID targetId, String targetName, String reason,
            long durationSeconds)
    {
        UUID issuerId = null;
        String issuerName = "Console";
        if (src != null)
        {
            ServerPlayer p = src.getPlayer();
            if (p != null)
            {
                issuerId = p.getUUID();
                issuerName = p.getGameProfile().getName();
            }
            else
            {
                issuerName = sourceName(src);
            }
        }
        record(action, targetId, targetName, reason, durationSeconds, issuerId, issuerName);
    }

    /**
     * Record a punishment with a fully resolved issuer. Use this from an automated or non-command path; pass a
     * {@code null} issuerId for the console or a system source, and name that source in {@code issuerName}.
     */
    public static void record(String action, UUID targetId, String targetName, String reason, long durationSeconds,
            UUID issuerId, String issuerName)
    {
        final UUID tId = targetId == null ? NIL_UUID : targetId;
        final String tName = clip(targetName == null ? "?" : targetName, 32);
        final String act = clip(action == null ? "?" : action, 16);
        final String rsn = reason == null || reason.isBlank() ? null : clip(reason, 512);
        final long dur = durationSeconds > 0 ? durationSeconds : 0L;
        final UUID iId = issuerId;
        final String iName = clip(issuerName == null || issuerName.isBlank() ? "Console" : issuerName, 64);

        // Server log first, so the trail exists even without a database and even if the write below fails.
        AuditLog.log("{} {} {}{}{}", iName, act, tName,
                rsn == null ? "" : " (reason: " + rsn + ")",
                dur > 0 ? " for " + dur + "s" : "");

        // The database row is the key's (keyless: no row, the log line above is the whole record).
        ShardHooks.get().recordPunishmentRow(act, tId, tName, rsn, dur, iId, iName, clip(safeServerId(), 32));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Read side, for the /history command.
    // ---------------------------------------------------------------------------------------------------------------

    /** One punishment row, as the /history command prints it. */
    public static final class Entry
    {
        public String action;
        public String targetName;
        public String reason;
        public long durationSeconds; // 0 when untimed
        public Timestamp expiresAt;  // null when untimed
        public String issuerName;
        public String shard;
        public Timestamp createdAt;
    }

    /**
     * Read the most recent {@code limit} punishment rows for a player, off the tick, then hand them back on the
     * caller's thread of choice. Matches on the target UUID when one is known, and always on the target name (so a
     * renamed player and history recorded under either key both show). Degrades to an empty list when the shard
     * database is off.
     */
    public static void history(UUID targetId, String targetName, int limit, Consumer<List<Entry>> onDone,
            Consumer<Throwable> onError)
    {
        history(targetId, targetName, limit, 0, onDone, onError);
    }

    /** As {@link #history(UUID, String, int, Consumer, Consumer)}, newest first, skipping {@code offset} rows (paging). */
    public static void history(UUID targetId, String targetName, int limit, int offset, Consumer<List<Entry>> onDone,
            Consumer<Throwable> onError)
    {
        ShardHooks.get().punishmentHistory(targetId, targetName, limit, offset, onDone, onError);
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

    /** Name a non-player source: a command block or named entity by its name, the console as "Console". */
    private static String sourceName(CommandSourceStack src)
    {
        Entity e = src.getEntity();
        if (e != null)
            return e.getName().getString();
        String tn = src.getTextName();
        if (tn == null || tn.isBlank() || "Server".equals(tn))
            return "Console";
        return tn;
    }

    private static String clip(String s, int max)
    {
        if (s == null)
            return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
