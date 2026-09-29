package net.shurui.shuruisutilities.cosmetics.argent;

import java.util.Locale;
import java.util.UUID;

/**
 * One line of the shard audit trail: who, how much, why, when, and which transaction it came from.
 *
 * <p>The owner has to be able to answer "where did these shards come from" months after the fact, so every mint,
 * every spend and every refund writes one of these. It is a plain-data record with an explicit, stable shape,
 * exactly like {@code CosmeticOwnership}, so it can be written to three places that outlive a process: a row in
 * the shared audit table, a line in a local append-only file, and a line in the server log under the suite's
 * {@code [audit]} prefix.
 *
 * <p>Nothing here is ever CONSULTED by a gate. It is evidence, not state. The balance and the applied-transaction
 * set are the state; this exists so a human can reconstruct how they got that way.
 */
public final class ArgentEntry
{
    /** Categories. Short, fixed, and written verbatim into the store, so do not rename them casually. */
    public static final String KIND_GRANT = "GRANT";

    /** An operator or a store chargeback taking shards back. A keyed debit, clamped at zero. */
    public static final String KIND_TAKE = "TAKE";

    /** A player spending shards in game. Not keyed: a live guarded debit, not a replayable delivery. */
    public static final String KIND_SPEND = "SPEND";

    /** Putting a spend back because whatever it bought could not be delivered. */
    public static final String KIND_REFUND = "REFUND";

    /** When it happened, epoch millis, from the applying server's clock. */
    public final long at;

    /** Whose balance moved. */
    public final UUID player;

    /** Signed. Positive is a mint or a refund, negative is a spend or a take. What ACTUALLY moved, not what was asked for. */
    public final long delta;

    /** The balance immediately afterwards, or -1 when it could not be established. */
    public final long balanceAfter;

    /** One of the {@code KIND_} constants above. */
    public final String kind;

    /**
     * The originating transaction, or blank for an unkeyed in-game spend.
     *
     * <p>For a store purchase this is the key Tebex was configured to pass through, which is the same string the
     * idempotency table holds, so a line here can always be tied back to the exact delivery that caused it.
     */
    public final String txn;

    /** Free text: the package name, what was bought, why an operator adjusted somebody. */
    public final String reason;

    /** Who caused it: {@code "tebex"}, {@code "console"}, {@code "admin:<name>"}, {@code "system"}. */
    public final String actor;

    /** Which server applied it, so a network-wide trail still says where a change was made. */
    public final String serverId;

    public ArgentEntry(long at, UUID player, long delta, long balanceAfter, String kind, String txn, String reason,
            String actor, String serverId)
    {
        this.at = at;
        this.player = player;
        this.delta = delta;
        this.balanceAfter = balanceAfter;
        this.kind = clean(kind, 16, "OTHER");
        this.txn = clean(txn, 190, "");
        this.reason = clean(reason, 255, "");
        this.actor = clean(actor, 64, "unknown");
        this.serverId = clean(serverId, 64, "local");
    }

    /**
     * Trim, cap and strip anything that would break a single-line log or a column width.
     *
     * <p>Every one of these strings can come from a command argument, so newlines and tabs are removed rather
     * than trusted: one audit line that spans two lines is one audit line a grep will misread.
     */
    private static String clean(String raw, int max, String fallback)
    {
        if (raw == null)
            return fallback;
        String s = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (s.isEmpty())
            return fallback;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * One JSON object on one line, for the append-only file.
     *
     * <p>Hand written rather than handed to Gson, because the suite's Gson is configured for its own data classes
     * and has a documented habit of walking into Minecraft types. There is nothing here but longs and short
     * strings, so escaping quotes and backslashes is the whole job.
     */
    public String toJsonLine()
    {
        StringBuilder b = new StringBuilder(220);
        b.append('{');
        b.append("\"at\":").append(at);
        b.append(",\"player\":\"").append(player == null ? "" : player.toString()).append('"');
        b.append(",\"delta\":").append(delta);
        b.append(",\"balanceAfter\":").append(balanceAfter);
        b.append(",\"kind\":\"").append(escape(kind)).append('"');
        b.append(",\"txn\":\"").append(escape(txn)).append('"');
        b.append(",\"reason\":\"").append(escape(reason)).append('"');
        b.append(",\"actor\":\"").append(escape(actor)).append('"');
        b.append(",\"server\":\"").append(escape(serverId)).append('"');
        b.append('}');
        return b.toString();
    }

    private static String escape(String s)
    {
        if (s == null || s.isEmpty())
            return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '"' || c == '\\')
                b.append('\\').append(c);
            else if (c < 0x20)
                b.append(' ');
            else
                b.append(c);
        }
        return b.toString();
    }

    /** A readable one-liner for the server log and for {@code /shards history}. */
    @Override
    public String toString()
    {
        return String.format(Locale.ROOT, "%s %+d (to %d) player=%s txn=%s actor=%s server=%s reason=%s", kind,
                delta, balanceAfter, player, txn.isEmpty() ? "-" : txn, actor, serverId,
                reason.isEmpty() ? "-" : reason);
    }
}
