package net.shurui.shuruisutilities.energy;

/**
 * The role title ids that grant an energy bar and the abilities on it.
 *
 * <p>These are the ids used in the tournaments {@code titles.definitions} config and in {@code title:<id>} rewards,
 * so they are PERSISTED VALUES: a stored holder row is keyed by this exact string. Renaming one orphans whoever
 * holds it (the holder survives in world data but stops resolving), so treat these as frozen.
 */
public final class RoleTitles
{
    private RoleTitles() {}

    /** God of Destruction, abbreviated G.O.D. in display text. */
    public static final String GOD_OF_DESTRUCTION = "god_of_destruction";

    /** Angel. */
    public static final String ANGEL = "angel";

    /**
     * Grand Zeno. Carries the God of Destruction AND the Angel movesets at once.
     *
     * <p>It is one title rather than a second grant of the other two because a holder row is keyed by title id and
     * {@code TitleManager.heldTitle} returns a SINGLE id, so "holds both" is not something the title system can
     * express. The two predicates below are how that is expressed instead, and they are the only place the
     * combination is written down: gate on them, never on {@code equals(GOD_OF_DESTRUCTION)} directly, or a Zeno
     * silently loses half their abilities.
     */
    public static final String GRAND_ZENO = "grand_zeno";

    /** True when this title id carries the God of Destruction abilities. */
    public static boolean grantsDestruction(String titleId)
    {
        return GOD_OF_DESTRUCTION.equals(titleId) || GRAND_ZENO.equals(titleId);
    }

    /** True when this title id carries the Angel abilities. */
    public static boolean grantsAngelic(String titleId)
    {
        return ANGEL.equals(titleId) || GRAND_ZENO.equals(titleId);
    }
}
