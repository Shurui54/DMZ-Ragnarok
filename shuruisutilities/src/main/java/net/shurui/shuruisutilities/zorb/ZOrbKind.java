package net.shurui.shuruisutilities.zorb;

/**
 * What a single Z orb pays out. The ordinals are the wire and NBT contract (a synched byte on {@link ZOrbEntity},
 * a stored weight key in {@link ZOrbConfig}), so they are PINNED: never reorder, never insert in the middle, only
 * append. TP is 0, ZENI is 1, ITEM is 2.
 *
 * <p>Each kind carries the default orb colour the client tints its shell with (Z3). The colours are plain {@code
 * 0xRRGGBB} ints so a later editor can override them without a class change.
 */
public enum ZOrbKind
{
    /** Grants DragonMineZ Training Points. */
    TP(0xFF3B30),
    /** Grants the suite economy currency (zeni). */
    ZENI(0xFFC631),
    /** Grants a rolled item, rendered inside the orb; only ever the LAST orb of a chain. */
    ITEM(0xD9B8FF);

    private final int defaultColour;

    ZOrbKind(int defaultColour)
    {
        this.defaultColour = defaultColour;
    }

    /** The default {@code 0xRRGGBB} shell colour for this kind. */
    public int defaultColour()
    {
        return defaultColour;
    }

    /** The pinned ordinal as a byte, for the synched entity field and any packet. */
    public byte id()
    {
        return (byte) ordinal();
    }

    /** Resolve a pinned ordinal back to a kind, clamped to TP on any out-of-range value (never throws). */
    public static ZOrbKind byId(int id)
    {
        ZOrbKind[] all = values();
        return id >= 0 && id < all.length ? all[id] : TP;
    }
}
