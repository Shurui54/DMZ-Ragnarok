package net.shurui.shuruisutilities.cosmetics.wardrobe;

/**
 * Every way a cosmetic could leave the account that owns it.
 *
 * <p>The list is deliberately longer than what is built: naming a route that does not exist yet costs one enum
 * constant, while FORGETTING one later costs a hole through which a bound cosmetic escapes. The event coverage
 * mirrors what {@code ModuleBanItem} already guards for banned items, which is the most complete "an item must
 * not move" net in the suite; this does not reuse that class (a cosmetic is not banned, and an admin clearing
 * the ban list would open the hole) but it does copy its list so no route is missed.
 *
 * <p>Nothing in milestone 1 mints a token, so today every route is asked and every route refuses a bound
 * cosmetic vacuously. That is the point: the chokepoint exists BEFORE the first thing that could move through
 * it, so no later milestone has to remember to add it.
 */
public enum TransferRoute
{
    /**
     * Creating the item form of a cosmetic in the first place.
     *
     * <p>The primary defence, and the cheapest: a bound cosmetic never becomes an item at all, so none of the
     * routes below ever see one. Everything else is the belt to this brace.
     */
    MINT("mint"),

    /** Player to player trade. */
    TRADE("trade"),

    /** Listing on the auction house. */
    AUCTION_LIST("auction"),

    /** Attaching to mail. */
    MAIL("mail"),

    /** Dropping on the ground by hand. */
    DROP("drop"),

    /** Dropped on death. */
    DEATH_DROP("death_drop"),

    /** Put into any container, including an ender chest or a backpack. */
    CONTAINER("container"),

    /** Handed over by a command or an NPC gift. */
    GIFT_COMMAND("gift");

    /** Stable lowercase key, for a log line and a message key. Not persisted anywhere, but kept stable anyway. */
    public final String key;

    TransferRoute(String key)
    {
        this.key = key;
    }

    public String langKey()
    {
        return "gui.dmz_ragnarok.cosmetics.route." + key;
    }
}
