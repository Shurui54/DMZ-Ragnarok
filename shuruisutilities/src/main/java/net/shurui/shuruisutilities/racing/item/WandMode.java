package net.shurui.shuruisutilities.racing.item;

/**
 * The track wand's edit modes, cycled with sneak + right-click-air and stored in the stack NBT. Common code so the
 * key (which acts on a mode) and the client preview (which labels the held mode) agree on the ordinal wire value.
 *
 * <p>Click bindings per mode (right-click a block = primary, sneak + right-click a block = alternate), from the
 * racing plan section 2:
 * <ul>
 *   <li>{@link #NODE}: append a node / (alt) delete the nearest node</li>
 *   <li>{@link #MOVE}: move the selected node here</li>
 *   <li>{@link #INSERT}: insert a node splitting the nearest edge here</li>
 *   <li>{@link #WIDTH}: nearest node width +1 / (alt) -1</li>
 *   <li>{@link #CHECKPOINT}: toggle the nearest node's checkpoint</li>
 *   <li>{@link #START}: set the nearest node as the start</li>
 *   <li>{@link #BRANCH}: extend a fork from the selected node / (alt) rejoin the nearest node</li>
 *   <li>{@link #PAD}, {@link #ITEMROW}: RETIRED. Boost pads and item-box spawners are hand-placed blocks now (a
 *       spawner placed inside a track's bounds binds to that track). The constants stay so the ordinal stored on an
 *       existing wand still decodes; {@link #next()} skips them, and a wand left on one only explains the change.</li>
 *   <li>{@link #GRID}: add a manual grid slot / (alt) clear the grid</li>
 * </ul>
 */
public enum WandMode
{
    NODE,
    MOVE,
    INSERT,
    WIDTH,
    CHECKPOINT,
    START,
    BRANCH,
    PAD,
    ITEMROW,
    GRID;

    private static final WandMode[] VALUES = values();

    public WandMode next()
    {
        WandMode m = this;
        do
            m = VALUES[(m.ordinal() + 1) % VALUES.length];
        while (m.retired());
        return m;
    }

    /** True for the modes the wand no longer offers (pads and item rows are hand-placed blocks now). */
    public boolean retired()
    {
        return this == PAD || this == ITEMROW;
    }

    public static WandMode byOrdinal(int ordinal)
    {
        if (ordinal < 0 || ordinal >= VALUES.length)
            return NODE;
        return VALUES[ordinal];
    }
}
