package net.shurui.shuruisutilities.racing;

/**
 * The action ids the track-editor GUI ({@code TrackEditorScreen}, core client) sends to the server over packet 119
 * ({@code PacketTrackEditorAction}), and that the Ragnarok Key's {@code TrackEditService} applies. Pure common
 * constants so the screen and the key agree without either importing the other; the ids are a wire contract, so
 * append only, never renumber.
 *
 * <p>How each action carries its payload (packet 119 fields are {@code action}, {@code pos}, {@code arg},
 * {@code text}):
 * <ul>
 *   <li>the numeric SET_* actions carry the value in {@code arg} (blocks, ticks, a percent, or a small ordinal);</li>
 *   <li>the block actions (SET_BUILD_SURFACE, SET_EDGE, SET_WALL, ADD_SURFACE, REMOVE_SURFACE) carry the block's
 *       resource-location id in {@code text}; ADD_SURFACE_HELD instead reads the player's off-hand block server-side;</li>
 *   <li>the NODE_* actions carry the target node id in {@code pos.getX()} and, for NODE_SET_WIDTH, the width in
 *       {@code arg}, so a per-node edit is unambiguous without depending on the wand's in-world selection;</li>
 *   <li>REFRESH, BUILD, UNDO and VALIDATE carry nothing (the server pushes a fresh preview / builds / validates).</li>
 * </ul>
 */
public final class EditorAction
{
    private EditorAction() {}

    /** Ask the server to push a fresh preview (packet 118) for the bound track. */
    public static final int REFRESH = 0;

    // --- race parameters (arg = value) ---
    public static final int SET_LAPS = 1;          // arg = laps
    public static final int SET_WIDTH = 2;         // arg = default width, blocks
    public static final int SET_OFFROAD = 3;       // arg = off-road multiplier as a percent (5..100)
    public static final int SET_RESCUE = 4;        // arg = rescue distance, blocks
    public static final int SET_FALLDEPTH = 5;     // arg = fall depth, blocks
    public static final int SET_WALLHEIGHT = 6;    // arg = wall height, blocks
    public static final int SET_HEADROOM = 7;      // arg = cleared headroom, blocks
    public static final int SET_GATESPACING = 8;   // arg = auto-gate spacing, blocks
    public static final int SET_BOUNCE = 9;        // arg = 0 GEOMETRY, 1 SURFACE, 2 BOTH
    public static final int SET_GRIDMODE = 10;     // arg = 0 AUTO, 1 MANUAL
    public static final int SET_GRIDSPACING = 11;  // arg = grid spacing, blocks

    // --- build blocks (text = block id) ---
    public static final int SET_BUILD_SURFACE = 12;
    public static final int SET_EDGE = 13;
    public static final int SET_WALL = 14;

    // --- surface set (text = block id; ADD_SURFACE_HELD reads the off-hand) ---
    public static final int ADD_SURFACE = 15;
    public static final int REMOVE_SURFACE = 16;
    public static final int ADD_SURFACE_HELD = 17;

    // --- per-node (pos.getX() = node id) ---
    public static final int NODE_SET_WIDTH = 18;      // arg = width, blocks
    public static final int NODE_TOGGLE_WALL_L = 19;
    public static final int NODE_TOGGLE_WALL_R = 20;
    public static final int NODE_TOGGLE_CHECKPOINT = 21;

    // --- build actions ---
    public static final int BUILD = 22;
    public static final int UNDO = 23;
    public static final int VALIDATE = 24;

    /** The three bounce-mode names, indexed by the {@link #SET_BOUNCE} ordinal. */
    public static final String[] BOUNCE_MODES = { "GEOMETRY", "SURFACE", "BOTH" };
}
