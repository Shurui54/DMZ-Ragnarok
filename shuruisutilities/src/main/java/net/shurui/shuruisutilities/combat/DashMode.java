package net.shurui.shuruisutilities.combat;

/**
 * Which shape of dash the player asked for, chosen by the movement key held when the dash is triggered.
 *
 * <p>One key, one place you end up. Forward or nothing goes straight into them; left and right put you beside them on
 * that side; back takes you over the top and drops you behind. The modes exist so a dash is a decision rather than a
 * button: only the straight one actually hits, so choosing to flank is choosing position over damage, and only the
 * straight one can meet another dash head on, so a clash is something both players opted into.
 *
 * <p>Where each mode ENDS is {@link DashPath#destination}; how it gets there is {@link DashPath#control}. This enum is
 * only the label, so the client and the server cannot disagree about what the player asked for.
 */
public enum DashMode
{
    /** W, or no movement key. Straight into them, and the only mode that lands a hit or produces a clash. */
    STRAIGHT,

    /** A. Swings out to the left and finishes on their left flank. */
    LEFT_ARC,

    /** D. Swings out to the right and finishes on their right flank. */
    RIGHT_ARC,

    /** S. Climbs over the top of them and comes down behind, facing their back. */
    OVER_TOP;

    /**
     * Whether reaching the target ENDS the dash. Every mode hits what it passes; only the straight run stops there.
     *
     * <p>The flanking modes are asking to finish somewhere specific, so stopping them the moment they came within
     * striking distance would park them in front of the target every time, which is the one place none of them meant
     * to be. They land the blow on the way past and carry on to where they were going.
     */
    public boolean stopsOnContact()
    {
        return this == STRAIGHT;
    }

    /** Whether a dash in this mode can meet another head on. Only a straight dash commits to the centre line. */
    public boolean canClash()
    {
        return this == STRAIGHT;
    }

    public static DashMode byId(int id)
    {
        DashMode[] all = values();
        return id < 0 || id >= all.length ? STRAIGHT : all[id];
    }
}
