package net.shurui.dev.sdu.waypoint;

import net.minecraft.resources.ResourceLocation;

/**
 * What KIND of thing a compass marker points at, and therefore which pin is drawn for it.
 *
 * <p>Four commissioned pins, one per kind. Each carries its own texture and a colour sampled from that
 * texture's body tone, so pin, floating icon and beacon beam read as one marker.
 *
 * <p>{@link #NONE} is the fallback for everything predating the pins (manual {@code /rg npc waypoint} pins,
 * the space course, the shadow-dragon marker): no texture, and the compass falls back to the coloured
 * triangle, so nothing that used to work changes appearance until deliberately given a kind.
 */
public enum WaypointMark {

    /** No pin: draw the old coloured triangle (or the waypoint's item icon, which still wins). */
    NONE(null, 0),

    /** Main story. DMZ's {@code QuestType.SAGA}: the through-line a player is actually following. */
    MAIN("red", 0xFFFF8C26),

    /**
     * Everything questlike that is not the story line: DMZ's {@code QuestType.SIDEQUEST}, plus {@code DAILY} and
     * {@code EVENT}. Those two were purple until purple was given to sign-ups; a daily still reads as a quest.
     */
    SIDE("blue", 0xFF80A6F4),

    /** Airdrops, and world loot on a timer generally. Registered through {@link GlobalMarkers}. */
    AIRDROP("yellow", 0xFFE8BC1A),

    /**
     * Raid boss and tournament SIGN-UPS, and nothing else. Purple is the "you can miss this" colour: a quest
     * waits, but a sign-up window is open for minutes and then gone, and until this its only notice was a chat
     * line that scrolls away. These arrive as positionless notices ({@code Waypoint.notice}), because signing up
     * is a command, not a journey.
     */
    EVENT("purple", 0xFFB88BEA),

    /**
     * Tasks, the tutorial-style jobs, drawn green. Colour sampled from the pin's dominant opaque pixel like
     * MAIN and SIDE.
     *
     * <p>APPENDED after {@link #EVENT} on purpose: {@link #byOrdinal(int)} backs {@code Waypoint.decode}, so the
     * wire format is ordinal-based and inserting a value mid-list would remap every existing marker's colour
     * across the network. New kinds go on the end.
     *
     * <p>The pin art ({@code textures/gui/marker/green.png}) ships, but nothing PRODUCES a task waypoint yet, so
     * this kind is here for the colour mapping only until a task-to-waypoint bridge feeds one in.
     */
    TASK("green", 0xFF6FD816);

    /** Texture for the pin, or null for {@link #NONE}. */
    public final ResourceLocation texture;

    /** Opaque ARGB used for the beacon beam and the compass label line. */
    public final int color;

    WaypointMark(String name, int color) {
        this.texture = name == null
                ? null
                : new ResourceLocation("dmz_ragnarok", "textures/gui/marker/" + name + ".png");
        this.color = color;
    }

    private static final WaypointMark[] VALUES = values();

    public boolean hasPin() {
        return texture != null;
    }

    /** Never throws: an ordinal from a future build (or a corrupt one) reads as NONE rather than crashing. */
    public static WaypointMark byOrdinal(int ordinal) {
        return ordinal < 0 || ordinal >= VALUES.length ? NONE : VALUES[ordinal];
    }

    /** The beam colour as the three floats {@code BeaconRenderer.renderBeaconBeam} wants. */
    public float[] beamColor() {
        return new float[] {
                ((color >> 16) & 0xFF) / 255.0f,
                ((color >> 8) & 0xFF) / 255.0f,
                (color & 0xFF) / 255.0f };
    }
}
