package net.shurui.shuruisutilities.cosmetics.argent;

/**
 * What one balance operation actually did.
 *
 * <p>Deliberately not a boolean. Every caller of this system is moving money somebody paid for, and the four
 * answers below are four different things to DO: an applied grant is done, a duplicate must be reported and
 * dropped without a second credit, an insufficient balance must leave the goods unsold, and an unavailable store
 * must be retried rather than treated as a refusal. A boolean collapses the last two into each other, which is
 * the mistake that hands a cosmetic out for free when the database is down.
 *
 * <p>See {@code ArgentCurrency} (Ragnarok Key) for why this system is called "argent" internally and "Shards" to players.
 */
public final class ArgentResult
{
    public enum Status
    {
        /** The balance moved and the change is durable. */
        APPLIED,
        /**
         * The idempotency key had already been applied. NOTHING changed, deliberately. This is the answer a
         * replayed Tebex delivery gets, and it is a success from the store's point of view: the player already
         * has the shards from the first delivery.
         */
        DUPLICATE,
        /** The player could not cover the amount. Nothing was taken. */
        INSUFFICIENT,
        /** The request itself was rejected (bad amount, missing key, module off). Nothing was attempted. */
        REFUSED,
        /**
         * The store could not be reached or the write failed. NOTHING was applied, and the caller must treat
         * this as "try again later", never as a refusal. A keyed operation is safe to retry by definition.
         */
        UNAVAILABLE
    }

    public final Status status;

    /** How much actually moved, as a signed figure. Positive is a credit, negative is a debit. Zero on failure. */
    public final long applied;

    /** The balance afterwards, or -1 when it could not be established. */
    public final long balance;

    /**
     * Requested minus applied, always zero or more.
     *
     * <p>Only ever non-zero on a clamped debit: a chargeback for 500 against a player who has 200 left takes the
     * 200 and reports 300 short, rather than driving the balance negative or refusing to act at all. The owner
     * sees the shortfall in the audit and can decide what to do about the rest.
     */
    public final long shortfall;

    private ArgentResult(Status status, long applied, long balance, long shortfall)
    {
        this.status = status;
        this.applied = applied;
        this.balance = balance;
        this.shortfall = Math.max(0L, shortfall);
    }

    public static ArgentResult applied(long applied, long balance, long shortfall)
    {
        return new ArgentResult(Status.APPLIED, applied, balance, shortfall);
    }

    public static ArgentResult duplicate(long balance)
    {
        return new ArgentResult(Status.DUPLICATE, 0L, balance, 0L);
    }

    public static ArgentResult insufficient(long balance)
    {
        return new ArgentResult(Status.INSUFFICIENT, 0L, balance, 0L);
    }

    public static ArgentResult refused()
    {
        return new ArgentResult(Status.REFUSED, 0L, -1L, 0L);
    }

    public static ArgentResult unavailable()
    {
        return new ArgentResult(Status.UNAVAILABLE, 0L, -1L, 0L);
    }

    public boolean ok()
    {
        return status == Status.APPLIED;
    }

    /** True when the caller may safely try the same keyed operation again. */
    public boolean retryable()
    {
        return status == Status.UNAVAILABLE;
    }

    @Override
    public String toString()
    {
        return status + "(applied=" + applied + ", balance=" + balance + ", short=" + shortfall + ")";
    }
}
