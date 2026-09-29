package net.shurui.shuruisutilities.ocarina;

/**
 * The songs an ocarina racial can play.
 *
 * <p>The order is the WIRE FORMAT: the client sends the ordinal when it picks one, so songs may be added to the end
 * but never reordered.
 *
 * <p>Each carries the four-note phrase shown in the menu and played back as it is performed. Notes are lane indices
 * into the rhythm chart (up, left, down, right), which is what lets one instrument use the melee clash's own chart
 * machinery rather than a second, slightly different rhythm game.
 */
public enum OcarinaSong
{
    /** Heals the player's own party and guild inside the radius. Known from the moment the racial is. */
    MENDING("Song of Mending", new int[] { 0, 2, 1, 3 }),

    /** Buffs the same friendly set, and grows with the racial's level. */
    VALOUR("Song of Valour", new int[] { 3, 1, 0, 2 }),

    /** Lays a debuff on everyone in the radius who is NOT party or guild. */
    DISCORD("Song of Discord", new int[] { 2, 3, 1, 0 });

    public final String displayName;
    /** The phrase, as rhythm lanes. Shown in the menu so a player can learn it by sight. */
    public final int[] phrase;

    OcarinaSong(String displayName, int[] phrase)
    {
        this.displayName = displayName;
        this.phrase = phrase;
    }

    public static OcarinaSong byId(int id)
    {
        OcarinaSong[] all = values();
        return id < 0 || id >= all.length ? MENDING : all[id];
    }
}
